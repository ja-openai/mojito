package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermIndexLink;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.transaction.PlatformTransactionManager;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryTermIndexCurationServiceTest {

  @Mock GlossaryRepository glossaryRepository;
  @Mock com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  @Mock GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  @Mock GlossaryTermIndexLinkRepository glossaryTermIndexLinkRepository;
  @Mock GlossaryTermIndexDecisionRepository glossaryTermIndexDecisionRepository;
  @Mock TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  @Mock TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  @Mock TermIndexCandidateRepository termIndexCandidateRepository;
  @Mock GlossaryTermService glossaryTermService;
  @Mock GlossaryAiExtractionService glossaryAiExtractionService;
  @Mock TextUnitSearcher textUnitSearcher;
  @Mock UserService userService;
  @Mock QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  @Mock PollableTaskService pollableTaskService;
  @Mock PlatformTransactionManager transactionManager;

  GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  @Before
  public void setUp() {
    glossaryTermIndexCurationService =
        new GlossaryTermIndexCurationService(
            glossaryRepository,
            repositoryRepository,
            glossaryTermMetadataRepository,
            glossaryTermIndexLinkRepository,
            glossaryTermIndexDecisionRepository,
            termIndexExtractedTermRepository,
            termIndexOccurrenceRepository,
            termIndexCandidateRepository,
            glossaryTermService,
            glossaryAiExtractionService,
            textUnitSearcher,
            userService,
            ObjectMapper.withNoFailOnUnknownProperties(),
            quartzPollableTaskScheduler,
            pollableTaskService,
            transactionManager);
  }

  @Test
  public void linkGlossaryTermsToCandidatesUsesGlossarySourceLocaleBeforeRoot() {
    Glossary glossary = glossary(1L, "en");
    GlossaryTermMetadata metadata = metadata(2L, glossary, "new_chat", "New chat");
    TermIndexExtractedTerm extractedTerm = extractedTerm(3L, "en", "new chat", "New chat");
    TermIndexCandidate candidate = candidate(4L, extractedTerm);

    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(glossary.getId()))
        .thenReturn(Optional.of(glossary));
    when(glossaryTermMetadataRepository.findByGlossaryId(glossary.getId()))
        .thenReturn(List.of(metadata));
    when(glossaryTermIndexLinkRepository.findByGlossaryTermMetadataIdInAndRelationType(
            List.of(metadata.getId()), GlossaryTermIndexLink.RELATION_TYPE_PRIMARY))
        .thenReturn(List.of());
    when(termIndexCandidateRepository.findBySourceLocaleTagAndNormalizedKeyIn(
            eq("en"), eq(Set.of("new chat"))))
        .thenReturn(List.of(candidate));

    GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesResult result =
        glossaryTermIndexCurationService.linkGlossaryTermsToCandidates(
            glossary.getId(),
            new GlossaryTermIndexCurationService.LinkGlossaryTermsToCandidatesCommand(
                true, null, 100, false, false));

    verify(termIndexCandidateRepository)
        .findBySourceLocaleTagAndNormalizedKeyIn("en", Set.of("new chat"));
    verifyNoMoreInteractions(termIndexCandidateRepository);
    assertThat(result.matchedTermCount()).isEqualTo(1);
    assertThat(result.terms()).hasSize(1);
    assertThat(result.terms().getFirst().action()).isEqualTo("WOULD_LINK");
    assertThat(result.terms().getFirst().termIndexCandidateId()).isEqualTo(candidate.getId());
    assertThat(result.terms().getFirst().termIndexExtractedTermId())
        .isEqualTo(extractedTerm.getId());
  }

  private Glossary glossary(Long id, String sourceLocaleTag) {
    Locale sourceLocale = new Locale();
    sourceLocale.setBcp47Tag(sourceLocaleTag);
    Repository backingRepository = new Repository();
    backingRepository.setId(10L);
    backingRepository.setName("glossary-backing");
    backingRepository.setSourceLocale(sourceLocale);
    Glossary glossary = new Glossary();
    glossary.setId(id);
    glossary.setName("Product UI");
    glossary.setBackingRepository(backingRepository);
    return glossary;
  }

  private GlossaryTermMetadata metadata(Long id, Glossary glossary, String termKey, String source) {
    TMTextUnit textUnit = new TMTextUnit();
    textUnit.setId(20L);
    textUnit.setName(termKey);
    textUnit.setContent(source);
    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setId(id);
    metadata.setGlossary(glossary);
    metadata.setTmTextUnit(textUnit);
    return metadata;
  }

  private TermIndexExtractedTerm extractedTerm(
      Long id, String sourceLocaleTag, String normalizedKey, String displayTerm) {
    TermIndexExtractedTerm extractedTerm = new TermIndexExtractedTerm();
    extractedTerm.setId(id);
    extractedTerm.setSourceLocaleTag(sourceLocaleTag);
    extractedTerm.setNormalizedKey(normalizedKey);
    extractedTerm.setDisplayTerm(displayTerm);
    return extractedTerm;
  }

  private TermIndexCandidate candidate(Long id, TermIndexExtractedTerm extractedTerm) {
    TermIndexCandidate candidate = new TermIndexCandidate();
    candidate.setId(id);
    candidate.setTermIndexExtractedTerm(extractedTerm);
    candidate.setSourceLocaleTag(extractedTerm.getSourceLocaleTag());
    candidate.setNormalizedKey(extractedTerm.getNormalizedKey());
    candidate.setTerm(extractedTerm.getDisplayTerm());
    candidate.setSourceType(TermIndexCandidate.SOURCE_TYPE_EXTRACTION);
    candidate.setSourceName("term-index");
    candidate.setConfidence(80);
    return candidate;
  }
}
