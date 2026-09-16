package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.service.assetExtraction.AssetExtractionRepository;
import com.box.l10n.mojito.service.drop.DropRepository;
import com.box.l10n.mojito.service.glossary.TermIndexAutomationRunRepository;
import com.box.l10n.mojito.service.glossary.TermIndexRefreshRunRepository;
import com.box.l10n.mojito.service.oaireview.AiReviewRequestUsageRepository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateRunRepository;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptRepository;
import com.box.l10n.mojito.service.tm.TMXliffRepository;
import com.box.l10n.mojito.service.tm.importer.BulkImportRunRepository;
import org.springframework.stereotype.Service;

/** Checks incoming task relationships through indexed foreign-key equality lookups. */
@Service
public class PollableTaskArchiveReferenceService {

  private final PollableTaskRepository pollableTaskRepository;

  private final AssetExtractionRepository assetExtractionRepository;

  private final DropRepository dropRepository;

  private final TMXliffRepository tmXliffRepository;

  private final AiTranslateRunRepository aiTranslateRunRepository;

  private final AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository;

  private final TermIndexRefreshRunRepository termIndexRefreshRunRepository;

  private final TermIndexAutomationRunRepository termIndexAutomationRunRepository;

  private final BulkImportRunRepository bulkImportRunRepository;

  private final AiReviewRequestUsageRepository aiReviewRequestUsageRepository;

  public PollableTaskArchiveReferenceService(
      PollableTaskRepository pollableTaskRepository,
      AssetExtractionRepository assetExtractionRepository,
      DropRepository dropRepository,
      TMXliffRepository tmXliffRepository,
      AiTranslateRunRepository aiTranslateRunRepository,
      AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository,
      TermIndexRefreshRunRepository termIndexRefreshRunRepository,
      TermIndexAutomationRunRepository termIndexAutomationRunRepository,
      BulkImportRunRepository bulkImportRunRepository,
      AiReviewRequestUsageRepository aiReviewRequestUsageRepository) {
    this.pollableTaskRepository = pollableTaskRepository;
    this.assetExtractionRepository = assetExtractionRepository;
    this.dropRepository = dropRepository;
    this.tmXliffRepository = tmXliffRepository;
    this.aiTranslateRunRepository = aiTranslateRunRepository;
    this.aiTranslateTextUnitAttemptRepository = aiTranslateTextUnitAttemptRepository;
    this.termIndexRefreshRunRepository = termIndexRefreshRunRepository;
    this.termIndexAutomationRunRepository = termIndexAutomationRunRepository;
    this.bulkImportRunRepository = bulkImportRunRepository;
    this.aiReviewRequestUsageRepository = aiReviewRequestUsageRepository;
  }

  public boolean hasIncomingReference(long taskId) {
    return pollableTaskRepository.existsByParentTask_Id(taskId)
        || assetExtractionRepository.existsByPollableTask_Id(taskId)
        || dropRepository.existsByImportPollableTask_Id(taskId)
        || dropRepository.existsByExportPollableTask_Id(taskId)
        || tmXliffRepository.existsByPollableTask_Id(taskId)
        || aiTranslateRunRepository.existsByPollableTask_Id(taskId)
        || aiTranslateTextUnitAttemptRepository.existsByPollableTask_Id(taskId)
        || termIndexRefreshRunRepository.existsByPollableTaskId(taskId)
        || termIndexAutomationRunRepository.existsByPollableTaskId(taskId)
        || bulkImportRunRepository.existsByPollableTask_Id(taskId)
        || aiReviewRequestUsageRepository.existsByPollableTaskId(taskId);
  }
}
