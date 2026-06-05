package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsPublishSnapshotSeal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsPublishSnapshotSealRepository
    extends JpaRepository<CmsPublishSnapshotSeal, Long> {

  @Query(
      """
      select count(seal)
      from CmsPublishSnapshotSeal seal
      where seal.snapshot.project.id = :projectId
      """)
  long countBySnapshotProjectId(@Param("projectId") Long projectId);
}
