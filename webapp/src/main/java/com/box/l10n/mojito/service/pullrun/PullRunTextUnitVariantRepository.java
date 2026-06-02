package com.box.l10n.mojito.service.pullrun;

import com.box.l10n.mojito.entity.PullRun;
import com.box.l10n.mojito.entity.PullRunAsset;
import com.box.l10n.mojito.entity.PullRunTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author garion
 */
@RepositoryRestResource(exported = false)
public interface PullRunTextUnitVariantRepository
    extends JpaRepository<PullRunTextUnitVariant, Long> {

  List<PullRunTextUnitVariant> findByPullRunAsset(PullRunAsset pullRunAsset, Pageable pageable);

  List<PullRunTextUnitVariant> findByTmTextUnitVariant_TmTextUnitIdAndLocaleId(
      Long tmTextUnitId, Long localeId);

  List<PullRunTextUnitVariant> findByPullRunAssetIdAndLocaleIdAndOutputBcp47Tag(
      Long pullRunAssetId, Long localeId, String outputBcp47Tag);

  @Query(
      """
      select prtuv.tmTextUnitVariant from PullRunTextUnitVariant prtuv
      inner join prtuv.pullRunAsset pra
      inner join pra.pullRun pr where pr = :pullRun
      """)
  List<TMTextUnitVariant> findByPullRun(@Param("pullRun") PullRun pullRun, Pageable pageable);

  @Transactional
  void deleteByPullRunAsset(PullRunAsset pullRunAsset);

  @Query(
      """
      select prtuv.id
      from PullRunTextUnitVariant prtuv
      where prtuv.pullRunAsset.pullRun.createdDate < :beforeDate
      order by prtuv.id
      """)
  List<Long> findIdsByPullRunWithCreatedDateBefore(
      @Param("beforeDate") ZonedDateTime beforeDate, Pageable pageable);
}
