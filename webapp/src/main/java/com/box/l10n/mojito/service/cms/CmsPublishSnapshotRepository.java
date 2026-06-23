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

  @Query(
      """
      select new com.box.l10n.mojito.service.cms.CmsPublishSnapshotHistoryRow(
        s.id,
        p.id,
        p.projectKey,
        s.snapshotVersion,
        s.status,
        s.localeTags,
        s.artifactSha256,
        s.artifactByteSize,
        s.snapshotSigningKeyId,
        s.snapshotSignature,
        s.artifactSignature,
        s.createdByUsername,
        s.publishedAt
      )
      from CmsPublishSnapshot s
      join s.project p
      where p.id = :projectId
      order by s.snapshotVersion desc
      """)
  List<CmsPublishSnapshotHistoryRow> findHistoryRowsByProjectIdOrderBySnapshotVersionDesc(
      @Param("projectId") Long projectId, Pageable pageable);

  @Query(
      """
      select new com.box.l10n.mojito.service.cms.CmsPublishSnapshotHistoryRow(
        s.id,
        p.id,
        p.projectKey,
        s.snapshotVersion,
        s.status,
        s.localeTags,
        s.artifactSha256,
        s.artifactByteSize,
        s.snapshotSigningKeyId,
        s.snapshotSignature,
        s.artifactSignature,
        s.createdByUsername,
        s.publishedAt
      )
      from CmsPublishSnapshot s
      join s.project p
      where p.id = :projectId
        and s.snapshotVersion < :beforeSnapshotVersion
      order by s.snapshotVersion desc
      """)
  List<CmsPublishSnapshotHistoryRow>
      findHistoryRowsByProjectIdBeforeSnapshotVersionOrderBySnapshotVersionDesc(
          @Param("projectId") Long projectId,
          @Param("beforeSnapshotVersion") Integer beforeSnapshotVersion,
          Pageable pageable);

  @EntityGraph(attributePaths = {"project", "createdByUser"})
  Optional<CmsPublishSnapshot> findByProjectIdAndPublishRequestKey(
      Long projectId, String publishRequestKey);

  @EntityGraph(attributePaths = {"project", "createdByUser"})
  Optional<CmsPublishSnapshot> findByProjectIdAndSnapshotVersion(
      Long projectId, Integer snapshotVersion);

  @EntityGraph(attributePaths = {"project", "createdByUser"})
  Optional<CmsPublishSnapshot> findFirstByProjectIdOrderBySnapshotVersionDesc(Long projectId);

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
