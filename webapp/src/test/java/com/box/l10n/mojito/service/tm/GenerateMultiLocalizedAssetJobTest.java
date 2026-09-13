package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;

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
  public void directQueueEnablementDoesNotOptParallelChildrenIn() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;

    MultiLocalizedAssetBody output = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(output.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .containsEntry("ga-IE", 2L);
  }

  @Test
  public void testDurableAsyncQueueFlagRoutesChildJobsToAssetLocalizeQueue() throws Exception {
    enableQueue();

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
  public void queuePreflightRejectsMissingLaterLocaleBeforeAcceptingAnyChild() {
    assertMissingLaterLocaleRejected(null);
  }

  @Test
  public void queuePreflightDoesNotLetOutputAliasBypassRepositoryMembership() {
    assertMissingLaterLocaleRejected("custom-output-tag");
  }

  private void assertMissingLaterLocaleRejected(String outputTag) {
    enableQueue();
    multiLocalizedAssetBody.getLocaleInfos().get(1).setOutputBcp47tag(outputTag);
    when(repositoryLocaleRepositoryMock.findByRepositoryIdAndLocaleId(1L, 2L)).thenReturn(null);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Locale 2 is not configured for repository 1");

    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds()).isEmpty();
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService, quartzPollableTaskSchedulerMock);
  }

  @Test
  public void queuePreflightLookupFailurePreservesTheOriginalErrorWithoutAcceptingChildren() {
    enableQueue();
    RuntimeException failure = new IllegalStateException("locale lookup unavailable");
    when(repositoryLocaleRepositoryMock.findByRepositoryIdAndLocaleId(1L, 2L)).thenThrow(failure);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds()).isEmpty();
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService, quartzPollableTaskSchedulerMock);
  }

  @Test
  public void queuePreflightDefaultTagFailureDoesNotAcceptEarlierChildren() {
    enableQueue();
    RuntimeException failure = new IllegalStateException("default locale tag unavailable");
    when(localeMock.getBcp47Tag()).thenReturn("fr-FR").thenThrow(failure);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds()).isEmpty();
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService, quartzPollableTaskSchedulerMock);
  }

  @Test
  public void queuePreflightRejectsNullLaterEntryBeforeAcceptingChildren() {
    enableQueue();
    multiLocalizedAssetBody.getLocaleInfos().set(1, null);
    assertInvalidLocaleRejectedBeforeChildren();
  }

  @Test
  public void queuePreflightRejectsNullAndNonPositiveLaterIdsBeforeAcceptingChildren() {
    enableQueue();
    for (Long id : Arrays.asList(null, 0L, -1L)) {
      multiLocalizedAssetBody.getLocaleInfos().get(1).setLocaleId(id);
      assertInvalidLocaleRejectedBeforeChildren();
    }
  }

  private void assertInvalidLocaleRejectedBeforeChildren() {
    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Locale id must be positive");
    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds()).isEmpty();
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService, quartzPollableTaskSchedulerMock);
  }

  @Test
  public void queuePreflightKeepsEmptyFanOutAsANoOp() throws Exception {
    enableQueue();
    multiLocalizedAssetBody.setLocaleInfos(List.of());

    assertThat(generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(multiLocalizedAssetBody);

    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds()).isEmpty();
    verifyNoInteractions(
        repositoryLocaleRepositoryMock,
        assetLocalizeAsyncJobSubmissionService,
        quartzPollableTaskSchedulerMock);
  }

  @Test
  public void queuePreflightSnapshotsLocaleOrderAndAliasesWithOneLookupPerEntry() throws Exception {
    enableQueue();
    LocaleInfo second = multiLocalizedAssetBody.getLocaleInfos().get(1);
    second.setOutputBcp47tag("alias-for-second");
    when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
        .thenAnswer(
            invocation -> {
              // Both lookups must precede even the first submit, with no repeated resolution.
              verify(repositoryLocaleRepositoryMock).findByRepositoryIdAndLocaleId(1L, 1L);
              verify(repositoryLocaleRepositoryMock).findByRepositoryIdAndLocaleId(1L, 2L);
              second.setLocaleId(999L);
              second.setOutputBcp47tag("changed-after-preflight");
              multiLocalizedAssetBody.getLocaleInfos().clear();
              return pollableFutureMock;
            });

    MultiLocalizedAssetBody result = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(assetLocalizeAsyncJobSubmissionService, times(2))
        .scheduleJob(quartzJobInfoCaptor.capture());
    var children = quartzJobInfoCaptor.getAllValues();
    assertThat(children)
        .extracting(child -> child.getInput().getLocaleId())
        .containsExactly(1L, 2L);
    assertThat(children)
        .extracting(child -> child.getInput().getOutputBcp47tag())
        .containsExactly(null, "alias-for-second");
    assertThat(children.get(0).getMessage()).contains("locale: fr-FR");
    assertThat(children.get(1).getMessage()).contains("locale: alias-for-second");
    assertThat(result.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .containsEntry("alias-for-second", 2L)
        .hasSize(2);
    org.mockito.Mockito.verifyNoMoreInteractions(repositoryLocaleRepositoryMock);
    verifyNoInteractions(quartzPollableTaskSchedulerMock);
  }

  @Test
  public void queuePreflightDoesNotIntroduceADuplicateAliasPolicy() throws Exception {
    enableQueue();
    multiLocalizedAssetBody.getLocaleInfos().forEach(info -> info.setOutputBcp47tag("same"));

    MultiLocalizedAssetBody result = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(assetLocalizeAsyncJobSubmissionService, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(result.getGenerateLocalizedAssetJobIds()).containsEntry("same", 2L).hasSize(1);
  }

  @Test
  public void defaultQuartzRouteKeepsLazyLocaleResolution() {
    assertQuartzKeepsLazyLocaleResolution();
  }

  @Test
  public void disabledProducerKeepsQuartzLazyLocaleResolution() {
    enableQueue();
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = false;
    assertQuartzKeepsLazyLocaleResolution();
  }

  @Test
  public void emptyTrackedNameKeepsQuartzLazyLocaleResolution() {
    enableQueue();
    multiLocalizedAssetBody.setPullRunName("");
    assertQuartzKeepsLazyLocaleResolution();
  }

  private void assertQuartzKeepsLazyLocaleResolution() {
    RuntimeException failure = new IllegalStateException("second locale lookup unavailable");
    when(repositoryLocaleRepositoryMock.findByRepositoryIdAndLocaleId(1L, 2L)).thenThrow(failure);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    verify(quartzPollableTaskSchedulerMock).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .hasSize(1);
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
  }

  private void enableQueue() {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeFanoutEnabled = true;
  }

  @Test
  public void fanoutDisabledKeepsQuartzLazyLocaleResolution() {
    enableQueue();
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeFanoutEnabled = false;
    assertQuartzKeepsLazyLocaleResolution();
  }

  @Test
  public void fanoutDisabledDoesNotRequireQueueSubmissionService() throws Exception {
    enableQueue();
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeFanoutEnabled = false;
    generateMultiLocalizedAssetJob.assetLocalizeAsyncJobSubmissionService = null;
    generateMultiLocalizedAssetJob.childSchedulerName = "direct-canary-quartz-children";

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    assertThat(quartzJobInfoCaptor.getAllValues())
        .allSatisfy(
            job -> assertThat(job.getScheduler()).isEqualTo("direct-canary-quartz-children"));
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
  }

  @Test
  public void fanoutFlagAloneDoesNotEnableQueue() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeFanoutEnabled = true;

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
  }

  @Test
  public void trackedChildrenUseQuartzWhenQueueIsEnabled() throws Exception {
    assertTrackedChildrenUseQuartz("tracked-run");
  }

  @Test
  public void emptyPullRunNameRoutesChildrenToQuartz() throws Exception {
    assertTrackedChildrenUseQuartz("");
  }

  @Test
  public void whitespacePullRunNameRoutesChildrenToQuartz() throws Exception {
    assertTrackedChildrenUseQuartz(" \t\n ");
  }

  @Test
  public void trackedChildrenDoNotRequireQueueSubmissionService() throws Exception {
    generateMultiLocalizedAssetJob.assetLocalizeAsyncJobSubmissionService = null;

    assertTrackedChildrenUseQuartz("tracked-run");
  }

  private void assertTrackedChildrenUseQuartz(String pullRunName) throws Exception {
    enableQueue();
    generateMultiLocalizedAssetJob.childSchedulerName = "trackedScheduler";
    multiLocalizedAssetBody.setPullRunName(pullRunName);

    MultiLocalizedAssetBody output = generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(quartzJobInfoCaptor.capture());
    assertThat(quartzJobInfoCaptor.getAllValues())
        .allSatisfy(
            jobInfo -> {
              assertThat(jobInfo.getClazz()).isEqualTo(GenerateLocalizedAssetJob.class);
              assertThat(jobInfo.getScheduler()).isEqualTo("trackedScheduler");
              assertThat(jobInfo.getParentId()).isEqualTo(1L);
              assertThat(jobInfo.isInlineInput()).isFalse();
              assertThat(jobInfo.getInput().getPullRunName()).isEqualTo(pullRunName);
              assertThat(jobInfo.getInput().isPullWithNoSource()).isTrue();
              assertThat(jobInfo.getInput().getPullWithNoSourceBranches())
                  .containsExactly(null, "authoring/checkout", "authoring/settings");
            });
    assertThat(output.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .containsEntry("ga-IE", 2L)
        .hasSize(2);
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void trackedPartialFanOutDoesNotFallBackAfterQuartzFailure() {
    enableQueue();
    multiLocalizedAssetBody.setPullRunName("tracked-run");
    RuntimeException failure = new RuntimeException("Quartz outcome unknown");
    when(quartzPollableTaskSchedulerMock.scheduleJob(isA(QuartzJobInfo.class)))
        .thenReturn(pollableFutureMock)
        .thenThrow(failure);

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
        .isSameAs(failure);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .hasSize(1);
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(1);
    assertThat(scheduleCount("quartz", "failed")).isEqualTo(1);
  }

  @Test
  public void testOnlyGlobalAsyncQueueFlagKeepsChildJobsOnQuartz() throws Exception {
    generateMultiLocalizedAssetJob.asyncJobQueueEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeFanoutEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = true;

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void acceptedChildrenRemainMappedWhenSchedulingAndTimerMetricsFail() throws Exception {
    enableQueue();
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
  public void acceptedQueueChildrenSurviveFailedDiagnosticLogging() throws Throwable {
    assertAcceptedChildrenSurviveFailedDiagnosticLogging(true);
  }

  @Test
  public void acceptedQuartzChildrenSurviveFailedDiagnosticLogging() throws Throwable {
    assertAcceptedChildrenSurviveFailedDiagnosticLogging(false);
  }

  private void assertAcceptedChildrenSurviveFailedDiagnosticLogging(boolean queue)
      throws Throwable {
    if (queue) {
      enableQueue();
    }
    String route = queue ? "assetlocalize" : "quartz";
    collideDiagnostics(route, "succeeded");

    int warnings =
        withFailedDiagnosticLogging(
            () -> {
              MultiLocalizedAssetBody result =
                  generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);
              assertThat(result.getGenerateLocalizedAssetJobIds())
                  .containsEntry("fr-FR", 1L)
                  .containsEntry("ga-IE", 2L)
                  .hasSize(2);
            });

    assertThat(warnings).isEqualTo(3);
    verifyOnlyRoute(queue, 2);
  }

  @Test
  public void unknownQueueOutcomeSurvivesFailedDiagnosticLogging() throws Throwable {
    assertUnknownOutcomeSurvivesFailedDiagnosticLogging(true);
  }

  @Test
  public void unknownQuartzOutcomeSurvivesFailedDiagnosticLogging() throws Throwable {
    assertUnknownOutcomeSurvivesFailedDiagnosticLogging(false);
  }

  private void assertUnknownOutcomeSurvivesFailedDiagnosticLogging(boolean queue) throws Throwable {
    RuntimeException original = new RuntimeException("child submission outcome unknown");
    if (queue) {
      enableQueue();
      when(assetLocalizeAsyncJobSubmissionService.scheduleJob(isA(QuartzJobInfo.class)))
          .thenReturn(pollableFutureMock)
          .thenThrow(original);
    } else {
      when(quartzPollableTaskSchedulerMock.scheduleJob(isA(QuartzJobInfo.class)))
          .thenReturn(pollableFutureMock)
          .thenThrow(original);
    }
    collideDiagnostics(queue ? "assetlocalize" : "quartz", "failed");

    int warnings =
        withFailedDiagnosticLogging(
            () ->
                assertThatThrownBy(
                        () -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
                    .isSameAs(original));

    assertThat(warnings).isEqualTo(2);
    assertThat(multiLocalizedAssetBody.getGenerateLocalizedAssetJobIds())
        .containsEntry("fr-FR", 1L)
        .hasSize(1);
    verifyOnlyRoute(queue, 2);
  }

  private void collideDiagnostics(String route, String result) {
    meterRegistry.gauge(
        "GenerateMultiLocalizedAssetJob.schedule", Tags.of("route", route, "result", result), 1);
    meterRegistry.gauge(
        "GenerateMultiLocalizedAssetJob.call", Tags.of("repositoryName", "testRepo"), 1);
  }

  private void verifyOnlyRoute(boolean queue, int count) {
    if (queue) {
      verify(assetLocalizeAsyncJobSubmissionService, times(count))
          .scheduleJob(isA(QuartzJobInfo.class));
      verifyNoInteractions(quartzPollableTaskSchedulerMock);
    } else {
      verify(quartzPollableTaskSchedulerMock, times(count)).scheduleJob(isA(QuartzJobInfo.class));
      verifyNoInteractions(assetLocalizeAsyncJobSubmissionService);
    }
  }

  private int withFailedDiagnosticLogging(ThrowingCallable action) throws Throwable {
    return withFailedDiagnosticLogging(
        new IllegalStateException("diagnostic appender unavailable"), action);
  }

  @Test
  public void fatalMetricSubclassStillEscapesAfterChildAcceptance() {
    ThreadDeath fatal = new DiagnosticThreadDeath();
    MeterRegistry broken = mock(MeterRegistry.class);
    when(broken.counter(eq("GenerateMultiLocalizedAssetJob.schedule"), any(Tags.class)))
        .thenThrow(fatal);
    generateMultiLocalizedAssetJob.meterRegistry = broken;
    QuartzJobInfo<LocalizedAssetBody, LocalizedAssetBody> input =
        QuartzJobInfo.newBuilder(GenerateLocalizedAssetJob.class)
            .withInput(new LocalizedAssetBody())
            .build();

    assertThatThrownBy(() -> generateMultiLocalizedAssetJob.scheduleLocalizedAssetJob(input))
        .isSameAs(fatal);
    verifyOnlyRoute(false, 1);
  }

  @Test
  public void fatalDiagnosticLoggingSubclassStillEscapesAfterChildAcceptance() throws Throwable {
    ThreadDeath fatal = new DiagnosticThreadDeath();
    collideDiagnostics("quartz", "succeeded");

    int warnings =
        withFailedDiagnosticLogging(
            fatal,
            () ->
                assertThatThrownBy(
                        () -> generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody))
                    .isSameAs(fatal));

    assertThat(warnings).isEqualTo(2);
    verifyOnlyRoute(false, 1);
  }

  private static final class DiagnosticThreadDeath extends ThreadDeath {}

  private int withFailedDiagnosticLogging(Throwable failure, ThrowingCallable action)
      throws Throwable {
    Logger logger = (Logger) LoggerFactory.getLogger(GenerateMultiLocalizedAssetJob.class);
    Level previousLevel = logger.getLevel();
    Thread caller = Thread.currentThread();
    List<ILoggingEvent> warnings = new ArrayList<>();
    @SuppressWarnings("unchecked")
    Appender<ILoggingEvent> appender = mock(Appender.class);
    doAnswer(
            invocation -> {
              ILoggingEvent event = invocation.getArgument(0);
              if (Thread.currentThread() == caller
                  && event.getLevel() == Level.WARN
                  && event
                      .getMessage()
                      .equals("Failed to record multi-locale asset scheduling metric")) {
                warnings.add(event);
                throw failure;
              }
              return null;
            })
        .when(appender)
        .doAppend(any(ILoggingEvent.class));
    try {
      logger.setLevel(Level.WARN);
      logger.addAppender(appender);
      action.call();
      return warnings.size();
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
    }
  }

  @Test
  public void partialFanOutFailureSurvivesSchedulingAndTimerMetricFailures() {
    enableQueue();
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
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeFanoutEnabled = true;
    generateMultiLocalizedAssetJob.asyncJobQueueAssetLocalizeProducerEnabled = true;

    generateMultiLocalizedAssetJob.call(multiLocalizedAssetBody);

    verify(quartzPollableTaskSchedulerMock, times(2)).scheduleJob(isA(QuartzJobInfo.class));
    verify(assetLocalizeAsyncJobSubmissionService, times(0)).scheduleJob(isA(QuartzJobInfo.class));
    assertThat(scheduleCount("quartz", "succeeded")).isEqualTo(2);
  }

  @Test
  public void testProducerRollbackRoutesChildrenToQuartzWithChildSchedulerOverride()
      throws Exception {
    enableQueue();
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
    enableQueue();
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
    enableQueue();
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
    enableQueue();
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
    enableQueue();
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
