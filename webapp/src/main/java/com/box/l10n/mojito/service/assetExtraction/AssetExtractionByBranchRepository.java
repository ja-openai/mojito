package com.box.l10n.mojito.service.assetExtraction;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetExtractionByBranch;
import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * @author jeanaurambault
 */
@RepositoryRestResource(exported = false)
public interface AssetExtractionByBranchRepository
    extends JpaRepository<AssetExtractionByBranch, Long> {

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate(
        asset.id, asset.path, asset.repository.id, branch.id, branch.name, extraction.contentMd5, asset.repository.sourceLocale.bcp47Tag)
      from AssetExtractionByBranch mapping
      join mapping.asset asset
      join mapping.branch branch
      join mapping.assetExtraction extraction
      where mapping.deleted = false and branch.deleted = false and asset.deleted = false
        and extraction.asset = asset and branch.repository = asset.repository
        and extraction.contentMd5 is not null and extraction.filterOptionsMd5 is not null
        and lower(asset.path) like '%.mdx'
        and exists (
          select rptu.id from ReviewProjectTextUnit rptu
          where rptu.reviewProject.id = :projectId and rptu.tmTextUnit.asset = asset)
      order by asset.path, branch.name, mapping.id
      """)
  List<ReviewProjectDocumentTemplate> findDocumentTemplatesByProjectId(
      @Param("projectId") Long projectId, Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate(
        asset.id, asset.path, asset.repository.id, branch.id, branch.name, extraction.contentMd5, asset.repository.sourceLocale.bcp47Tag)
      from AssetExtractionByBranch mapping
      join mapping.asset asset
      join mapping.branch branch
      join mapping.assetExtraction extraction
      where mapping.deleted = false and branch.deleted = false and asset.deleted = false
        and extraction.asset = asset and branch.repository = asset.repository
        and extraction.contentMd5 is not null and extraction.filterOptionsMd5 is not null
        and asset.repository.id = :repositoryId and branch.id = :branchId
        and asset.path = :assetPath
        and (lower(asset.path) like '%.mdx' or lower(asset.path) like '%.mf2.json')
      """)
  Optional<ReviewProjectDocumentTemplate> findDocumentTemplate(
      @Param("repositoryId") Long repositoryId,
      @Param("branchId") Long branchId,
      @Param("assetPath") String assetPath);

  List<AssetExtractionByBranch> findByAssetAndDeletedFalse(Asset asset);

  int countByAssetAndDeletedFalseAndBranchNot(Asset asset, Branch branch);

  @EntityGraph(value = "AssetExtractionByBranch.legacy", type = EntityGraphType.FETCH)
  Optional<AssetExtractionByBranch> findByAssetAndBranch(Asset asset, Branch branch);

  @Modifying
  @Query("update AssetExtractionByBranch aea set aea.deleted = true where aea.asset= ?1")
  int setDeletedTrue(Asset asset);
}
