package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.PlannedIncident;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.PreviewPage;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Request;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Result;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Skipped;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class ManualIncidentReviewServiceTest {
  private final IncidentReviewBatchService batches = mock(IncidentReviewBatchService.class);
  private final ManualIncidentReviewService service = new ManualIncidentReviewService(batches);

  @Test
  public void createsAllPlannedProjectsAcrossPagesAndPublishesOnlyCommittedResults() {
    Request request = request(null);
    when(batches.previewManualPage(request, 7L, 0, null))
        .thenReturn(
            new PreviewPage(
                List.of(incident(1), incident(2)),
                List.of(new Skipped(8L, "handled")),
                4,
                true,
                4,
                9));
    when(batches.previewManualPage(request, 7L, 4, 9L))
        .thenReturn(new PreviewPage(List.of(incident(5)), List.of(), 5, false, 9, 9));
    List<Result> saved = new ArrayList<>();
    when(batches.createManualProject(any(), eq(7L)))
        .thenAnswer(
            invocation -> {
              Request batch = invocation.getArgument(0);
              if (batch.incidentIds().equals(List.of(1L, 2L))) {
                assertThat(saved.getLast().projectIds()).isEmpty();
                return created(2, 10L);
              }
              assertThat(saved.getLast().projectIds()).containsExactly(10L);
              return created(1, 11L);
            });
    List<String> progress = new ArrayList<>();

    Result result = service.create(request, 7L, progress::add, saved::add);

    assertThat(result.projectIds()).containsExactly(10L, 11L);
    assertThat(result.eligibleIncidentCount()).isEqualTo(3);
    assertThat(result.skippedIncidentCount()).isEqualTo(1);
    assertThat(result.scannedIncidentCount()).isEqualTo(9);
    assertThat(result.hasMore()).isFalse();
    assertThat(result.localeTags()).containsExactly("fr");
    assertThat(saved).hasSize(4);
    assertThat(saved.get(2).hasMore()).isTrue();
    assertThat(progress)
        .contains(
            "Preparing incidents: scanned 9; eligible 3",
            "Creating projects: checked 2 of 2; created 2 projects");
    ArgumentCaptor<Request> requests = ArgumentCaptor.forClass(Request.class);
    verify(batches, times(2)).createManualProject(requests.capture(), eq(7L));
    assertThat(requests.getAllValues())
        .extracting(Request::incidentIds)
        .containsExactly(List.of(1L, 2L), List.of(5L));
    assertThat(requests.getAllValues())
        .allSatisfy(
            batch -> {
              assertThat(batch.teamId()).isEqualTo(request.teamId());
              assertThat(batch.maxWordCountPerProject()).isEqualTo(2);
              assertThat(batch.dueDate()).isEqualTo(request.dueDate());
            });
  }

  @Test
  public void laterFailurePreservesPublishedResultsAndStopsFurtherCreation() {
    Request request = request(null);
    when(batches.previewManualPage(request, 7L, 0, null))
        .thenReturn(
            new PreviewPage(
                List.of(incident(1), incident(2), incident(3), incident(4), incident(5)),
                List.of(),
                5,
                false,
                5,
                5));
    when(batches.createManualProject(any(), eq(7L)))
        .thenReturn(created(2, 10L))
        .thenThrow(new IllegalStateException("batch failed"));
    List<Result> saved = new ArrayList<>();

    assertThatThrownBy(() -> service.create(request, 7L, ignored -> {}, saved::add))
        .hasMessage("batch failed");

    assertThat(saved.getLast().projectIds()).containsExactly(10L);
    assertThat(saved.getLast().eligibleIncidentCount()).isEqualTo(2);
    assertThat(saved.getLast().hasMore()).isTrue();
    verify(batches, times(2)).createManualProject(any(), eq(7L));
  }

  @Test
  public void emptySelectionPublishesCompletedEmptyResult() {
    Request request = request(null);
    when(batches.previewManualPage(request, 7L, 0, null))
        .thenReturn(
            new PreviewPage(List.of(), List.of(new Skipped(1L, "handled")), 3, false, 3, 3));
    List<Result> saved = new ArrayList<>();
    Result result = service.create(request, 7L, ignored -> {}, saved::add);
    assertThat(result.projectIds()).isEmpty();
    assertThat(result.eligibleIncidentCount()).isZero();
    assertThat(result.skippedIncidentCount()).isEqualTo(1);
    assertThat(result.scannedIncidentCount()).isEqualTo(3);
    assertThat(result.hasMore()).isFalse();
    assertThat(saved.getLast()).isEqualTo(result);
    verify(batches, never()).createManualProject(any(), any());
  }

  @Test
  public void explicitProjectDoesNotScanOrPlanAgain() {
    Request request = request(List.of(1L));
    when(batches.createManualProject(request, 7L)).thenReturn(created(1, 10L));
    assertThat(service.create(request, 7L).projectIds()).containsExactly(10L);
    verify(batches, never()).previewManualPage(any(), any(), anyLong(), any());
  }

  @Test
  public void unlimitedProjectIncludesMoreThanFiveThousandIncidentsAcrossScanPages() {
    Request request = request(null, null);
    List<PlannedIncident> incidents =
        LongStream.rangeClosed(1, 5001).mapToObj(this::incident).toList();
    for (int offset = 0; offset < incidents.size(); offset += 500) {
      int end = Math.min(offset + 500, incidents.size());
      when(batches.previewManualPage(request, 7L, offset, offset == 0 ? null : 5001L))
          .thenReturn(
              new PreviewPage(
                  incidents.subList(offset, end),
                  List.of(),
                  end - offset,
                  end < incidents.size(),
                  end,
                  5001));
    }
    when(batches.createManualProject(any(), eq(7L))).thenReturn(created(5001, 10L));

    Result result = service.create(request, 7L);

    assertThat(result.eligibleIncidentCount()).isEqualTo(5001);
    assertThat(result.projectCount()).isEqualTo(1);
    assertThat(result.scannedIncidentCount()).isEqualTo(5001);
    ArgumentCaptor<Request> created = ArgumentCaptor.forClass(Request.class);
    verify(batches).createManualProject(created.capture(), eq(7L));
    assertThat(created.getValue().incidentIds())
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 5001).boxed().toList());
    assertThat(created.getValue().maxWordCountPerProject()).isNull();
  }

  static Request request(List<Long> ids) {
    return request(ids, 2);
  }

  static Request request(List<Long> ids, Integer maxWords) {
    return new Request(
        List.of(),
        List.of(),
        List.of("fr"),
        List.of(),
        null,
        3L,
        "Incident review",
        ZonedDateTime.now().plusDays(1).withNano(0),
        maxWords,
        false,
        null,
        null,
        List.of(),
        true,
        null,
        ids);
  }

  private PlannedIncident incident(long id) {
    return new PlannedIncident(id, id, null, 1, 2, "group", "fr", 1);
  }

  private Result created(int count, long id) {
    return new Result(count, 0, 1, List.of("fr"), List.of(id), List.of(id + 10), List.of());
  }
}
