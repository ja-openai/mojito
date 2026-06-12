package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
  @Mock TermIndexAutomationRunRepository termIndexAutomationRunRepository;
  @Mock GlossaryTermService glossaryTermService;
  @Mock GlossaryAiExtractionService glossaryAiExtractionService;
  @Mock TextUnitSearcher textUnitSearcher;
  @Mock UserService userService;
  @Mock QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  @Mock PollableTaskService pollableTaskService;
  @Mock PlatformTransactionManager transactionManager;

  GlossaryTermIndexCurationService glossaryTermIndexCurationService;
  SimpleMeterRegistry meterRegistry;

  @Before
  public void setUp() {
    meterRegistry = new SimpleMeterRegistry();
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
            termIndexAutomationRunRepository,
            glossaryTermService,
            glossaryAiExtractionService,
            textUnitSearcher,
            userService,
            ObjectMapper.withNoFailOnUnknownProperties(),
            quartzPollableTaskScheduler,
            pollableTaskService,
            transactionManager,
            new TermIndexJobObservability(meterRegistry));
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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

  @Test
  public void acceptSuggestionDefaultsNullDoNotTranslateFromCandidate() {
    Glossary glossary = glossary(17L, "en");
    TermIndexExtractedTerm extractedTerm = extractedTerm(501L, "en", "api", "API");
    TermIndexCandidate candidate = candidate(502L, extractedTerm);
    candidate.setDoNotTranslate(Boolean.TRUE);
    GlossaryTermMetadata metadata = metadata(1001L, glossary, "api", "API");

    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(glossary.getId()))
        .thenReturn(Optional.of(glossary));
    when(termIndexCandidateRepository.findById(candidate.getId()))
        .thenReturn(Optional.of(candidate));
    when(glossaryTermService.upsertTerm(
            eq(glossary.getId()), isNull(), any(GlossaryTermService.TermUpsertCommand.class)))
        .thenReturn(termView(metadata.getId(), metadata.getTmTextUnit().getId(), "API"));
    when(glossaryTermMetadataRepository.findById(metadata.getId()))
        .thenReturn(Optional.of(metadata));
    when(glossaryTermIndexLinkRepository
            .findByGlossaryTermMetadataIdAndTermIndexCandidateIdAndRelationType(
                metadata.getId(), candidate.getId(), GlossaryTermIndexLink.RELATION_TYPE_PRIMARY))
        .thenReturn(Optional.empty());
    when(glossaryTermIndexDecisionRepository.findByGlossaryIdAndTermIndexCandidateId(
            glossary.getId(), candidate.getId()))
        .thenReturn(Optional.empty());

    glossaryTermIndexCurationService.acceptSuggestion(
        glossary.getId(),
        candidate.getId(),
        new GlossaryTermIndexCurationService.AcceptSuggestionCommand(
            null,
            null,
            null,
            null,
            null,
            null,
            GlossaryTermMetadata.STATUS_CANDIDATE,
            null,
            null,
            null,
            null,
            List.of()));

    ArgumentCaptor<GlossaryTermService.TermUpsertCommand> commandCaptor =
        ArgumentCaptor.forClass(GlossaryTermService.TermUpsertCommand.class);
    verify(glossaryTermService).upsertTerm(eq(glossary.getId()), isNull(), commandCaptor.capture());
    assertThat(commandCaptor.getValue().doNotTranslate()).isTrue();
    assertThat(commandCaptor.getValue().status()).isEqualTo(GlossaryTermMetadata.STATUS_CANDIDATE);
  }

  @Test
  public void acceptSuggestionDefaultsMissingDoNotTranslateToFalse() {
    Glossary glossary = glossary(17L, "en");
    TermIndexExtractedTerm extractedTerm =
        extractedTerm(501L, "en", "billing portal", "Billing portal");
    TermIndexCandidate candidate = candidate(502L, extractedTerm);
    GlossaryTermMetadata metadata = metadata(1001L, glossary, "billing_portal", "Billing portal");

    when(userService.isCurrentUserAdminOrPm()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(glossary.getId()))
        .thenReturn(Optional.of(glossary));
    when(termIndexCandidateRepository.findById(candidate.getId()))
        .thenReturn(Optional.of(candidate));
    when(glossaryTermService.upsertTerm(
            eq(glossary.getId()), isNull(), any(GlossaryTermService.TermUpsertCommand.class)))
        .thenReturn(termView(metadata.getId(), metadata.getTmTextUnit().getId(), "Billing portal"));
    when(glossaryTermMetadataRepository.findById(metadata.getId()))
        .thenReturn(Optional.of(metadata));
    when(glossaryTermIndexLinkRepository
            .findByGlossaryTermMetadataIdAndTermIndexCandidateIdAndRelationType(
                metadata.getId(), candidate.getId(), GlossaryTermIndexLink.RELATION_TYPE_PRIMARY))
        .thenReturn(Optional.empty());
    when(glossaryTermIndexDecisionRepository.findByGlossaryIdAndTermIndexCandidateId(
            glossary.getId(), candidate.getId()))
        .thenReturn(Optional.empty());

    glossaryTermIndexCurationService.acceptSuggestion(
        glossary.getId(),
        candidate.getId(),
        new GlossaryTermIndexCurationService.AcceptSuggestionCommand(
            null,
            null,
            null,
            null,
            null,
            null,
            GlossaryTermMetadata.STATUS_CANDIDATE,
            null,
            null,
            null,
            null,
            List.of()));

    ArgumentCaptor<GlossaryTermService.TermUpsertCommand> commandCaptor =
        ArgumentCaptor.forClass(GlossaryTermService.TermUpsertCommand.class);
    verify(glossaryTermService).upsertTerm(eq(glossary.getId()), isNull(), commandCaptor.capture());
    assertThat(commandCaptor.getValue().doNotTranslate()).isFalse();
  }

  @Test
  public void generateCandidatesFromExtractedTermsCanUseBatchFilters() {
    when(termIndexExtractedTermRepository.searchEntriesForCandidateGeneration(
            eq(false),
            eq(List.of(7L)),
            eq("drive"),
            isNull(),
            eq(TermIndexReview.STATUS_ACCEPTED),
            eq(TermIndexReview.AUTHORITY_FILTER_ALL),
            eq(2L),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(false),
            any(Pageable.class)))
        .thenReturn(List.of());

    GlossaryTermIndexCurationService.GenerateCandidatesResult result =
        glossaryTermIndexCurationService.generateCandidatesFromExtractedTermsInternal(
            new GlossaryTermIndexCurationService.GenerateCandidatesFromExtractedTermsCommand(
                null,
                List.of(7L),
                " drive ",
                null,
                TermIndexReview.STATUS_ACCEPTED,
                TermIndexReview.AUTHORITY_FILTER_ALL,
                2L,
                25,
                null,
                null,
                null,
                null,
                false,
                null));

    assertThat(result.candidateCount()).isZero();
    verify(termIndexExtractedTermRepository)
        .searchEntriesForCandidateGeneration(
            eq(false),
            eq(List.of(7L)),
            eq("drive"),
            isNull(),
            eq(TermIndexReview.STATUS_ACCEPTED),
            eq(TermIndexReview.AUTHORITY_FILTER_ALL),
            eq(2L),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(false),
            any(Pageable.class));
  }

  @Test
  public void generateCandidatesFromExtractedTermsDefaultsToSkippingExistingCandidates() {
    when(termIndexExtractedTermRepository.searchEntriesForCandidateGeneration(
            eq(true),
            any(),
            isNull(),
            isNull(),
            eq(TermIndexReview.STATUS_ACCEPTED),
            eq(TermIndexReview.AUTHORITY_FILTER_ALL),
            eq(1L),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(true),
            any(Pageable.class)))
        .thenReturn(List.of());

    GlossaryTermIndexCurationService.GenerateCandidatesResult result =
        glossaryTermIndexCurationService.generateCandidatesFromExtractedTermsInternal(null);

    assertThat(result.candidateCount()).isZero();
    verify(termIndexExtractedTermRepository)
        .searchEntriesForCandidateGeneration(
            eq(true),
            any(),
            isNull(),
            isNull(),
            eq(TermIndexReview.STATUS_ACCEPTED),
            eq(TermIndexReview.AUTHORITY_FILTER_ALL),
            eq(1L),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            eq(true),
            any(Pageable.class));
  }

  @Test
  public void generateCandidatesFromExtractedTermsSkipsExistingCandidatesMatchedByHash() {
    TermIndexExtractedTermRepository.SearchRow row = searchRow(501L, "en", "api", "API");
    TermIndexCandidate existingCandidate = new TermIndexCandidate();
    existingCandidate.setId(900L);

    when(termIndexExtractedTermRepository.findCandidateGenerationRowsByIdIn(
            eq(List.of(501L)), eq(true), any()))
        .thenReturn(List.of(row));
    when(termIndexCandidateRepository.findBySourceTypeAndSourceNameAndCandidateHash(
            eq(TermIndexCandidate.SOURCE_TYPE_EXTRACTION), eq("term-index"), any()))
        .thenReturn(Optional.of(existingCandidate));

    GlossaryTermIndexCurationService.GenerateCandidatesResult result =
        glossaryTermIndexCurationService.generateCandidatesFromExtractedTermsInternal(
            new GlossaryTermIndexCurationService.GenerateCandidatesFromExtractedTermsCommand(
                List.of(501L),
                null,
                null,
                null,
                TermIndexReview.STATUS_ACCEPTED,
                TermIndexReview.AUTHORITY_FILTER_ALL,
                1L,
                25,
                null,
                null,
                null,
                null,
                true,
                null));

    assertThat(result.candidateCount()).isZero();
    assertThat(result.createdCandidateCount()).isZero();
    assertThat(result.updatedCandidateCount()).isZero();
    assertThat(result.skippedExistingCandidateCount()).isEqualTo(1);
    verify(termIndexCandidateRepository, never()).save(any());
    verify(termIndexExtractedTermRepository, never()).getReferenceById(501L);
  }

  @Test
  public void generateCandidatesFromExtractedTermsRecordsBoundedMetricsForAiBatch() {
    TermIndexExtractedTerm extractedTerm = extractedTerm(501L, "en", "api", "API");
    TermIndexExtractedTermRepository.SearchRow row = searchRow(501L, "en", "api", "API");

    when(termIndexExtractedTermRepository.findCandidateGenerationRowsByIdIn(
            eq(List.of(501L)), eq(true), any()))
        .thenReturn(List.of(row));
    when(termIndexOccurrenceRepository.findDetailsByTermIndexExtractedTermId(
            eq(501L), eq(true), any(), isNull(), any(Pageable.class)))
        .thenReturn(List.of());
    when(glossaryAiExtractionService.enrichCandidates(any()))
        .thenReturn(
            List.of(
                new GlossaryAiExtractionService.AiCandidateView(
                    "501",
                    "API",
                    null,
                    null,
                    90,
                    "Application programming interface",
                    "Common technical acronym.",
                    null,
                    null,
                    null,
                    true)));
    when(termIndexCandidateRepository.findBySourceTypeAndSourceNameAndCandidateHash(
            eq(TermIndexCandidate.SOURCE_TYPE_EXTRACTION), eq("term-index"), any()))
        .thenReturn(Optional.empty());
    when(termIndexExtractedTermRepository.getReferenceById(501L)).thenReturn(extractedTerm);
    when(termIndexCandidateRepository.save(any(TermIndexCandidate.class)))
        .thenAnswer(
            invocation -> {
              TermIndexCandidate candidate = invocation.getArgument(0);
              candidate.setId(900L);
              return candidate;
            });

    GlossaryTermIndexCurationService.GenerateCandidatesResult result =
        glossaryTermIndexCurationService.generateCandidatesFromExtractedTermsInternal(
            new GlossaryTermIndexCurationService.GenerateCandidatesFromExtractedTermsCommand(
                List.of(501L),
                null,
                null,
                null,
                TermIndexReview.STATUS_ACCEPTED,
                TermIndexReview.AUTHORITY_FILTER_ALL,
                1L,
                25,
                null,
                null,
                null,
                null,
                false,
                null));

    assertThat(result.candidateCount()).isEqualTo(1);
    assertThat(result.createdCandidateCount()).isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_JOB_EVENTS)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_CANDIDATE_GENERATION,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_BATCH_EVENTS)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_CANDIDATE_GENERATION,
                    "type",
                    TermIndexJobObservability.TYPE_CANDIDATE_GENERATION_BATCH,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_AI_BATCH_EVENTS)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_CANDIDATE_GENERATION,
                    "type",
                    TermIndexJobObservability.TYPE_AI_CANDIDATE_ENRICHMENT,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_AI_BATCH_DURATION)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_CANDIDATE_GENERATION,
                    "type",
                    TermIndexJobObservability.TYPE_AI_CANDIDATE_ENRICHMENT,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .timer()
                .count())
        .isEqualTo(1);
    assertNoUnboundedEntityIdentifierMetricTags("501", "900");
  }

  private void assertNoUnboundedEntityIdentifierMetricTags(String... forbiddenValues) {
    List<Tag> tags =
        meterRegistry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .toList();
    assertThat(tags)
        .extracting(Tag::getKey)
        .doesNotContain(
            "repositoryId",
            "repositoryIds",
            "glossaryId",
            "textUnitId",
            "tmTextUnitId",
            "candidateId",
            "termIndexCandidateId",
            "extractedTermId",
            "termIndexExtractedTermId");
    assertThat(tags).extracting(Tag::getValue).doesNotContain(forbiddenValues);
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

  private TermIndexExtractedTermRepository.SearchRow searchRow(
      Long id, String sourceLocaleTag, String normalizedKey, String displayTerm) {
    TermIndexExtractedTermRepository.SearchRow row =
        mock(TermIndexExtractedTermRepository.SearchRow.class);
    when(row.getId()).thenReturn(id);
    when(row.getSourceLocaleTag()).thenReturn(sourceLocaleTag);
    when(row.getNormalizedKey()).thenReturn(normalizedKey);
    when(row.getDisplayTerm()).thenReturn(displayTerm);
    when(row.getOccurrenceCount()).thenReturn(10L);
    when(row.getRepositoryCount()).thenReturn(1L);
    return row;
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

  private GlossaryTermService.TermView termView(Long metadataId, Long tmTextUnitId, String source) {
    return new GlossaryTermService.TermView(
        metadataId,
        null,
        null,
        tmTextUnitId,
        source.toLowerCase(),
        source,
        null,
        null,
        null,
        null,
        null,
        GlossaryTermMetadata.STATUS_CANDIDATE,
        null,
        false,
        true,
        null,
        null,
        null,
        null,
        List.of(),
        List.of());
  }
}
