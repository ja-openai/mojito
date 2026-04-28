package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexEntryRepository extends JpaRepository<TermIndexEntry, Long> {

  Optional<TermIndexEntry> findBySourceLocaleTagAndNormalizedKey(
      String sourceLocaleTag, String normalizedKey);
}
