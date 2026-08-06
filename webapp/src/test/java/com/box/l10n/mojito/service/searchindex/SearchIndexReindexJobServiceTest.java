package com.box.l10n.mojito.service.searchindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRepository;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexReindexProgress;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexReindexRequest;
import com.box.l10n.mojito.service.searchindex.SearchIndexService.SearchIndexReindexResult;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class SearchIndexReindexJobServiceTest {

  @Mock private SearchIndexService searchIndexService;
  @Mock private QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  @Mock private PollableTaskRepository pollableTaskRepository;
  @Mock private PollableFuture<SearchIndexReindexResult> pollableFuture;

  private SearchIndexReindexJobService jobService;

  @Before
  public void setUp() {
    jobService =
        new SearchIndexReindexJobService(
            searchIndexService,
            quartzPollableTaskScheduler,
            pollableTaskRepository,
            new ObjectMapper());
  }

  @Test
  public void schedulesDurablePollableQuartzReindexJob() {
    SearchIndexReindexRequest request = new SearchIndexReindexRequest(List.of(17L), 500, 200);
    PollableTask task = new PollableTask();
    task.setId(71L);
    when(pollableTaskRepository.findByNameAndFinishedDateIsNullOrderByCreatedDateDesc(
            SearchIndexReindexJob.class.getCanonicalName()))
        .thenReturn(List.of());
    when(searchIndexService.queuedReindexProgress(request))
        .thenReturn(
            new SearchIndexReindexProgress(
                "QUEUED",
                "tm-text-unit-variants-v1",
                List.of(17L),
                500,
                200,
                0,
                0,
                0,
                0,
                null,
                null));
    when(quartzPollableTaskScheduler.scheduleJob(
            org.mockito.ArgumentMatchers
                .<QuartzJobInfo<SearchIndexReindexRequest, SearchIndexReindexResult>>any()))
        .thenReturn(pollableFuture);
    when(pollableFuture.getPollableTask()).thenReturn(task);

    PollableTask result = jobService.scheduleReindex(request);

    assertThat(result.getId()).isEqualTo(71L);
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<QuartzJobInfo> quartzJobInfoCaptor =
        ArgumentCaptor.forClass(QuartzJobInfo.class);
    verify(quartzPollableTaskScheduler).scheduleJob(quartzJobInfoCaptor.capture());
    QuartzJobInfo<?, ?> quartzJobInfo = quartzJobInfoCaptor.getValue();
    assertThat(quartzJobInfo.getClazz()).isEqualTo(SearchIndexReindexJob.class);
    assertThat(quartzJobInfo.getInput()).isEqualTo(request);
    assertThat(quartzJobInfo.getUniqueId()).isEqualTo("tm-text-unit-variants-v1");
    assertThat(quartzJobInfo.getRequestRecovery()).isTrue();
    assertThat(quartzJobInfo.getMessage()).contains("\"status\":\"QUEUED\"");
  }

  @Test
  public void returnsExistingActiveJobInsteadOfStartingOverlappingWork() {
    PollableTask existingTask = new PollableTask();
    existingTask.setId(84L);
    when(pollableTaskRepository.findByNameAndFinishedDateIsNullOrderByCreatedDateDesc(
            SearchIndexReindexJob.class.getCanonicalName()))
        .thenReturn(List.of(existingTask));

    PollableTask result =
        jobService.scheduleReindex(new SearchIndexReindexRequest(List.of(19L), 100, 50));

    assertThat(result).isSameAs(existingTask);
    verifyNoInteractions(quartzPollableTaskScheduler, pollableFuture);
  }

  @Test
  public void rejectsSchedulingWhenSearchIndexIsDisabled() {
    doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search index is disabled"))
        .when(searchIndexService)
        .requireEnabled();

    assertThatThrownBy(() -> jobService.scheduleReindex(null))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Search index is disabled");
    verifyNoInteractions(quartzPollableTaskScheduler, pollableTaskRepository, pollableFuture);
  }
}
