package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTranslateRunRepository extends JpaRepository<AiTranslateRun, Long> {

  Optional<AiTranslateRun> findByPollableTask_Id(Long pollableTaskId);

  @Query(
      """
      select new com.box.l10n.mojito.service.oaitranslate.AiTranslateRunSummaryRow(
        run.id,
        run.triggerSource,
        repository.id,
        repository.name,
        requestedByUser.id,
        pollableTask.id,
        run.model,
        run.translateType,
        run.relatedStringsType,
        run.sourceTextMaxCountPerLocale,
        run.status,
        run.createdDate,
        run.startedAt,
        run.finishedAt,
        run.inputTokens,
        run.cachedInputTokens,
        run.outputTokens,
        run.reasoningTokens,
        run.estimatedCostUsd
      )
      from AiTranslateRun run
      join run.repository repository
      left join run.requestedByUser requestedByUser
      left join run.pollableTask pollableTask
      order by run.createdDate desc, run.id desc
      """)
  List<AiTranslateRunSummaryRow> findRecentRunRows(Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.oaitranslate.AiTranslateRunSummaryRow(
        run.id,
        run.triggerSource,
        repository.id,
        repository.name,
        requestedByUser.id,
        pollableTask.id,
        run.model,
        run.translateType,
        run.relatedStringsType,
        run.sourceTextMaxCountPerLocale,
        run.status,
        run.createdDate,
        run.startedAt,
        run.finishedAt,
        run.inputTokens,
        run.cachedInputTokens,
        run.outputTokens,
        run.reasoningTokens,
        run.estimatedCostUsd
      )
      from AiTranslateRun run
      join run.repository repository
      left join run.requestedByUser requestedByUser
      left join run.pollableTask pollableTask
      where repository.id in :repositoryIds
      order by run.createdDate desc, run.id desc
      """)
  List<AiTranslateRunSummaryRow> findRecentRunRowsByRepositoryIds(
      @Param("repositoryIds") List<Long> repositoryIds, Pageable pageable);
}
