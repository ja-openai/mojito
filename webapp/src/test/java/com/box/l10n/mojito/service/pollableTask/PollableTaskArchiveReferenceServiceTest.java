package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.assetExtraction.AssetExtractionRepository;
import com.box.l10n.mojito.service.drop.DropRepository;
import com.box.l10n.mojito.service.glossary.TermIndexAutomationRunRepository;
import com.box.l10n.mojito.service.glossary.TermIndexRefreshRunRepository;
import com.box.l10n.mojito.service.oaireview.AiReviewRequestUsageRepository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateRunRepository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptRepository;
import com.box.l10n.mojito.service.tm.TMXliffRepository;
import com.box.l10n.mojito.service.tm.importer.BulkImportRunRepository;
import org.junit.Before;
import org.junit.Test;

public class PollableTaskArchiveReferenceServiceTest {

  private PollableTaskRepository taskRepository;

  private AssetExtractionRepository assetExtractionRepository;

  private DropRepository dropRepository;

  private TMXliffRepository tmXliffRepository;

  private AiTranslateRunRepository aiTranslateRunRepository;

  private AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository;

  private TermIndexRefreshRunRepository refreshRunRepository;

  private TermIndexAutomationRunRepository automationRunRepository;

  private BulkImportRunRepository bulkImportRunRepository;

  private AiReviewRequestUsageRepository usageRepository;

  private PollableTaskArchiveReferenceService referenceService;

  @Before
  public void setUp() {
    taskRepository = mock(PollableTaskRepository.class);
    assetExtractionRepository = mock(AssetExtractionRepository.class);
    dropRepository = mock(DropRepository.class);
    tmXliffRepository = mock(TMXliffRepository.class);
    aiTranslateRunRepository = mock(AiTranslateRunRepository.class);
    aiTranslateTextUnitAttemptRepository = mock(AiTranslateTextUnitAttemptRepository.class);
    refreshRunRepository = mock(TermIndexRefreshRunRepository.class);
    automationRunRepository = mock(TermIndexAutomationRunRepository.class);
    bulkImportRunRepository = mock(BulkImportRunRepository.class);
    usageRepository = mock(AiReviewRequestUsageRepository.class);
    referenceService =
        new PollableTaskArchiveReferenceService(
            taskRepository,
            assetExtractionRepository,
            dropRepository,
            tmXliffRepository,
            aiTranslateRunRepository,
            aiTranslateTextUnitAttemptRepository,
            refreshRunRepository,
            automationRunRepository,
            bulkImportRunRepository,
            usageRepository);
  }

  @Test
  public void detectsEveryIndexedIncomingRelationship() {
    assertThat(referenceService.hasIncomingReference(42L)).isFalse();

    when(taskRepository.existsByParentTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(taskRepository);

    when(assetExtractionRepository.existsByPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(assetExtractionRepository);

    when(dropRepository.existsByImportPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(dropRepository);

    when(dropRepository.existsByExportPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(dropRepository);

    when(tmXliffRepository.existsByPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(tmXliffRepository);

    when(aiTranslateRunRepository.existsByPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(aiTranslateRunRepository);

    when(aiTranslateTextUnitAttemptRepository.existsByPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(aiTranslateTextUnitAttemptRepository);

    when(refreshRunRepository.existsByPollableTaskId(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(refreshRunRepository);

    when(automationRunRepository.existsByPollableTaskId(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(automationRunRepository);

    when(bulkImportRunRepository.existsByPollableTask_Id(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
    reset(bulkImportRunRepository);

    when(usageRepository.existsByPollableTaskId(42L)).thenReturn(true);
    assertThat(referenceService.hasIncomingReference(42L)).isTrue();
  }

  @Test
  public void stopsAfterTheFirstIndexedReference() {
    when(taskRepository.existsByParentTask_Id(42L)).thenReturn(true);

    assertThat(referenceService.hasIncomingReference(42L)).isTrue();

    verifyNoInteractions(
        assetExtractionRepository,
        dropRepository,
        tmXliffRepository,
        aiTranslateRunRepository,
        aiTranslateTextUnitAttemptRepository,
        refreshRunRepository,
        automationRunRepository,
        bulkImportRunRepository,
        usageRepository);
  }
}
