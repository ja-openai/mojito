package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexEntry;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexEntryRepository extends JpaRepository<TermIndexEntry, Long> {

  Optional<TermIndexEntry> findBySourceLocaleTagAndNormalizedKey(
      String sourceLocaleTag, String normalizedKey);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
          insert ignore into term_index_entry
            (created_date, last_modified_date, source_locale_tag, normalized_key, display_term,
             occurrence_count, repository_count, first_seen_at, last_seen_at)
          values
            (now(), now(), :sourceLocaleTag, :normalizedKey, :displayTerm, 0, 0, now(), now())
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("sourceLocaleTag") String sourceLocaleTag,
      @Param("normalizedKey") String normalizedKey,
      @Param("displayTerm") String displayTerm);
}
