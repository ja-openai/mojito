package com.box.l10n.mojito.service.tm.temporarybulkaccept;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Temporary repository used by the admin-only bulk accept cleanup tool.
 *
 * <p>Delete this file when the temporary cleanup UI/API is removed.
 */
@org.springframework.stereotype.Repository
public interface TemporaryBulkTranslationAcceptRepository
    extends Repository<TMTextUnitCurrentVariant, Long> {

  interface CandidateRow {
    Long getTmTextUnitCurrentVariantId();

    Long getRepositoryId();

    String getRepositoryName();
  }

  interface RepositoryCountRow {
    Long getRepositoryId();

    String getRepositoryName();

    Long getMatchedCount();
  }

  Optional<TMTextUnitCurrentVariant> findById(Long id);

  @Query(
      """
      select distinct
        cv.id as tmTextUnitCurrentVariantId,
        repo.id as repositoryId,
        repo.name as repositoryName
      from TMTextUnitCurrentVariant cv
      join cv.asset asset
      join asset.repository repo
      join cv.tmTextUnitVariant variant
      join variant.tmTextUnitVariantComments variantComment
      where repo.id in :repositoryIds
        and variant.status = :status
        and variantComment.type = :commentType
      order by cv.id
      """)
  List<CandidateRow> findPhraseImportedNeedsReviewCandidates(
      @Param("repositoryIds") List<Long> repositoryIds,
      @Param("status") TMTextUnitVariant.Status status,
      @Param("commentType") TMTextUnitVariantComment.Type commentType,
      Pageable pageable);

  @Query(
      """
      select
        repo.id as repositoryId,
        repo.name as repositoryName,
        count(distinct cv.id) as matchedCount
      from TMTextUnitCurrentVariant cv
      join cv.asset asset
      join asset.repository repo
      join cv.tmTextUnitVariant variant
      join variant.tmTextUnitVariantComments variantComment
      where repo.id in :repositoryIds
        and variant.status = :status
        and variantComment.type = :commentType
      group by repo.id, repo.name
      order by repo.name
      """)
  List<RepositoryCountRow> countPhraseImportedNeedsReviewByRepository(
      @Param("repositoryIds") List<Long> repositoryIds,
      @Param("status") TMTextUnitVariant.Status status,
      @Param("commentType") TMTextUnitVariantComment.Type commentType);

  @Query(
      """
      select
        cv.id as tmTextUnitCurrentVariantId,
        repo.id as repositoryId,
        repo.name as repositoryName
      from TMTextUnitCurrentVariant cv
      join cv.asset asset
      join asset.repository repo
      join cv.tmTextUnitVariant variant
      where repo.id in :repositoryIds
        and variant.status = :status
        and variant.createdDate < :createdBefore
      order by cv.id
      """)
  List<CandidateRow> findNeedsReviewOlderThanCandidates(
      @Param("repositoryIds") List<Long> repositoryIds,
      @Param("status") TMTextUnitVariant.Status status,
      @Param("createdBefore") ZonedDateTime createdBefore,
      Pageable pageable);

  @Query(
      """
      select
        repo.id as repositoryId,
        repo.name as repositoryName,
        count(cv.id) as matchedCount
      from TMTextUnitCurrentVariant cv
      join cv.asset asset
      join asset.repository repo
      join cv.tmTextUnitVariant variant
      where repo.id in :repositoryIds
        and variant.status = :status
        and variant.createdDate < :createdBefore
      group by repo.id, repo.name
      order by repo.name
      """)
  List<RepositoryCountRow> countNeedsReviewOlderThanByRepository(
      @Param("repositoryIds") List<Long> repositoryIds,
      @Param("status") TMTextUnitVariant.Status status,
      @Param("createdBefore") ZonedDateTime createdBefore);
}
