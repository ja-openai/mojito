package com.box.l10n.mojito.rest.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobSubmissionService;
import com.box.l10n.mojito.service.tm.GenerateLocalizedAssetJob;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AssetWSAsyncQueueRoutingTest {

  @Mock AssetRepository assetRepository;
  @Mock QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  @Mock AssetLocalizeAsyncJobSubmissionService assetLocalizeAsyncJobSubmissionService;
  @Mock PollableFuture<LocalizedAssetBody> pollableFuture;
  @Mock PollableTask pollableTask;

  @Captor ArgumentCaptor<QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody>> jobInfoCaptor;

  AssetWS assetWS = new AssetWS();

  @Before
  public void setUp() {
    assetWS.assetRepository = assetRepository;
    assetWS.quartzPollableTaskScheduler = quartzPollableTaskScheduler;
    assetWS.assetLocalizeAsyncJobSubmissionService = assetLocalizeAsyncJobSubmissionService;
    assetWS.meterRegistry = new SimpleMeterRegistry();
    assetWS.schedulerName = "defaultScheduler";

    when(assetRepository.getReferenceById(11L)).thenReturn(asset());
    when(pollableFuture.getPollableTask()).thenReturn(pollableTask);
  }

  @Test
  public void getLocalizedAssetForContentAsyncUsesQuartzByDefault() throws Exception {
    assertThat(assetWS.asyncJobQueueAssetLocalizeProducerEnabled).isTrue();
    LocalizedAssetBody input = new LocalizedAssetBody();
    when(quartzPollableTaskScheduler.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFuture);

    PollableTask result = assetWS.getLocalizedAssetForContentAsync(11L, input);

    assertThat(result).isSameAs(pollableTask);
    assertThat(input.getAssetId()).isEqualTo(11L);
    verify(quartzPollableTaskScheduler).scheduleJob(jobInfoCaptor.capture());
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> jobInfo = jobInfoCaptor.getValue();
    assertThat(jobInfo.getClazz()).isEqualTo(GenerateLocalizedAssetJob.class);
    assertThat(jobInfo.getInput()).isSameAs(input);
    assertThat(jobInfo.getScheduler()).isEqualTo("defaultScheduler");
    assertThat(jobInfo.isInlineInput()).isFalse();
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(1);
  }

  @Test
  public void getLocalizedAssetForContentAsyncUsesDurableQueueWhenEnabled() throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFuture);

    PollableTask result = assetWS.getLocalizedAssetForContentAsync(11L, input);

    assertThat(result).isSameAs(pollableTask);
    verify(assetLocalizeAsyncJobSubmissionService).scheduleJob(jobInfoCaptor.capture());
    verify(quartzPollableTaskScheduler, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> jobInfo = jobInfoCaptor.getValue();
    assertThat(jobInfo.getClazz()).isEqualTo(GenerateLocalizedAssetJob.class);
    assertThat(jobInfo.getInput()).isSameAs(input);
    assertThat(jobInfo.getScheduler()).isEqualTo("defaultScheduler");
    assertThat(jobInfo.isInlineInput()).isFalse();
    assertThat(scheduleCount("assetlocalize", "succeeded")).isEqualTo(1);
  }

  @Test
  public void acceptedQueueTaskIsReturnedWhenSchedulingMetricsFail() throws Exception {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.meterRegistry.gauge(
        "assetWS.getLocalizedAssetForContentAsync.schedule",
        Tags.of("route", "assetlocalize", "result", "succeeded"),
        1);
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFuture);

    assertThat(assetWS.getLocalizedAssetForContentAsync(11L, new LocalizedAssetBody()))
        .isSameAs(pollableTask);

    verify(assetLocalizeAsyncJobSubmissionService).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(quartzPollableTaskScheduler);
  }

  @Test
  public void schedulingFailureIsNotReplacedByMetricFailure() {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.meterRegistry.gauge(
        "assetWS.getLocalizedAssetForContentAsync.schedule",
        Tags.of("route", "assetlocalize", "result", "failed"),
        1);
    RuntimeException failure = new RuntimeException("enqueue outcome unknown");
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenThrow(failure);

    assertThatThrownBy(
            () -> assetWS.getLocalizedAssetForContentAsync(11L, new LocalizedAssetBody()))
        .isSameAs(failure);

    verifyNoInteractions(quartzPollableTaskScheduler);
  }

  @Test
  public void lazyAssetLookupFailureIsNotMistakenForMetricFailure() {
    Asset asset = mock(Asset.class);
    RuntimeException failure = new jakarta.persistence.EntityNotFoundException("asset removed");
    when(assetRepository.getReferenceById(11L)).thenReturn(asset);
    when(asset.getRepository()).thenThrow(failure);

    assertThatThrownBy(
            () -> assetWS.getLocalizedAssetForContentAsync(11L, new LocalizedAssetBody()))
        .isSameAs(failure);

    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService, quartzPollableTaskScheduler);
  }

  @Test
  public void getLocalizedAssetForContentAsyncUsesQuartzWhenOnlyGlobalQueueIsEnabled()
      throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeProducerEnabled = true;
    when(quartzPollableTaskScheduler.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFuture);

    PollableTask result = assetWS.getLocalizedAssetForContentAsync(11L, input);

    assertThat(result).isSameAs(pollableTask);
    verify(quartzPollableTaskScheduler).scheduleJob(isA(QuartzJobInfo.class));
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(1);
  }

  @Test
  public void getLocalizedAssetForContentAsyncUsesQuartzWhenOnlyAssetLocalizeQueueIsEnabled()
      throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeProducerEnabled = true;
    when(quartzPollableTaskScheduler.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFuture);

    PollableTask result = assetWS.getLocalizedAssetForContentAsync(11L, input);

    assertThat(result).isSameAs(pollableTask);
    verify(quartzPollableTaskScheduler).scheduleJob(isA(QuartzJobInfo.class));
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(1);
  }

  @Test
  public void getLocalizedAssetForContentAsyncUsesQuartzWhenProducerIsDisabled() throws Exception {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeProducerEnabled = false;

    getLocalizedAssetForContentAsyncUsesQuartz();
  }

  @Test
  public void getLocalizedAssetForContentAsyncRollbackDoesNotRequireSubmissionService()
      throws Exception {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeProducerEnabled = false;
    assetWS.assetLocalizeAsyncJobSubmissionService = null;

    getLocalizedAssetForContentAsyncUsesQuartz();
  }

  private void getLocalizedAssetForContentAsyncUsesQuartz() throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    when(quartzPollableTaskScheduler.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFuture);

    assertThat(assetWS.getLocalizedAssetForContentAsync(11L, input)).isSameAs(pollableTask);

    verify(quartzPollableTaskScheduler).scheduleJob(jobInfoCaptor.capture());
    assertThat(jobInfoCaptor.getValue().getInput()).isSameAs(input);
    assertThat(jobInfoCaptor.getValue().getScheduler()).isEqualTo("defaultScheduler");
    assertThat(jobInfoCaptor.getValue().isInlineInput()).isFalse();
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(1);
  }

  @Test
  public void getLocalizedAssetForContentAsyncDoesNotFallBackAfterAmbiguousQueueFailure() {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    RuntimeException failure = new RuntimeException("enqueue outcome unknown");
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenThrow(failure);

    assertThatThrownBy(
            () -> assetWS.getLocalizedAssetForContentAsync(11L, new LocalizedAssetBody()))
        .isSameAs(failure);

    verify(assetLocalizeAsyncJobSubmissionService).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(quartzPollableTaskScheduler);
    assertThat(scheduleCount("assetlocalize", "failed")).isEqualTo(1);
  }

  @Test
  public void getLocalizedAssetForContentAsyncRollbackDoesNotFallBackAfterQuartzFailure() {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeProducerEnabled = false;
    RuntimeException failure = new RuntimeException("Quartz outcome unknown");
    when(quartzPollableTaskScheduler.scheduleJob(isA(QuartzJobInfo.class))).thenThrow(failure);

    assertThatThrownBy(
            () -> assetWS.getLocalizedAssetForContentAsync(11L, new LocalizedAssetBody()))
        .isSameAs(failure);

    verify(quartzPollableTaskScheduler).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(scheduleCount("quartz", "failed")).isEqualTo(1);
  }

  @Test
  public void getLocalizedAssetForContentAsyncFailsFastWhenQueueEnabledButUnavailable() {
    assetWS.asyncJobQueueEnabled = true;
    assetWS.asyncJobQueueAssetLocalizeEnabled = true;
    assetWS.assetLocalizeAsyncJobSubmissionService = null;

    assertThatThrownBy(
            () -> assetWS.getLocalizedAssetForContentAsync(11L, new LocalizedAssetBody()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("submission service is unavailable");
    verifyNoInteractions(quartzPollableTaskScheduler);
    assertThat(scheduleCount("assetlocalize", "failed")).isEqualTo(1);
  }

  private Asset asset() {
    Repository repository = new Repository();
    repository.setId(7L);
    repository.setName("repository");
    Asset asset = new Asset();
    asset.setRepository(repository);
    return asset;
  }

  private double scheduleCount(String route, String result) {
    return assetWS
        .meterRegistry
        .get("assetWS.getLocalizedAssetForContentAsync.schedule")
        .tag("route", route)
        .tag("result", result)
        .counter()
        .count();
  }
}
