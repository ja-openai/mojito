package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.GlossaryTermTranslationProposal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlossaryTermTranslationProposalRepository
    extends JpaRepository<GlossaryTermTranslationProposal, Long> {

  List<GlossaryTermTranslationProposal> findByGlossaryIdOrderByCreatedDateDesc(Long glossaryId);

  List<GlossaryTermTranslationProposal> findByGlossaryIdAndStatusOrderByCreatedDateAsc(
      Long glossaryId, String status);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      delete from GlossaryTermTranslationProposal gttp
      where gttp.glossary.id = :glossaryId
      """)
  int deleteByGlossaryId(@Param("glossaryId") Long glossaryId);
}
