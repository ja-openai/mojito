package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermEvidence;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryTermEvidenceRepository extends JpaRepository<GlossaryTermEvidence, Long> {

  List<GlossaryTermEvidence> findByGlossaryTermMetadataIdInOrderBySortOrderAsc(
      Collection<Long> glossaryTermMetadataIds);

  List<GlossaryTermEvidence> findByGlossaryTermMetadataIdOrderBySortOrderAsc(
      Long glossaryTermMetadataId);

  void deleteByGlossaryTermMetadataId(Long glossaryTermMetadataId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from GlossaryTermEvidence gte
      where gte.glossaryTermMetadata.glossary.id = :glossaryId
      """)
  int deleteByGlossaryId(@Param("glossaryId") Long glossaryId);
}
