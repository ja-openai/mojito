package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsContentProject;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsContentProjectRepository extends JpaRepository<CmsContentProject, Long> {

  Optional<CmsContentProject> findByProjectKeyIgnoreCase(String projectKey);

  Optional<CmsContentProject> findByAssetId(Long assetId);

  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select p from CmsContentProject p where p.id = :id")
  Optional<CmsContentProject> findByIdWithRepositoryAndAsset(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "asset.lastSuccessfulAssetExtraction",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select p from CmsContentProject p where p.projectKey = :projectKey")
  Optional<CmsContentProject> findByProjectKeyWithRepositoryAndAssetForUpdate(
      @Param("projectKey") String projectKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select p from CmsContentProject p where p.id = :id")
  Optional<CmsContentProject> findByIdWithRepositoryAndAssetForUpdate(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select p from CmsContentType t join t.project p where t.id = :contentTypeId")
  Optional<CmsContentProject> findByContentTypeIdWithRepositoryAndAssetForUpdate(
      @Param("contentTypeId") Long contentTypeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query(
      "select p from CmsContentTypeField f join f.contentType t join t.project p where f.id ="
          + " :fieldId")
  Optional<CmsContentProject> findByFieldIdWithRepositoryAndAssetForUpdate(
      @Param("fieldId") Long fieldId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select p from CmsContentEntry e join e.project p where e.id = :entryId")
  Optional<CmsContentProject> findByEntryIdWithRepositoryAndAssetForUpdate(
      @Param("entryId") Long entryId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query(
      "select p from CmsContentEntryVariant v join v.entry e join e.project p where v.id = :variantId")
  Optional<CmsContentProject> findByVariantIdWithRepositoryAndAssetForUpdate(
      @Param("variantId") Long variantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(
      attributePaths = {
        "repository",
        "asset",
        "repository.sourceLocale",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query(
      """
      select p
      from CmsContentFieldMapping m
      join m.variant v
      join v.entry e
      join e.project p
      where m.id = :mappingId
      """)
  Optional<CmsContentProject> findByFieldMappingIdWithRepositoryAndAssetForUpdate(
      @Param("mappingId") Long mappingId);

  @Modifying
  @Query(
      """
      update CmsContentProject p
      set p.lastPublishedSnapshotVersion = :nextSnapshotVersion
      where p.id = :projectId
        and p.lastPublishedSnapshotVersion = :expectedSnapshotVersion
      """)
  int advanceLastPublishedSnapshotVersion(
      @Param("projectId") Long projectId,
      @Param("expectedSnapshotVersion") Integer expectedSnapshotVersion,
      @Param("nextSnapshotVersion") Integer nextSnapshotVersion);

  @Query(
      value =
          """
          select p
          from CmsContentProject p
          join fetch p.repository r
          join fetch p.asset a
          join fetch p.createdByUser createdByUser
          join fetch p.lastModifiedByUser lastModifiedByUser
          where (:enabled is null or p.enabled = :enabled)
            and (:searchQuery is null
              or lower(p.name) like concat('%', lower(:searchQuery), '%')
              or lower(p.projectKey) like concat('%', lower(:searchQuery), '%'))
          order by lower(p.name) asc, p.id asc
          """,
      countQuery =
          """
          select count(p.id)
          from CmsContentProject p
          where (:enabled is null or p.enabled = :enabled)
            and (:searchQuery is null
              or lower(p.name) like concat('%', lower(:searchQuery), '%')
              or lower(p.projectKey) like concat('%', lower(:searchQuery), '%'))
          """)
  Page<CmsContentProject> search(
      @Param("searchQuery") String searchQuery,
      @Param("enabled") Boolean enabled,
      Pageable pageable);
}
