package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsContentType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsContentTypeRepository extends JpaRepository<CmsContentType, Long> {

  Optional<CmsContentType> findByProjectIdAndTypeKeyIgnoreCase(Long projectId, String typeKey);

  @EntityGraph(
      attributePaths = {
        "project",
        "project.repository",
        "project.asset",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select t from CmsContentType t where t.id = :id")
  Optional<CmsContentType> findByIdWithProject(@Param("id") Long id);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentType> findByProjectIdOrderByNameAscIdAsc(Long projectId);
}
