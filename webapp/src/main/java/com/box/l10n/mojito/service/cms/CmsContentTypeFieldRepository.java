package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsContentTypeField;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface CmsContentTypeFieldRepository extends JpaRepository<CmsContentTypeField, Long> {

  Optional<CmsContentTypeField> findByContentTypeIdAndFieldKeyIgnoreCase(
      Long contentTypeId, String fieldKey);

  @EntityGraph(
      attributePaths = {
        "contentType",
        "contentType.project",
        "createdByUser",
        "lastModifiedByUser"
      })
  @Query("select f from CmsContentTypeField f where f.id = :id")
  Optional<CmsContentTypeField> findByIdWithContentType(@Param("id") Long id);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentTypeField> findByContentTypeIdOrderBySortOrderAscFieldKeyAscIdAsc(
      Long contentTypeId);

  @EntityGraph(attributePaths = {"createdByUser", "lastModifiedByUser"})
  List<CmsContentTypeField> findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
      Collection<Long> contentTypeIds);
}
