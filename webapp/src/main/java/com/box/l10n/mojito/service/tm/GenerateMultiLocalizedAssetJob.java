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
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

public class GenerateMultiLocalizedAssetJob
    extends QuartzPollableJob<MultiLocalizedAssetBody, MultiLocalizedAssetBody> {

  @Autowired QuartzPollableTaskScheduler quartzPollableTaskScheduler;

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

  @Override
  public MultiLocalizedAssetBody call(MultiLocalizedAssetBody multiLocalizedAssetBody)
      throws Exception {

    Asset asset = assetRepository.findById(multiLocalizedAssetBody.getAssetId()).orElse(null);

    if (asset == null) {
      throw new AssetWithIdNotFoundException(multiLocalizedAssetBody.getAssetId());
    }

    try (var timer =
        Timer.resource(meterRegistry, "GenerateMultiLocalizedAssetJob.call")
            .tag("repositoryName", asset.getRepository().getName())) {

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
            outputTag,
            quartzPollableTaskScheduler.scheduleJob(quartzJobInfo).getPollableTask().getId());
      }

      return multiLocalizedAssetBody;
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

  private LocalizedAssetBody createLocalizedAssetBody(
      LocaleInfo localeInfo, MultiLocalizedAssetBody multiLocalizedAssetBody) {
    LocalizedAssetBody localizedAssetBody = new LocalizedAssetBody();
    localizedAssetBody.setLocaleId(localeInfo.getLocaleId());
    localizedAssetBody.setContent(multiLocalizedAssetBody.getSourceContent());
    localizedAssetBody.setAssetId(multiLocalizedAssetBody.getAssetId());
    localizedAssetBody.setOutputBcp47tag(localeInfo.getOutputBcp47tag());
    localizedAssetBody.setContent(multiLocalizedAssetBody.getSourceContent());
    localizedAssetBody.setFilterConfigIdOverride(
        multiLocalizedAssetBody.getFilterConfigIdOverride());
    localizedAssetBody.setFilterOptions(multiLocalizedAssetBody.getFilterOptions());
    localizedAssetBody.setInheritanceMode(multiLocalizedAssetBody.getInheritanceMode());
    localizedAssetBody.setPullRunName(multiLocalizedAssetBody.getPullRunName());
    localizedAssetBody.setStatus(multiLocalizedAssetBody.getStatus());
    return localizedAssetBody;
  }
}
