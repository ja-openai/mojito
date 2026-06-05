package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsPublishSnapshotRepository extends JpaRepository<CmsPublishSnapshot, Long> {

  @Override
  @EntityGraph(attributePaths = {"project", "createdByUser"})
  Optional<CmsPublishSnapshot> findById(Long id);

  @EntityGraph(attributePaths = {"project", "createdByUser"})
  List<CmsPublishSnapshot> findByProjectIdOrderBySnapshotVersionDesc(
      Long projectId, Pageable pageable);

  @EntityGraph(attributePaths = {"project", "createdByUser"})
  Optional<CmsPublishSnapshot> findByProjectIdAndPublishRequestKey(
      Long projectId, String publishRequestKey);

  @Query(
      """
      select s
      from CmsPublishSnapshot s
      join fetch s.project p
      join fetch s.createdByUser createdByUser
      where lower(p.projectKey) = lower(:projectKey)
        and s.snapshotVersion = :snapshotVersion
      """)
  Optional<CmsPublishSnapshot> findByProjectKeyAndSnapshotVersion(
      @Param("projectKey") String projectKey, @Param("snapshotVersion") Integer snapshotVersion);

  @EntityGraph(
      attributePaths = {
        "project",
        "project.repository",
        "project.asset",
        "project.asset.lastSuccessfulAssetExtraction",
        "createdByUser"
      })
  Optional<CmsPublishSnapshot> findFirstByProjectProjectKeyIgnoreCaseOrderBySnapshotVersionDesc(
      String projectKey);

  @Query(
      "select coalesce(max(s.snapshotVersion), 0) from CmsPublishSnapshot s where s.project.id = :projectId")
  Integer findMaxSnapshotVersionByProjectId(@Param("projectId") Long projectId);

  long countByProjectId(Long projectId);

  @Query(
      """
      select new com.box.l10n.mojito.service.cms.CmsCurrentVariantRow(
        tu.id,
        l.bcp47Tag,
        tuv.content,
        tuv.status,
        tuv.includedInLocalizedFile
      )
      from TMTextUnitCurrentVariant currentVariant
      join currentVariant.tmTextUnit tu
      join currentVariant.locale l
      left join currentVariant.tmTextUnitVariant tuv
      where tu.id in :tmTextUnitIds
        and l.bcp47Tag in :localeTags
      """)
  List<CmsCurrentVariantRow> findCurrentVariantRows(
      @Param("tmTextUnitIds") Collection<Long> tmTextUnitIds,
      @Param("localeTags") Collection<String> localeTags);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select currentVariant
      from TMTextUnitCurrentVariant currentVariant
      join currentVariant.tmTextUnit tu
      join currentVariant.locale l
      where tu.id in :tmTextUnitIds
        and l.bcp47Tag in :localeTags
      order by tu.id, l.bcp47Tag
      """)
  List<TMTextUnitCurrentVariant> lockCurrentVariantRows(
      @Param("tmTextUnitIds") Collection<Long> tmTextUnitIds,
      @Param("localeTags") Collection<String> localeTags);
}
