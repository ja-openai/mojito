package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

/**
 * @author jaurambault
 */
@RepositoryRestResource(exported = false)
public interface TMTextUnitCurrentVariantRepository
    extends JpaRepository<TMTextUnitCurrentVariant, Long> {

  @EntityGraph(value = "TMTextUnitCurrentVariant.legacy", type = EntityGraphType.FETCH)
  TMTextUnitCurrentVariant findByLocale_IdAndTmTextUnit_Id(Long localeId, Long tmTextUnitId);

  List<TMTextUnitCurrentVariant> findByTmTextUnit_Id(Long tmTextUnitId);

  List<TMTextUnitCurrentVariant> findByTmTextUnit_Tm_IdAndLocale_Id(Long tmId, Long localeId);

  @Query(
      value =
          """
          select 1
          from tm_text_unit_current_variant
          where tm_id = ?1
            and locale_id in (?2)
            and last_modified_date > ?3
          limit 1
          """,
      nativeQuery = true)
  Optional<Integer> findFirstChangeSince(
      Long tmId, Collection<Long> localeIds, ZonedDateTime lastModifiedDate);

  @Query(
      """
      select new com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantDTO(
        currentVariant.tmTextUnit.id,
        currentVariant.tmTextUnitVariant.id,
        currentVariant.locale.id,
        currentVariant.asset.id,
        currentVariant.lastModifiedDate,
        currentVariant.id
      )
      from #{#entityName} currentVariant
      where currentVariant.asset.id = ?1 and currentVariant.locale.id = ?2
      """)
  List<TMTextUnitCurrentVariantDTO> findByAsset_idAndLocale_Id(Long assetId, Long localeId);

  @Query(
      """
      select new com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantDTO(
        currentVariant.tmTextUnit.id,
        currentVariant.tmTextUnitVariant.id,
        currentVariant.locale.id,
        currentVariant.asset.id,
        currentVariant.lastModifiedDate,
        currentVariant.id
      )
      from #{#entityName} currentVariant
      where currentVariant.tm.id = :tmId
        and currentVariant.locale.id = :localeId
        and currentVariant.asset.id = :assetId
        and currentVariant.lastModifiedDate >= :lastModifiedDate
      order by currentVariant.lastModifiedDate asc, currentVariant.id asc
      """)
  List<TMTextUnitCurrentVariantDTO> findChangesByTmIdAndLocaleIdAndAssetIdSince(
      @Param("tmId") Long tmId,
      @Param("localeId") Long localeId,
      @Param("assetId") Long assetId,
      @Param("lastModifiedDate") ZonedDateTime lastModifiedDate);
}
