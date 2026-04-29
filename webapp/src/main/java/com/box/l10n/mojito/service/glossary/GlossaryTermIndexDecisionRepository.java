package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexDecision;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryTermIndexDecisionRepository
    extends JpaRepository<GlossaryTermIndexDecision, Long> {

  Optional<GlossaryTermIndexDecision> findByGlossaryIdAndTermIndexCandidateId(
      Long glossaryId, Long termIndexCandidateId);

  List<GlossaryTermIndexDecision> findByGlossaryIdAndTermIndexCandidateIdIn(
      Long glossaryId, Collection<Long> termIndexCandidateIds);
}
