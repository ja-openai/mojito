package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.AssetExtractionByBranch;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

/** Exact extraction membership, with no document payload reads. */
@Repository
public class ContentTranslationCandidateQueries {
  private final EntityManager entityManager;

  public ContentTranslationCandidateQueries(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public record ExtractionSnapshot(Long id, String contentMd5) {}

  public ContentTranslationCandidate.Source findCurrentSource(
      Long repositoryId, Long branchId, Long assetId) {
    var sources =
        entityManager
            .createQuery(
                """
        select new com.box.l10n.mojito.service.content.ContentTranslationCandidate$Source(
          asset.repository.id, branch.id, asset.id, asset.path, extraction.id, extraction.contentMd5)
        from AssetExtractionByBranch mapping
        join mapping.asset asset join mapping.branch branch join mapping.assetExtraction extraction
        where mapping.deleted = false and asset.deleted = false and branch.deleted = false
          and asset.repository.deleted = false
          and asset.repository.id = :repositoryId and branch.repository = asset.repository
          and branch.id = :branchId and asset.id = :assetId and extraction.asset = asset
          and extraction.filterOptionsMd5 is not null and extraction.contentMd5 is not null
          and (lower(asset.path) like '%.mdx' or lower(asset.path) like '%.mf2.json')
        """,
                ContentTranslationCandidate.Source.class)
            .setParameter("repositoryId", repositoryId)
            .setParameter("branchId", branchId)
            .setParameter("assetId", assetId)
            .setMaxResults(2)
            .getResultList();
    return sources.size() == 1 ? sources.getFirst() : null;
  }

  public ExtractionSnapshot lockCurrentExtraction(Long repositoryId, Long branchId, Long assetId) {
    var mappings =
        entityManager
            .createQuery(
                """
        select mapping from AssetExtractionByBranch mapping
        join mapping.asset asset join mapping.branch branch join mapping.assetExtraction extraction
        where mapping.deleted = false and asset.deleted = false and branch.deleted = false
          and asset.repository.id = :repositoryId and branch.repository = asset.repository
          and branch.id = :branchId and asset.id = :assetId and extraction.asset = asset
          and extraction.filterOptionsMd5 is not null and extraction.contentMd5 is not null
        """,
                AssetExtractionByBranch.class)
            .setParameter("repositoryId", repositoryId)
            .setParameter("branchId", branchId)
            .setParameter("assetId", assetId)
            .setLockMode(LockModeType.PESSIMISTIC_READ)
            .setMaxResults(2)
            .getResultList();
    if (mappings.size() != 1) return null;
    var mapping = mappings.getFirst();
    entityManager.refresh(mapping, LockModeType.PESSIMISTIC_READ);
    if (Boolean.TRUE.equals(mapping.getDeleted())) return null;
    // Extraction IDs are reused: lock and refresh the mutable revision, not only its mapping.
    var extraction =
        entityManager.find(
            AssetExtraction.class,
            mapping.getAssetExtraction().getId(),
            LockModeType.PESSIMISTIC_READ);
    if (extraction == null) return null;
    entityManager.refresh(extraction, LockModeType.PESSIMISTIC_READ);
    if (extraction.getFilterOptionsMd5() == null
        || extraction.getContentMd5() == null
        || !assetId.equals(extraction.getAsset().getId())) return null;
    return new ExtractionSnapshot(extraction.getId(), extraction.getContentMd5());
  }

  public boolean containsUnit(Long extractionId, Long assetId, Long unitId) {
    return entityManager
            .createQuery(
                """
        select mapping.id from AssetTextUnitToTMTextUnit mapping
        join mapping.assetExtraction extraction join mapping.tmTextUnit unit
        where extraction.id = :extractionId and extraction.asset.id = :assetId
          and mapping.assetTextUnit.assetExtraction = extraction
          and mapping.assetTextUnit.doNotTranslate = false
          and unit.id = :unitId and unit.asset.id = :assetId
        """,
                Long.class)
            .setParameter("extractionId", extractionId)
            .setParameter("assetId", assetId)
            .setParameter("unitId", unitId)
            .setMaxResults(2)
            .getResultList()
            .size()
        == 1;
  }

  public boolean hasTargetHistory(Long unitId, Long localeId) {
    return !entityManager
        .createQuery(
            """
        select variant.id from TMTextUnitVariant variant
        where variant.tmTextUnit.id = :unitId and variant.locale.id = :localeId
        """,
            Long.class)
        .setParameter("unitId", unitId)
        .setParameter("localeId", localeId)
        .setMaxResults(1)
        .getResultList()
        .isEmpty();
  }
}
