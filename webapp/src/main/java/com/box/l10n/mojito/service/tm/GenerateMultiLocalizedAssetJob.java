package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.rest.asset.AssetWithIdNotFoundException;
import com.box.l10n.mojito.rest.asset.LocaleInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

public class GenerateMultiLocalizedAssetJob
    extends QuartzPollableJob<MultiLocalizedAssetBody, MultiLocalizedAssetBody> {

  private static final Logger logger =
      LoggerFactory.getLogger(GenerateMultiLocalizedAssetJob.class);

  @Autowired QuartzPollableTaskScheduler quartzPollableTaskScheduler;

  @Autowired(required = false)
  AssetLocalizeAsyncJobSubmissionService assetLocalizeAsyncJobSubmissionService;

  @Autowired AssetRepository assetRepository;

  @Autowired RepositoryLocaleRepository repositoryLocaleRepository;

  @Autowired MeterRegistry meterRegistry;

  /**
   * Optional scheduler override for locale child jobs.
   *
   * <p>Used as a stop-gap to decouple parent job submission scheduler (API->worker handoff) from
   * child fan-out scheduler. If unset, child jobs keep legacy behavior and use parent scheduler.
   */
  @Value("${l10n.assetWS.quartz.childSchedulerName:}")
  String childSchedulerName;

  @Value("${l10n.org.async-job-queue.enabled:false}")
  boolean asyncJobQueueEnabled;

  @Value("${l10n.org.async-job-queue.asset-localize.enabled:false}")
  boolean asyncJobQueueAssetLocalizeEnabled;

  @Value("${l10n.org.async-job-queue.asset-localize.producer-enabled:true}")
  boolean asyncJobQueueAssetLocalizeProducerEnabled = true;

  // Parallel admission still needs durable child reconciliation; direct rollout must not opt it in.
  @Value("${l10n.org.async-job-queue.asset-localize.fanout-enabled:false}")
  boolean asyncJobQueueAssetLocalizeFanoutEnabled;

  @Override
  public MultiLocalizedAssetBody call(MultiLocalizedAssetBody multiLocalizedAssetBody)
      throws Exception {

    Asset asset = assetRepository.findById(multiLocalizedAssetBody.getAssetId()).orElse(null);

    if (asset == null) {
      throw new AssetWithIdNotFoundException(multiLocalizedAssetBody.getAssetId());
    }

    String repositoryName = asset.getRepository().getName();
    long startNanos = System.nanoTime();
    try {

      boolean preflightQueue =
          isAssetLocalizeAsyncQueueEnabled() && multiLocalizedAssetBody.getPullRunName() == null;
      Stream<ResolvedLocale> resolved =
          multiLocalizedAssetBody.getLocaleInfos().stream()
              .map(
                  localeInfo ->
                      resolveLocale(asset.getRepository().getId(), localeInfo, preflightQueue));
      // Resolve all queue children before accepting any. Quartz keeps its legacy lazy order.
      Iterable<ResolvedLocale> locales = preflightQueue ? resolved.toList() : resolved::iterator;
      for (ResolvedLocale locale : locales) {
        QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
            QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                .withInlineInput(false)
                .withParentId(getParentId())
                .withInput(createLocalizedAssetBody(locale, multiLocalizedAssetBody))
                .withScheduler(getChildSchedulerName(multiLocalizedAssetBody))
                .withMessage(
                    "Generate localized asset for locale: "
                        + locale.outputTag()
                        + ", asset: "
                        + asset.getPath())
                .build();
        multiLocalizedAssetBody.addGenerateLocalizedAddedJobIdToMap(
            locale.outputTag(), scheduleLocalizedAssetJob(quartzJobInfo).getPollableTask().getId());
      }

      return multiLocalizedAssetBody;
    } finally {
      long durationNanos = System.nanoTime() - startNanos;
      recordMetric(
          () ->
              meterRegistry
                  .timer("GenerateMultiLocalizedAssetJob.call", "repositoryName", repositoryName)
                  .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS));
    }
  }

  protected long getParentId() {
    return getCurrentPollableTask().getId();
  }

  private ResolvedLocale resolveLocale(
      long repositoryId, LocaleInfo localeInfo, boolean preflightQueue) {
    if (preflightQueue
        && (localeInfo == null
            || localeInfo.getLocaleId() == null
            || localeInfo.getLocaleId() <= 0)) {
      throw new IllegalArgumentException("Locale id must be positive");
    }
    Long localeId = localeInfo.getLocaleId();
    String outputOverride = localeInfo.getOutputBcp47tag();
    RepositoryLocale repositoryLocale =
        repositoryLocaleRepository.findByRepositoryIdAndLocaleId(repositoryId, localeId);
    if (preflightQueue && repositoryLocale == null) {
      throw new IllegalArgumentException(
          "Locale " + localeId + " is not configured for repository " + repositoryId);
    }
    String outputTag =
        outputOverride != null ? outputOverride : repositoryLocale.getLocale().getBcp47Tag();
    return new ResolvedLocale(localeId, outputTag, outputOverride);
  }

  private record ResolvedLocale(Long localeId, String outputTag, String outputOverride) {}

  String getChildSchedulerName(MultiLocalizedAssetBody multiLocalizedAssetBody) {
    if (childSchedulerName == null || childSchedulerName.trim().isEmpty()) {
      return multiLocalizedAssetBody.getSchedulerName();
    }
    return childSchedulerName;
  }

  PollableFuture<LocalizedAssetBody> scheduleLocalizedAssetJob(
      QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo) {
    boolean useAsyncQueue =
        isAssetLocalizeAsyncQueueEnabled()
            && AssetLocalizeAsyncJobEligibility.isEligible(quartzJobInfo.getInput());
    String route = useAsyncQueue ? "assetlocalize" : "quartz";
    try {
      PollableFuture<LocalizedAssetBody> pollableFuture;
      if (useAsyncQueue) {
        if (assetLocalizeAsyncJobSubmissionService == null) {
          throw new IllegalStateException(
              "Asset localize async queue is enabled but the submission service is unavailable");
        }
        pollableFuture = assetLocalizeAsyncJobSubmissionService.scheduleJob(quartzJobInfo);
      } else {
        pollableFuture = quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
      }
      recordLocalizedAssetSchedule(route, "succeeded");
      return pollableFuture;
    } catch (RuntimeException e) {
      recordLocalizedAssetSchedule(route, "failed");
      throw e;
    }
  }

  private boolean isAssetLocalizeAsyncQueueEnabled() {
    return asyncJobQueueEnabled
        && asyncJobQueueAssetLocalizeEnabled
        && asyncJobQueueAssetLocalizeProducerEnabled
        && asyncJobQueueAssetLocalizeFanoutEnabled;
  }

  private void recordLocalizedAssetSchedule(String route, String result) {
    recordMetric(
        () ->
            meterRegistry
                .counter(
                    "GenerateMultiLocalizedAssetJob.schedule",
                    Tags.of("route", route, "result", result))
                .increment());
  }

  private void recordMetric(Runnable recording) {
    try {
      recording.run();
    } catch (Throwable failure) {
      rethrowDirectJvmFatal(failure);
      try {
        logger.warn("Failed to record multi-locale asset scheduling metric", failure);
      } catch (Throwable loggingFailure) {
        rethrowDirectJvmFatal(loggingFailure);
        // Diagnostics must not reject accepted children or replace an uncertain submission error.
      }
    }
  }

  private static void rethrowDirectJvmFatal(Throwable failure) {
    if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath) {
      throw (Error) failure;
    }
  }

  private LocalizedAssetBody createLocalizedAssetBody(
      ResolvedLocale locale, MultiLocalizedAssetBody multiLocalizedAssetBody) {
    LocalizedAssetBody localizedAssetBody = new LocalizedAssetBody();
    localizedAssetBody.setLocaleId(locale.localeId());
    localizedAssetBody.setContent(multiLocalizedAssetBody.getSourceContent());
    localizedAssetBody.setAssetId(multiLocalizedAssetBody.getAssetId());
    localizedAssetBody.setOutputBcp47tag(locale.outputOverride());
    localizedAssetBody.setFilterConfigIdOverride(
        multiLocalizedAssetBody.getFilterConfigIdOverride());
    localizedAssetBody.setFilterOptions(multiLocalizedAssetBody.getFilterOptions());
    localizedAssetBody.setInheritanceMode(multiLocalizedAssetBody.getInheritanceMode());
    localizedAssetBody.setPullRunName(multiLocalizedAssetBody.getPullRunName());
    localizedAssetBody.setStatus(multiLocalizedAssetBody.getStatus());
    localizedAssetBody.setPullWithNoSource(multiLocalizedAssetBody.isPullWithNoSource());
    localizedAssetBody.setPullWithNoSourceBranches(
        multiLocalizedAssetBody.getPullWithNoSourceBranches());
    return localizedAssetBody;
  }
}
