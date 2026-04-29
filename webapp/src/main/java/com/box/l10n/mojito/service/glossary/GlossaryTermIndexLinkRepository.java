package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryTermIndexLinkRepository
    extends JpaRepository<GlossaryTermIndexLink, Long> {

  List<GlossaryTermIndexLink> findByGlossaryTermMetadataId(Long glossaryTermMetadataId);

  List<GlossaryTermIndexLink> findByTermIndexCandidateId(Long termIndexCandidateId);

  Optional<GlossaryTermIndexLink>
      findByGlossaryTermMetadataIdAndTermIndexCandidateIdAndRelationType(
          Long glossaryTermMetadataId, Long termIndexCandidateId, String relationType);

  @Query(
      """
      select link.termIndexCandidate.id
      from GlossaryTermIndexLink link
      where link.glossaryTermMetadata.glossary.id = :glossaryId
        and link.termIndexCandidate.id in :termIndexCandidateIds
      """)
  List<Long> findLinkedTermIndexCandidateIdsByGlossaryId(
      @Param("glossaryId") Long glossaryId,
      @Param("termIndexCandidateIds") Collection<Long> termIndexCandidateIds);
}
