package com.box.l10n.mojito.service.oaitranslate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.openai.OpenAIClient.CreateBatchResponse;
import com.box.l10n.mojito.openai.OpenAIClient.RetrieveBatchResponse;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateBatchesImportJob.AiTranslateBatchesImportInput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateBatchesImportJob.AiTranslateBatchesImportOutput;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.BatchImportResult;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.BatchRepairCandidate;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiTranslateBatchesImportJobTest {

  private final AiTranslateService service = mock(AiTranslateService.class);
  private final AiTranslateBatchRepairService repairs = mock(AiTranslateBatchRepairService.class);
  private final PollableTask task = task(10);
  private final AiTranslateBatchesImportJob job =
      new AiTranslateBatchesImportJob() {
        @Override
        protected PollableTask getCurrentPollableTask() {
          return task;
        }
      };

  AiTranslateBatchesImportJobTest() {
    task.setParentTask(task(1));
    job.aiTranslateService = service;
    job.batchRepairService = repairs;
  }

  @Test
  @SuppressWarnings("unchecked")
  void schedulesRepairInNextPollAndImportsItsCompletionWithoutRepeatingOriginal() throws Exception {
    CreateBatchResponse original = created("original");
    CreateBatchResponse repair = created("repair");
    RetrieveBatchResponse originalComplete = retrieved("original", "completed");
    RetrieveBatchResponse repairComplete = retrieved("repair", "completed");
    when(service.retrieveBatchWithRetry(original)).thenReturn(originalComplete);
    when(service.retrieveBatchWithRetry(repair)).thenReturn(repairComplete);
    when(service.importBatchForRepair(eq(originalComplete), any(), any(), eq(task)))
        .thenReturn(new BatchImportResult(List.of(), List.of(candidate())));
    when(service.importBatchForRepair(eq(repairComplete), any(), any(), eq(task)))
        .thenReturn(new BatchImportResult(List.of(), List.of()));
    when(repairs.createRepairBatch(originalComplete, List.of(candidate()))).thenReturn(repair);
    PollableFuture<AiTranslateBatchesImportOutput> future = mock(PollableFuture.class);
    when(future.getPollableTask()).thenReturn(task(11));
    when(service.aiTranslateBatchesImportAsync(any(), eq(task.getParentTask()))).thenReturn(future);

    AiTranslateBatchesImportOutput first = job.call(input(original));

    assertThat(first.processed()).containsExactly("original");
    assertThat(first.failedToImport()).isEmpty();
    assertThat(first.nextJob()).isEqualTo(11L);
    ArgumentCaptor<AiTranslateBatchesImportInput> next =
        ArgumentCaptor.forClass(AiTranslateBatchesImportInput.class);
    verify(service).aiTranslateBatchesImportAsync(next.capture(), eq(task.getParentTask()));
    assertThat(next.getValue().createBatchResponses()).containsExactly(original, repair);
    assertThat(next.getValue().attempt()).isEqualTo(1);

    AiTranslateBatchesImportOutput second = job.call(next.getValue());

    assertThat(second.processed()).containsExactlyInAnyOrder("original", "repair");
    assertThat(second.failedToImport()).isEmpty();
    assertThat(second.nextJob()).isNull();
    verify(service, times(1)).importBatchForRepair(eq(originalComplete), any(), any(), eq(task));
    verify(repairs, times(1)).createRepairBatch(any(), any());
    verify(service, times(1)).aiTranslateBatchesImportAsync(any(), any());

    AiTranslateBatchesImportOutput replay =
        job.call(
            new AiTranslateBatchesImportInput(
                List.of(original, repair),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                0,
                "TARGET_ONLY_NEW",
                "REVIEW_NEEDED"));
    assertThat(replay.nextJob()).isNull();
    assertThat(replay.processed()).containsExactlyInAnyOrder("original", "repair");
    verify(service, times(1)).aiTranslateBatchesImportAsync(any(), any());
  }

  @Test
  void failedRepairValidationIsTerminalAndPreservesItsDiagnostic() throws Exception {
    CreateBatchResponse repair = created("repair");
    RetrieveBatchResponse complete = retrieved("repair", "completed");
    when(complete.metadata()).thenReturn(Map.of(AiTranslateBatchRepairService.REPAIR_ATTEMPT, "1"));
    when(service.retrieveBatchWithRetry(repair)).thenReturn(complete);
    when(service.importBatchForRepair(eq(complete), any(), any(), eq(task)))
        .thenReturn(new BatchImportResult(List.of("Text unit 42 still lacks few"), List.of()));

    AiTranslateBatchesImportOutput result = job.call(input(repair));

    assertThat(result.processed()).containsExactly("repair");
    assertThat(result.nextJob()).isNull();
    assertThat(result.failedToImport()).containsEntry("repair", "Text unit 42 still lacks few");
    verifyNoInteractions(repairs);
    verify(service, never()).aiTranslateBatchesImportAsync(any(), any());
  }

  @Test
  void failedRepairSubmissionReportsEveryUnresolvedTextUnit() throws Exception {
    CreateBatchResponse original = created("original");
    RetrieveBatchResponse complete = retrieved("original", "completed");
    when(service.retrieveBatchWithRetry(original)).thenReturn(complete);
    when(service.importBatchForRepair(eq(complete), any(), any(), eq(task)))
        .thenReturn(new BatchImportResult(List.of(), List.of(candidate())));
    when(repairs.createRepairBatch(any(), any()))
        .thenThrow(new IllegalStateException("unavailable"));

    AiTranslateBatchesImportOutput result = job.call(input(original));

    assertThat(result.nextJob()).isNull();
    assertThat(result.failedToImport().get("original"))
        .contains("Could not schedule translation repair for text unit 42", "unavailable");
    assertThat(result.processed()).containsExactly("original");
  }

  @Test
  void terminalProviderFailureIsVisibleInsteadOfSilentlyProcessed() throws Exception {
    CreateBatchResponse repair = created("repair");
    RetrieveBatchResponse expired = retrieved("repair", "expired");
    when(service.retrieveBatchWithRetry(repair)).thenReturn(expired);

    AiTranslateBatchesImportOutput result = job.call(input(repair));

    assertThat(result.failedToImport()).containsEntry("repair", "Batch ended with status: expired");
    assertThat(result.nextJob()).isNull();
    verify(service, never()).importBatchForRepair(any(), any(), any(), any());
    verifyNoInteractions(repairs);
  }

  private static AiTranslateBatchesImportInput input(CreateBatchResponse batch) {
    return new AiTranslateBatchesImportInput(
        List.of(batch),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        0,
        "TARGET_ONLY_NEW",
        "REVIEW_NEEDED");
  }

  private static CreateBatchResponse created(String id) {
    CreateBatchResponse result = mock(CreateBatchResponse.class);
    when(result.id()).thenReturn(id);
    return result;
  }

  private static RetrieveBatchResponse retrieved(String id, String status) {
    RetrieveBatchResponse result = mock(RetrieveBatchResponse.class);
    when(result.id()).thenReturn(id);
    when(result.status()).thenReturn(status);
    return result;
  }

  private static PollableTask task(long id) {
    PollableTask result = new PollableTask();
    result.setId(id);
    return result;
  }

  private static BatchRepairCandidate candidate() {
    return new BatchRepairCandidate(
        42,
        "Incomplete translation",
        List.of(TranslationIntegrityDiagnostic.targetError("plural-category-missing", Map.of())));
  }
}
