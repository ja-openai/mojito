package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.*;
import com.box.l10n.mojito.quartz.*;
import com.box.l10n.mojito.rest.asset.*;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationService;
import com.box.l10n.mojito.service.repository.RepositoryGenerationSettingsRepository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Already-queued Quartz parents must never change admission protocol when JDBC is enabled. */
public class GenerateMultiLocalizedAssetJobTest {
  private final GenerateMultiLocalizedAssetJob job = spy(new GenerateMultiLocalizedAssetJob());
  private final MultiLocalizedAssetBody input = new MultiLocalizedAssetBody();
  private final QuartzPollableTaskScheduler scheduler = mock(QuartzPollableTaskScheduler.class);
  private final Repository repository = new Repository();
  private final GlobalGenerationService global = mock(GlobalGenerationService.class);
  private final RepositoryGenerationSettingsRepository settings =
      mock(RepositoryGenerationSettingsRepository.class);

  @Before
  public void setup() {
    job.assetRepository = mock(AssetRepository.class);
    job.repositoryLocaleRepository = mock(RepositoryLocaleRepository.class);
    job.repositoryGenerationService =
        new RepositoryGenerationService(
            mock(RepositoryRepository.class), mock(LocaleService.class), global, settings);
    job.quartzPollableTaskScheduler = scheduler;
    job.meterRegistry = new SimpleMeterRegistry();
    doReturn(7L).when(job).getParentId();
    repository.setId(1L);
    repository.setName("fixture");
    Asset asset = new Asset();
    asset.setRepository(repository);
    when(job.assetRepository.findById(2L)).thenReturn(Optional.of(asset));
    input.setAssetId(2L);
    input.setSourceContent("source");
    input.setSchedulerName("parentScheduler");
    input.setPullWithNoSource(true);
    input.setPullWithNoSourceBranches(List.of("branch"));
    input.setLocaleInfos(List.of(locale(3L, null), locale(4L, "alias")));
    for (long id : List.of(3L, 4L)) {
      com.box.l10n.mojito.entity.Locale locale = new com.box.l10n.mojito.entity.Locale();
      locale.setBcp47Tag("locale-" + id);
      RepositoryLocale configured = new RepositoryLocale();
      configured.setLocale(locale);
      when(job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(1L, id))
          .thenReturn(configured);
    }
    when(scheduler.scheduleJob(any()))
        .thenAnswer(
            invocation -> {
              PollableTask task = new PollableTask();
              task.setId(
                  ((LocalizedAssetBody)
                          ((QuartzJobInfo<?, ?>) invocation.getArgument(0)).getInput())
                      .getLocaleId());
              return new QuartzPollableFutureTask<>(task, LocalizedAssetBody.class);
            });
  }

  private LocaleInfo locale(long id, String tag) {
    LocaleInfo locale = new LocaleInfo();
    locale.setLocaleId(id);
    locale.setOutputBcp47tag(tag);
    return locale;
  }

  private void excludeLocales(List<String> localeTags) {
    RepositoryGenerationSettings saved = new RepositoryGenerationSettings();
    saved.setRepositoryId(repository.getId());
    saved.setExcludedLocaleTags(localeTags);
    when(settings.findById(repository.getId())).thenReturn(Optional.of(saved));
  }

  @Test
  public void legacyParentKeepsQuartzAndMapsAllChildren() throws Exception {
    assertThat(job.call(input).getGenerateLocalizedAssetJobIds())
        .containsEntry("locale-3", 3L)
        .containsEntry("alias", 4L);
    ArgumentCaptor<QuartzJobInfo> jobs = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(scheduler, times(2)).scheduleJob(jobs.capture());
    for (QuartzJobInfo info : jobs.getAllValues()) {
      assertThat(info.getParentId()).isEqualTo(7L);
      assertThat(info.getScheduler()).isEqualTo("parentScheduler");
      LocalizedAssetBody child = (LocalizedAssetBody) info.getInput();
      assertThat(child.getContent()).isEqualTo("source");
      assertThat(child.isPullWithNoSource()).isTrue();
      assertThat(child.getPullWithNoSourceBranches()).containsExactly("branch");
    }
  }

