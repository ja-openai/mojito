package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.entity.AiTranslateTextUnitAttempt;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTranslateTextUnitAttemptRepository
    extends JpaRepository<AiTranslateTextUnitAttempt, Long> {

  List<AiTranslateTextUnitAttempt> findByTmTextUnit_IdAndLocale_IdOrderByCreatedDateDesc(
      Long tmTextUnitId, Long localeId);

  Optional<AiTranslateTextUnitAttempt> findByIdAndTmTextUnit_IdAndLocale_Id(
      Long id, Long tmTextUnitId, Long localeId);

  List<AiTranslateTextUnitAttempt> findByPollableTask_IdAndRequestGroupId(
      Long pollableTaskId, String requestGroupId);

  List<AiTranslateTextUnitAttempt> findByPollableTask_IdAndRequestGroupIdIn(
      Long pollableTaskId, Collection<String> requestGroupIds);

  @Query(
      """
      select new com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptLineageRow(
        attempt.id,
        attempt.createdDate,
        attempt.lastModifiedDate,
        tmTextUnit.id,
        tmTextUnit.name,
        tmTextUnitVariant.id,
        locale.bcp47Tag,
        repository.id,
        repository.name,
        pollableTask.id,
        aiTranslateRun.id,
        attempt.requestGroupId,
        attempt.translateType,
        attempt.model,
        attempt.status,
        attempt.completionId,
        attempt.requestPayloadBlobName,
        attempt.responsePayloadBlobName,
        attempt.errorMessage
      )
      from AiTranslateTextUnitAttempt attempt
      join attempt.tmTextUnit tmTextUnit
      join tmTextUnit.asset asset
      join asset.repository repository
      join attempt.locale locale
      join attempt.pollableTask pollableTask
      left join attempt.aiTranslateRun aiTranslateRun
      left join attempt.tmTextUnitVariant tmTextUnitVariant
      order by attempt.createdDate desc, attempt.id desc
      """)
  List<AiTranslateTextUnitAttemptLineageRow> findRecentLineageRows(Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptLineageRow(
        attempt.id,
        attempt.createdDate,
        attempt.lastModifiedDate,
        tmTextUnit.id,
        tmTextUnit.name,
        tmTextUnitVariant.id,
        locale.bcp47Tag,
        repository.id,
        repository.name,
        pollableTask.id,
        aiTranslateRun.id,
        attempt.requestGroupId,
        attempt.translateType,
        attempt.model,
        attempt.status,
        attempt.completionId,
        attempt.requestPayloadBlobName,
        attempt.responsePayloadBlobName,
        attempt.errorMessage
      )
      from AiTranslateTextUnitAttempt attempt
      join attempt.tmTextUnit tmTextUnit
      join tmTextUnit.asset asset
      join asset.repository repository
      join attempt.locale locale
      join attempt.pollableTask pollableTask
      left join attempt.aiTranslateRun aiTranslateRun
      left join attempt.tmTextUnitVariant tmTextUnitVariant
      where repository.id in :repositoryIds
      order by attempt.createdDate desc, attempt.id desc
      """)
  List<AiTranslateTextUnitAttemptLineageRow> findRecentLineageRowsByRepositoryIds(
      @Param("repositoryIds") List<Long> repositoryIds, Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.oaitranslate.AiTranslateTextUnitAttemptLineageRow(
        attempt.id,
        attempt.createdDate,
        attempt.lastModifiedDate,
        tmTextUnit.id,
        tmTextUnit.name,
        tmTextUnitVariant.id,
        locale.bcp47Tag,
        repository.id,
        repository.name,
        pollableTask.id,
        aiTranslateRun.id,
        attempt.requestGroupId,
        attempt.translateType,
        attempt.model,
        attempt.status,
        attempt.completionId,
        attempt.requestPayloadBlobName,
        attempt.responsePayloadBlobName,
        attempt.errorMessage
      )
      from AiTranslateTextUnitAttempt attempt
      join attempt.tmTextUnit tmTextUnit
      join tmTextUnit.asset asset
      join asset.repository repository
      join attempt.locale locale
      join attempt.pollableTask pollableTask
      left join attempt.aiTranslateRun aiTranslateRun
      left join attempt.tmTextUnitVariant tmTextUnitVariant
      where pollableTask.id in :pollableTaskIds
      order by attempt.createdDate desc, attempt.id desc
      """)
  List<AiTranslateTextUnitAttemptLineageRow> findRecentLineageRowsByPollableTaskIds(
      @Param("pollableTaskIds") List<Long> pollableTaskIds, Pageable pageable);
}
