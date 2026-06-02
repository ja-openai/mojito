package com.box.l10n.mojito.service.assetTextUnit;

import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.AssetTextUnit;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author aloison
 */
@RepositoryRestResource(exported = false)
public interface AssetTextUnitRepository
    extends JpaRepository<AssetTextUnit, Long>, JpaSpecificationExecutor<AssetTextUnit> {

  @EntityGraph(value = "AssetTextUnit.legacy", type = EntityGraphType.FETCH)
  List<AssetTextUnit> findByAssetExtraction(AssetExtraction assetExtraction);

  @EntityGraph(value = "AssetTextUnit.legacy", type = EntityGraphType.FETCH)
  List<AssetTextUnit> findByAssetExtractionId(long assetExtractionId);

  @Query(
      """
      select new com.box.l10n.mojito.service.assetTextUnit.AssetTextUnitIdToMd5(atu.id, atu.md5)
      from #{#entityName} atu
      where atu.assetExtraction = ?1
      """)
  List<AssetTextUnitIdToMd5> findMd5ByAssetExtraction(AssetExtraction assetExtraction);

  /**
   * Gets unmapped {@link AssetTextUnit}s
   *
   * @param assetExtractionId {@link AssetExtraction} id
   * @return the unmapped {@link AssetTextUnit}s
   */
  @Query(
      """
      select atu
      from AssetTextUnit atu
      where atu.assetExtraction.id = ?1
      and not exists (
        select map.id
        from AssetTextUnitToTMTextUnit map
        where map.assetTextUnit = atu
      )
      """)
  List<AssetTextUnit> getUnmappedAssetTextUnits(Long assetExtractionId);

  @Transactional
  int deleteByAssetExtractionId(Long assetExtractionId);

  List<AssetTextUnit> findByAssetExtractionIdAndName(Long assetExtractionId, String name);

  @EntityGraph(value = "AssetTextUnit.legacy", type = EntityGraphType.FETCH)
  List<AssetTextUnit> findByIdIn(List<Long> assetTextUnitIds);

  void deleteByIdIn(List<Long> assetTextUnitIds);
}