  @Test
  public void excludedLocaleIsOmittedFromLegacyOutputEvenWithAnExplicitAlias() throws Exception {
    excludeLocales(List.of("locale-4"));

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds())
        .containsOnlyKeys("locale-3")
        .containsEntry("locale-3", 3L);
    ArgumentCaptor<QuartzJobInfo> jobs = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(scheduler).scheduleJob(jobs.capture());
    assertThat(((LocalizedAssetBody) jobs.getValue().getInput()).getLocaleId()).isEqualTo(3L);
    assertThat(input.getLocaleInfos()).hasSize(2);
  }

  @Test
  public void allExcludedLocalesReturnSuccessfulEmptyOutputWithoutSchedulingChildren()
      throws Exception {
    excludeLocales(List.of("locale-3", "locale-4"));
    input.addGenerateLocalizedAddedJobIdToMap("locale-3", 98L);
    input.addGenerateLocalizedAddedJobIdToMap("alias", 99L);

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds()).isEmpty();
    verifyNoInteractions(scheduler);
  }

  @Test
  public void globalAndRepositoryExclusionsCanTogetherExcludeAllChildren() throws Exception {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("locale-4"));
    excludeLocales(List.of("locale-3"));

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds()).isEmpty();
    verify(global).getExcludedLocaleTags();
    verifyNoInteractions(scheduler);
  }

  @Test
  public void globalExclusionsCanOmitAllRequestedLocalesIncludingTheSource() throws Exception {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("locale-3", "locale-4"));
    repository.setSourceLocale(
        job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(1L, 3L).getLocale());

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds()).isEmpty();
    verify(global).getExcludedLocaleTags();
    verifyNoInteractions(scheduler);
  }

  @Test
  public void globalExclusionsApplyAcrossRepositoriesWithoutLocalConfiguration() throws Exception {
    when(global.getExcludedLocaleTags()).thenReturn(List.of("locale-4"));
    assertThat(job.call(input).getGenerateLocalizedAssetJobIds()).containsOnlyKeys("locale-3");
    Repository other = new Repository();
    other.setId(8L);
    other.setName("another-project");
    Asset otherAsset = new Asset();
    otherAsset.setRepository(other);
    when(job.assetRepository.findById(9L)).thenReturn(Optional.of(otherAsset));
    input.setAssetId(9L);
    for (long id : List.of(3L, 4L)) {
      RepositoryLocale configured =
          job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(1L, id);
      when(job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(8L, id))
          .thenReturn(configured);
    }

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds()).containsOnlyKeys("locale-3");
    verify(global, times(2)).getExcludedLocaleTags();
    verify(scheduler, times(2)).scheduleJob(any());
  }

  @Test
  public void prepopulatedOutputDoesNotRetainExcludedAliasesOrUnrequestedChildren()
      throws Exception {
    excludeLocales(List.of("locale-4"));
    input.addGenerateLocalizedAddedJobIdToMap("alias", 99L);
    input.addGenerateLocalizedAddedJobIdToMap("unrequested", 100L);

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds())
        .containsOnlyKeys("locale-3")
        .containsEntry("locale-3", 3L);
    verify(scheduler).scheduleJob(any());
  }

  @Test
  public void exclusionDoesNotAffectAnotherRepository() throws Exception {
    excludeLocales(List.of("locale-3"));
    Repository other = new Repository();
    other.setId(8L);
    other.setName("another-project");
    Asset otherAsset = new Asset();
    otherAsset.setRepository(other);
    when(job.assetRepository.findById(9L)).thenReturn(Optional.of(otherAsset));
    RepositoryLocale configured =
        job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(1L, 3L);
    when(job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(8L, 3L))
        .thenReturn(configured);
    input.setAssetId(9L);
    input.setLocaleInfos(List.of(locale(3L, "locale-3-REGION")));

    assertThat(job.call(input).getGenerateLocalizedAssetJobIds())
        .containsOnlyKeys("locale-3-REGION");
    verify(scheduler).scheduleJob(any());
    verify(settings).findById(8L);
    verify(settings, never()).findById(1L);
  }

  @Test
  public void explicitChildSchedulerStillApplies() throws Exception {
    job.childSchedulerName = "children";
    job.call(input);
    ArgumentCaptor<QuartzJobInfo> jobs = ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(scheduler, times(2)).scheduleJob(jobs.capture());
    assertThat(jobs.getAllValues())
        .allSatisfy(info -> assertThat(info.getScheduler()).isEqualTo("children"));
  }

  @Test
  public void everyTrackedNameStaysQuartz() throws Exception {
    for (String name : List.of("tracked", "", " ")) {
      input.setPullRunName(name);
      job.call(input);
    }
    verify(scheduler, times(6)).scheduleJob(any());
  }

  @Test
  public void legacyLocaleResolutionRemainsLazyOnFailure() {
    when(job.repositoryLocaleRepository.findByRepositoryIdAndLocaleId(1L, 4L))
        .thenThrow(new IllegalStateException("lookup failed"));
    assertThatThrownBy(() -> job.call(input)).hasMessage("lookup failed");
    verify(scheduler).scheduleJob(any());
    assertThat(input.getGenerateLocalizedAssetJobIds()).containsEntry("locale-3", 3L);
  }

  @Test
  public void diagnosticsDoNotDiscardAcknowledgedChildren() throws Exception {
    job.meterRegistry =
        mock(
            MeterRegistry.class,
            invocation -> {
              throw new IllegalStateException("metric failed");
            });
    assertThat(job.call(input).getGenerateLocalizedAssetJobIds()).hasSize(2);
  }

  @Test
  public void diagnosticFailureDoesNotReplaceUncertainQuartzSubmission() {
    RuntimeException failure = new IllegalStateException("unknown admission");
    doThrow(failure).when(scheduler).scheduleJob(any());
    job.meterRegistry =
        mock(
            MeterRegistry.class,
            invocation -> {
              throw new IllegalStateException("metric failed");
            });
    assertThatThrownBy(() -> job.call(input)).isSameAs(failure);
    verify(scheduler).scheduleJob(any());
  }
}
