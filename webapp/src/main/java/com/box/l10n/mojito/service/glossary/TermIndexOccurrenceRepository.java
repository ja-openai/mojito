package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface TermIndexOccurrenceRepository extends JpaRepository<TermIndexOccurrence, Long> {

  List<TermIndexOccurrence> findByTermIndexEntryId(Long termIndexEntryId);

  List<TermIndexOccurrence> findByTmTextUnitIdIn(Collection<Long> tmTextUnitIds);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from TermIndexOccurrence occurrence
      where occurrence.tmTextUnit.id in :tmTextUnitIds
      """)
  int deleteByTmTextUnitIdIn(@Param("tmTextUnitIds") Collection<Long> tmTextUnitIds);
}
