package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.rest.asset.LocaleInfo;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.rest.asset.MultiLocalizedAssetBody;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GenerateMultiLocalizedAssetJobTest {

  @Mock QuartzPollableTaskScheduler quartzPollableTaskSchedulerMock;

  @Mock AssetLocalizeAsyncJobSubmissionService assetLocalizeAsyncJobSubmissionService;

  @Mock PollableFuture<LocalizedAssetBody> pollableFutureMock;

  @Mock PollableTask pollableTaskMock;

  @Mock AssetRepository assetRepositoryMock;

  @Mock RepositoryLocale repoLocaleMock;

  @Mock Locale localeMock;

  @Mock Asset assetMock;

  MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Mock RepositoryLocaleRepository repositoryLocaleRepositoryMock;

  @Mock Repository repositoryMock;

  @Captor ArgumentCaptor<QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody>> quartzJobInfoCaptor;

  MultiLocalizedAssetBody multiLocalizedAssetBody;

  @Spy
  GenerateMultiLocalizedAssetJob generateMultiLocalizedAssetJob =
      new GenerateMultiLocalizedAssetJob();

  @Before
  public void setUp() {
    generateMultiLocalizedAssetJob.assetRepository = assetRepositoryMock;
    generateMultiLocalizedAssetJob.repositoryLocaleRepository = repositoryLocaleRepositoryMock;
    doReturn(1L).when(generateMultiLocalizedAssetJob).getParentId();
    when(repositoryLocaleRepositoryMock.findByRepositoryIdAndLocaleId(
            isA(Long.class), isA(Long.class)))
        .thenReturn(repoLocaleMock);
    when(repoLocaleMock.getLocale()).thenReturn(localeMock);
    when(localeMock.getBcp47Tag()).thenReturn("fr-FR").thenReturn("ga-IE");
    when(repositoryMock.getId()).thenReturn(1L);
    when(assetMock.getRepository()).thenReturn(repositoryMock);
    when(repositoryMock.getName()).thenReturn("testRepo");
    when(assetRepositoryMock.findById(isA(Long.class)))
        .thenReturn(java.util.Optional.of(assetMock));
    when(pollableFutureMock.getPollableTask()).thenReturn(pollableTaskMock);
    when(pollableTaskMock.getId()).thenReturn(1L).thenReturn(2L);
    when(quartzPollableTaskSchedulerMock.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFutureMock);
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFutureMock);
    generateMultiLocalizedAssetJob.quartzPollableTaskScheduler = quartzPollableTaskSchedulerMock;
    generateMultiLocalizedAssetJob.assetLocalizeAsyncJobSubmissionService =
        assetLocalizeAsyncJobSubmissionService;
    generateMultiLocalizedAssetJob.meterRegistry = meterRegistry;
    multiLocalizedAssetBody = new MultiLocalizedAssetBody();
    List<LocaleInfo> localeInfos = new ArrayList<>();
    LocaleInfo localeInfo = new LocaleInfo();
    localeInfo.setLocaleId(1L);
    LocaleInfo localeInfo2 = new LocaleInfo();
    localeInfo2.setLocaleId(2L);
    localeInfos.add(localeInfo);
    localeInfos.add(localeInfo2);
    multiLocalizedAssetBody.setLocaleInfos(localeInfos);
    multiLocalizedAssetBody.setSourceContent("sourceContent");
    multiLocalizedAssetBody.setAssetId(1L);
    multiLocalizedAssetBody.setSchedulerName("schedulerName");
    multiLocalizedAssetBody.setPullWithNoSource(true);
    multiLocalizedAssetBody.setPullWithNoSourceBranches(
        Arrays.asList(null, "authoring/checkout", "authoring/settings"));
  }

  @Test
  public void testMultipleLocalisedAssetJobsScheduled() throws Exception {
    assertThat(generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled).isTrue();
    MultiLocalizedAssetBody output = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);
    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    List<QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody>> allValues =
        quartzJobInfoCaptor.getAllValues();
    assertThat(allValues.stream().map(QuartzJobInfo::getScheduler))
        .containsOnly(multiLocalizedAssetBody.getSchedulerName());
    assertThat(allValues.stream().filter(q -> q.getParentId() == 1L).count()).isEqualTo(2);
    assertThat(allValues.stream().map(QuartzJobInfo::getInput))
        .extracting("localeId")
        .containsExactlyInAnyOrder(1L, 2L);
    assertThat(allValues.stream().map(QuartzJobInfo::getInput))
        .allMatch(LocalizedAssetBody::isPullWithNoSource)
        .extracting(LocalizedAssetBody::getPullWithNoSourceBranches)
        .containsOnly(Arrays.asList(null, "authoring/checkout", "authoring/settings"));
    assertThat(output.getGenerateLocalizedAssetJobIds().size()).isEqualTo(2);
    assertThat(output.getGenerateLocalizedAssetJobIds().get("fr-FR")).isEqualTo(1L);
    assertThat(output.getGenerateLocalizedAssetJobIds().get("ga-IE")).isEqualTo(2L);
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
  }

  @Test
  public void testChildSchedulerNameOverridesParentSchedulerName() throws Exception {
    generateMultiLocalizedAssetJob.childSchedulerName = "assetlocalize";
    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);
    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    List<QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody>> allValues =
        quartzJobInfoCaptor.getAllValues();
    assertThat(allValues.stream().map(QuartzJobInfo::getScheduler)).containsOnly("assetlocalize");
  }

  @Test
  public void testDurableAsyncQueueFlagRoutesChildJobsToAssetLocalizeQueue() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;

    MultiLocalizedAssetBody output = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(assetLocalizeAsyncJobSubmissionService, times(2))
        .scheduleJob(quartzJobInfoCaptor.capture());
    verify(quartzPollableTaskSchedulerMock, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    List<QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody>> allValues =
        quartzJobInfoCaptor.getAllValues();
    assertThat(allValues.stream().map(QuartzJobInfo::getScheduler))
        .containsOnly(multiLocalizedAssetBody.getSchedulerName());
    assertThat(output.getGenerateLocalizedAssetJobIds().size()).isEqualTo(2);
    assertThat(output.getGenerateLocalizedAssetJobIds().get("fr-FR")).isEqualTo(1L);
    assertThat(output.getGenerateLocalizedAssetJobIds().get("ga-IE")).isEqualTo(2L);
    assertThat(scheduleCount("assetlocalize", "succeeded")).isEqualTo(2);
  }

  @Test
  public void testOnlyGlobalAsyncQueueFlagKeepsChildJobsOnQuartz() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = true;

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void acceptedChildrenRemainMappedWhenSchedulingAndTimerMetricsFail() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    meterRegistry.gauge(
        "GenerateMultiLocalizedAssetJob.schedule",
        Tags.of("route", "assetlocalize", "result", "succeeded"),
        1);
    meterRegistry.gauge(
        "GenerateMultiLocalizedAssetJob.call", Tags.of("repositoryName", "testRepo"), 1);

    MultiLocalizedAssetBody result = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    assertThat(result.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .containsEntry("ga-IE", 2L)
        .hasSize(2);
    verify(assetLocalizeAsyncJobSubmissionService, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(quartzPollableTaskSchedulerMock);
  }

  @Test
  public void partialFanOutFailureSurvivesSchedulingAndTimerMetricFailures() {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    RuntimeException failure = new RuntimeException("enqueue outcome unknown");
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFutureMock)
        .thenThrow(failure);
    meterRegistry.gauge(
        "GenerateMultiLocalizedAssetJob.schedule",
        Tags.of("route", "assetlocalize", "result", "failed"),
        1);
    meterRegistry.gauge(
        "GenerateMultiLocalizedAssetJob.call", Tags.of("repositoryName", "testRepo"), 1);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .hasSize(1);
    verifyNoInteractions(quartzPollableTaskSchedulerMock);
  }

  @Test
  public void testOnlyAssetLocalizeAsyncQueueFlagKeepsChildJobsOnQuartz() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = true;

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void testProducerRollbackRoutesChildrenToQuartzWithChildSchedulerOverride()
      throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = false;
    generateMultiLocalizedAssetJob.childSchedulerName = "rollbackScheduler";

    MultiLocalizedAssetBody output = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    assertThat(quartzJobInfoCaptor.getAllValues())
        .allSatisfy(
            jobInfo -> {
              assertThat(jobInfo.getScheduler()).isEqualTo("rollbackScheduler");
              assertThat(jobInfo.getParentId()).isEqualTo(1L);
              assertThat(jobInfo.isInlineInput()).isFalse();
            });
    assertThat(output.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .containsEntry("ga-IE", 2L);
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void testProducerRollbackDoesNotRequireSubmissionService() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = false;
    generateMultiLocalizedAssetJob.assetLocalizeAsyncJobSubmissionService = null;

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    assertThat(quartzJobInfoCaptor.getAllValues().stream().map(QuartzJobInfo::getScheduler))
        .containsOnly(multiLocalizedAssetBody.getSchedulerName());
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void testPartialFanOutDoesNotFallBackAfterAmbiguousQueueFailure() {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    RuntimeException failure = new RuntimeException("enqueue outcome unknown");
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFutureMock)
        .thenThrow(failure);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    verify(assetLocalizeAsyncJobSubmissionService, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(quartzPollableTaskSchedulerMock);
    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds())
        .hasSize(1)
        .containsEntry("fr-FR", 1L);
    assertThat(scheduleCount("assetlocalize", "succeeded")).isEqualTo(1);
    assertThat(scheduleCount("assetlocalize", "failed")).isEqualTo(1);
  }

  @Test
  public void testProducerRollbackDoesNotFallBackAfterQuartzFailure() {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = false;
    RuntimeException failure = new RuntimeException("Quartz outcome unknown");
    when(quartzPollableTaskSchedulerMock.scheduleJob(isA(QuartzJobInfo.class))).thenThrow(failure);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    verify(quartzPollableTaskSchedulerMock).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(scheduleCount("quartz", "failed")).isEqualTo(1);
  }

  @Test
  public void testDurableAsyncQueueEnabledFailsFastWhenSubmissionServiceUnavailable() {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    generateMultiLocalizedAssetJob.assetLocalizeAsyncJobSubmissionService = null;

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("submission service is unavailable");
    verifyNoInteractions(quartzPollableTaskSchedulerMock);
    assertThat(scheduleCount("assetlocalize", "failed")).isEqualTo(1);
  }

  private double scheduleCount(String route, String result) {
    return meterRegistry
        .get("GenerateMultiLocalizedAssetJob.schedule")
        .tag("route", route)
        .tag("result", result)
        .counter()
        .count();
  }
}
