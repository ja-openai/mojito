package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsContentEntry;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsContentEntryRepository extends JpaRepository<CmsContentEntry, Long> {

  Optional<CmsContentEntry> findByProjectIdAndEntryKeyIgnoreCase(Long projectId, String entryKey);

  @EntityGraph(
      attributePaths = {
        "project",
        "project.repository",
        "project.asset",
        "contentType",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select e from CmsContentEntry e where e.id = :id")
  Optional<CmsContentEntry> findByIdWithProjectAndType(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "project",
        "project.repository",
        "project.asset",
        "contentType",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select e from CmsContentEntry e where e.id = :id")
  Optional<CmsContentEntry> findByIdWithProjectAndTypeForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "project",
        "project.repository",
        "project.asset",
        "contentType",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select e from CmsContentEntryVariant v join v.entry e where v.id = :variantId")
  Optional<CmsContentEntry> findByVariantIdWithProjectAndTypeForUpdate(
      @Param("variantId") Long variantId);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentEntry> findByProjectIdOrderByNameAscIdAsc(Long projectId);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentEntry> findByContentTypeIdOrderByNameAscIdAsc(Long contentTypeId);
}
