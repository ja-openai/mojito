package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

@RepositoryRestResource(exported = false)
public interface GlossaryTermMetadataRepository extends JpaRepository<GlossaryTermMetadata, Long> {

  Optional<GlossaryTermMetadata> findByGlossaryIdAndTmTextUnitId(
      Long glossaryId, Long tmTextUnitId);

  @EntityGraph(attributePaths = "tmTextUnit")
  List<GlossaryTermMetadata> findByGlossaryIdAndTmTextUnitIdIn(
      @Param("glossaryId") Long glossaryId, @Param("tmTextUnitIds") List<Long> tmTextUnitIds);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from GlossaryTermMetadata gtm
      where gtm.glossary.id = :glossaryId
      """)
  int deleteByGlossaryId(@Param("glossaryId") Long glossaryId);
}
