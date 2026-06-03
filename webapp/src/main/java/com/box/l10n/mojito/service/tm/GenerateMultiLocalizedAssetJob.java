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

      for (LocaleInfo localeInfo : multiLocalizedAssetBody.getLocaleInfos()) {

        RepositoryLocale repositoryLocale =
            repositoryLocaleRepository.findByRepositoryIdAndLocaleId(
                asset.getRepository().getId(), localeInfo.getLocaleId());

        String outputTag =
            localeInfo.getOutputBcp47tag() != null
                ? localeInfo.getOutputBcp47tag()
                : repositoryLocale.getLocale().getBcp47Tag();
        QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo =
            QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
                .withInlineInput(false)
                .withParentId(getParentId())
                .withInput(createLocalizedAssetBody(localeInfo, multiLocalizedAssetBody))
                .withScheduler(getChildSchedulerName(multiLocalizedAssetBody))
                .withMessage(
                    "Generate localized asset for locale: "
                        + outputTag
                        + ", asset: "
                        + asset.getPath())
                .build();
        multiLocalizedAssetBody.addGenerateLocalizedAddedJobIdToMap(
            outputTag, scheduleLocalizedAssetJob(quartzJobInfo).getPollableTask().getId());
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

  String getChildSchedulerName(MultiLocalizedAssetBody multiLocalizedAssetBody) {
    if (childSchedulerName == null || childSchedulerName.trim().isEmpty()) {
      return multiLocalizedAssetBody.getSchedulerName();
    }
    return childSchedulerName;
  }

  PollableFuture<LocalizedAssetBody> scheduleLocalizedAssetJob(
      QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> quartzJobInfo) {
    String route = isAssetLocalizeAsyncQueueEnabled() ? "assetlocalize" : "quartz";
    try {
      PollableFuture<LocalizedAssetBody> pollableFuture;
      if (isAssetLocalizeAsyncQueueEnabled()) {
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
        && asyncJobQueueAssetLocalizeProducerEnabled;
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
      if (failure instanceof VirtualMachineError
          || "java.lang.ThreadDeath".equals(failure.getClass().getName())) {
        throw (Error) failure;
      }
      logger.warn("Failed to record multi-locale asset scheduling metric", failure);
    }
  }

  private LocalizedAssetBody createLocalizedAssetBody(
      LocaleInfo localeInfo, MultiLocalizedAssetBody multiLocalizedAssetBody) {
    LocalizedAssetBody localizedAssetBody = new LocalizedAssetBody();
    localizedAssetBody.setLocaleId(localeInfo.getLocaleId());
    localizedAssetBody.setContent(multiLocalizedAssetBody.getSourceContent());
    localizedAssetBody.setAssetId(multiLocalizedAssetBody.getAssetId());
    localizedAssetBody.setOutputBcp47tag(localeInfo.getOutputBcp47tag());
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
