package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryTermIndexLinkRepository
    extends JpaRepository<GlossaryTermIndexLink, Long> {

  List<GlossaryTermIndexLink> findByGlossaryTermMetadataId(Long glossaryTermMetadataId);

  List<GlossaryTermIndexLink> findByTermIndexEntryId(Long termIndexEntryId);
}
