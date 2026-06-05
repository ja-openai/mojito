package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsContentEntryVariant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsContentEntryVariantRepository
    extends JpaRepository<CmsContentEntryVariant, Long> {

  Optional<CmsContentEntryVariant> findByEntryIdAndVariantKeyIgnoreCase(
      Long entryId, String variantKey);

  @EntityGraph(
      attributePaths = {
        "entry",
        "entry.project",
        "entry.contentType",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select v from CmsContentEntryVariant v where v.id = :id")
  Optional<CmsContentEntryVariant> findByIdWithEntry(@Param("id") Long id);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentEntryVariant> findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(Long entryId);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentEntryVariant> findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
      Collection<Long> entryIds);
}
