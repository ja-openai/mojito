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
      select new com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantDTO(ttucv.tmTextUnit.id, ttucv.tmTextUnitVariant.id)
      from #{#entityName} ttucv
      where ttucv.asset.id = ?1 and ttucv.locale.id = ?2
      """)
  List<TMTextUnitCurrentVariantDTO> findByAsset_idAndLocale_Id(Long assetId, Long localeId);
}
