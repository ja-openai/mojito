package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexEntry;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexOccurrenceRepository extends JpaRepository<TermIndexOccurrence, Long> {

  List<TermIndexOccurrence> findByTermIndexEntryId(Long termIndexEntryId);

  @Query(
      """
      select distinct occurrence.extractionMethod
      from TermIndexOccurrence occurrence
      order by occurrence.extractionMethod asc
      """)
  List<String> findDistinctExtractionMethods();

  @Query(
      """
      select occurrence.id as id,
             repository.id as repositoryId,
             repository.name as repositoryName,
             asset.id as assetId,
             asset.path as assetPath,
             textUnit.id as tmTextUnitId,
             textUnit.name as textUnitName,
             textUnit.content as sourceText,
             occurrence.matchedText as matchedText,
             occurrence.startIndex as startIndex,
             occurrence.endIndex as endIndex,
             occurrence.extractorId as extractorId,
             occurrence.extractionMethod as extractionMethod,
             occurrence.confidence as confidence,
             occurrence.createdDate as createdDate
      from TermIndexOccurrence occurrence
      join occurrence.repository repository
      join occurrence.tmTextUnit textUnit
      left join occurrence.asset asset
      where occurrence.termIndexEntry.id = :termIndexEntryId
        and (:repositoryIdsEmpty = true or repository.id in :repositoryIds)
        and (:extractionMethod is null or occurrence.extractionMethod = :extractionMethod)
      order by repository.name asc, occurrence.id desc
      """)
  List<DetailRow> findDetailsByTermIndexEntryId(
      @Param("termIndexEntryId") Long termIndexEntryId,
      @Param("repositoryIdsEmpty") boolean repositoryIdsEmpty,
      @Param("repositoryIds") Collection<Long> repositoryIds,
      @Param("extractionMethod") String extractionMethod,
      Pageable pageable);

  @Query(
      """
      select distinct occurrence.termIndexEntry.id
      from TermIndexOccurrence occurrence
      where occurrence.tmTextUnit.id in :tmTextUnitIds
      """)
  List<Long> findDistinctTermIndexEntryIdsByTmTextUnitIdIn(
      @Param("tmTextUnitIds") Collection<Long> tmTextUnitIds);

  @Query(
      """
      select distinct occurrence.termIndexEntry.id
      from TermIndexOccurrence occurrence
      where occurrence.repository.id = :repositoryId
      """)
  List<Long> findDistinctTermIndexEntryIdsByRepositoryId(@Param("repositoryId") Long repositoryId);

  long countByTermIndexEntry(TermIndexEntry termIndexEntry);

  @Query(
      """
      select count(distinct occurrence.repository.id)
      from TermIndexOccurrence occurrence
      where occurrence.termIndexEntry = :termIndexEntry
      """)
  long countDistinctRepositoriesByTermIndexEntry(
      @Param("termIndexEntry") TermIndexEntry termIndexEntry);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TermIndexOccurrence occurrence
      where occurrence.tmTextUnit.id in :tmTextUnitIds
      """)
  int deleteByTmTextUnitIdIn(@Param("tmTextUnitIds") Collection<Long> tmTextUnitIds);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TermIndexOccurrence occurrence
      where occurrence.repository.id = :repositoryId
      """)
  int deleteByRepositoryId(@Param("repositoryId") Long repositoryId);

  interface DetailRow {
    Long getId();

    Long getRepositoryId();

    String getRepositoryName();

    Long getAssetId();

    String getAssetPath();

    Long getTmTextUnitId();

    String getTextUnitName();

    String getSourceText();

    String getMatchedText();

    Integer getStartIndex();

    Integer getEndIndex();

    String getExtractorId();

    String getExtractionMethod();

    Integer getConfidence();

    ZonedDateTime getCreatedDate();
  }
}
