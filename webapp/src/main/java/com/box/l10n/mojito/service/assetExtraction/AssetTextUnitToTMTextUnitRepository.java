package com.box.l10n.mojito.service.assetExtraction;

import com.box.l10n.mojito.entity.AssetTextUnitToTMTextUnit;
import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.TMTextUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author aloison
 */
@RepositoryRestResource(exported = false)
public interface AssetTextUnitToTMTextUnitRepository
    extends JpaRepository<AssetTextUnitToTMTextUnit, Long> {

  @Transactional
  int deleteByAssetExtractionId(Long assetExtractionId);

  void deleteByAssetTextUnitId(Long assetTextUnitId);

  @Modifying
  @Query(
      """
      delete from AssetTextUnitToTMTextUnit map
      where map.assetTextUnit.id in :assetTextUnitIds
      """)
  int deleteByAssetTextUnitIdIn(@Param("assetTextUnitIds") List<Long> assetTextUnitIds);

  @Query(
      """
      select atuttu.tmTextUnit.id from AssetTextUnitToTMTextUnit atuttu
      inner join atuttu.assetExtraction ae
      inner join ae.assetExtractionByBranches aec
      where aec.branch = ?1
      """)
  List<Long> findByBranch(Branch branch);

  @Query(
      """
      select atuttu.tmTextUnit.id from AssetTextUnitToTMTextUnit atuttu
      inner join atuttu.assetExtraction ae
      inner join ae.assetExtractionByBranches aec
      where aec.branch.name = ?1
      """)
  List<Long> findByBranchName(String branchName);

  @Query(
      """
      select atuttu.tmTextUnit.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = ?1
      """)
  Set<Long> findTmTextUnitIdsByAssetExtractionId(Long assetExtractionId);

  @Query(
      """
      select atuttu.tmTextUnit.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = :assetExtractionId
        and atuttu.tmTextUnit.id in :tmTextUnitIds
      """)
  Set<Long> findTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
      @Param("assetExtractionId") Long assetExtractionId,
      @Param("tmTextUnitIds") Collection<Long> tmTextUnitIds);

  @Query(
      """
      select atuttu.tmTextUnit.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = ?1 and atuttu.assetTextUnit.doNotTranslate = true
      """)
  Set<Long> getTmTextUnitIdsOfDoNotTranslateUnitsByAssetExtractionId(Long assetExtractionId);

  @Query(
      """
      select atuttu.tmTextUnit.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = :assetExtractionId
        and atuttu.tmTextUnit.id in :tmTextUnitIds
        and atuttu.assetTextUnit.doNotTranslate = true
      """)
  Set<Long> findDoNotTranslateTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
      @Param("assetExtractionId") Long assetExtractionId,
      @Param("tmTextUnitIds") Collection<Long> tmTextUnitIds);

  @Query(
      """
      select atuttu.tmTextUnit.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = ?1 and atuttu.assetTextUnit.id = ?2
      """)
  Optional<Long> findTmTextUnitId(long assetExtractionId, long assetTextUnitId);

  @Query(
      """
      select atuttu.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = :assetExtractionId
        and atuttu.tmTextUnit.id = :tmTextUnitId
      """)
  Optional<Long> findIdByAssetExtractionIdAndTmTextUnitId(
      @Param("assetExtractionId") long assetExtractionId, @Param("tmTextUnitId") long tmTextUnitId);

  @Query(
      """
      select atuttu.id
      from AssetTextUnitToTMTextUnit atuttu
      where atuttu.assetExtraction.id = :assetExtractionId
        and atuttu.tmTextUnit.id = :tmTextUnitId
        and atuttu.assetTextUnit.doNotTranslate = true
      """)
  Optional<Long> findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
      @Param("assetExtractionId") long assetExtractionId, @Param("tmTextUnitId") long tmTextUnitId);

  @Query(
      """
      select tmTextUnit
      from AssetTextUnitToTMTextUnit atuttu
      join atuttu.tmTextUnit tmTextUnit
      join fetch tmTextUnit.asset
      where atuttu.assetExtraction.id = :assetExtractionId
        and atuttu.assetTextUnit.name = :stringId
      order by tmTextUnit.id asc
      """)
  List<TMTextUnit> findTmTextUnitsByAssetExtractionIdAndAssetTextUnitName(
      @Param("assetExtractionId") long assetExtractionId, @Param("stringId") String stringId);
}
