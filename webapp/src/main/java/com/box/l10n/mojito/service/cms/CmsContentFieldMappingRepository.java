package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsContentFieldMapping;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsContentFieldMappingRepository
    extends JpaRepository<CmsContentFieldMapping, Long> {

  @EntityGraph(
      attributePaths = {"variant", "field", "tmTextUnit", "createdByUser", "lastModifiedByUser"})
  Optional<CmsContentFieldMapping> findByVariantIdAndFieldId(Long variantId, Long fieldId);

  @EntityGraph(
      attributePaths = {
        "variant",
        "variant.entry",
        "variant.entry.project",
        "field",
        "tmTextUnit",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select m from CmsContentFieldMapping m where m.id = :id")
  Optional<CmsContentFieldMapping> findByIdWithVariantFieldAndTextUnit(@Param("id") Long id);

  @EntityGraph(
      attributePaths = {
        "variant",
        "variant.entry",
        "field",
        "tmTextUnit",
        "createdByUser",
        "lastModifiedByUser"
      })
  Optional<CmsContentFieldMapping> findByTmTextUnitId(Long tmTextUnitId);

  @EntityGraph(
      attributePaths = {
        "variant",
        "variant.entry",
        "field",
        "tmTextUnit",
        "createdByUser",
        "lastModifiedByUser"
      })
  List<CmsContentFieldMapping> findByFieldId(Long fieldId);

  @Query(
      """
      select m
      from CmsContentFieldMapping m
      join fetch m.variant v
      join fetch v.entry e
      join fetch m.field f
      join fetch m.tmTextUnit tu
      join fetch m.createdByUser createdByUser
      join fetch m.lastModifiedByUser lastModifiedByUser
      where e.project.id = :projectId
      order by e.entryKey asc, v.sortOrder asc, v.variantKey asc, f.sortOrder asc, f.fieldKey asc
      """)
  List<CmsContentFieldMapping> findMappingsByProjectId(@Param("projectId") Long projectId);

  @Query(
      """
      select m
      from CmsContentFieldMapping m
      join fetch m.variant v
      join fetch v.entry e
      join fetch m.field f
      join fetch m.tmTextUnit tu
      join fetch m.createdByUser createdByUser
      join fetch m.lastModifiedByUser lastModifiedByUser
      where e.id = :entryId
      order by v.sortOrder asc, v.variantKey asc, f.sortOrder asc, f.fieldKey asc
      """)
  List<CmsContentFieldMapping> findMappingsByEntryId(@Param("entryId") Long entryId);
}
