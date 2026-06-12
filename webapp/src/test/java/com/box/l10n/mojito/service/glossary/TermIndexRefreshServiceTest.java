package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@RunWith(MockitoJUnitRunner.class)
public class TermIndexRefreshServiceTest {

  @Mock com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  @Mock TMTextUnitRepository tmTextUnitRepository;
  @Mock GlossaryRepository glossaryRepository;
  @Mock GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  @Mock TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  @Mock TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  @Mock TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository;
  @Mock TermIndexRefreshRunRepository termIndexRefreshRunRepository;
  @Mock TermIndexRefreshRunEntryRepository termIndexRefreshRunEntryRepository;
  @Mock QuartzPollableTaskScheduler quartzPollableTaskScheduler;

  TermIndexRefreshService termIndexRefreshService;
  SimpleMeterRegistry meterRegistry;
  AtomicReference<TermIndexRefreshRun> refreshRun = new AtomicReference<>();
  AtomicReference<TermIndexRepositoryCursor> cursor = new AtomicReference<>();
  Map<Long, TermIndexExtractedTerm> termIndexExtractedTerms = new LinkedHashMap<>();
  Set<Long> runEntryIds = new LinkedHashSet<>();

  @Before
  public void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    termIndexRefreshService =
        new TermIndexRefreshService(
            repositoryRepository,
            tmTextUnitRepository,
            glossaryRepository,
            glossaryTermMetadataRepository,
            termIndexExtractedTermRepository,
            termIndexOccurrenceRepository,
            termIndexRepositoryCursorRepository,
            termIndexRefreshRunRepository,
            termIndexRefreshRunEntryRepository,
            transactionTemplate(),
            quartzPollableTaskScheduler,
            new TermIndexJobObservability(meterRegistry));

    when(termIndexRefreshRunRepository.save(any(TermIndexRefreshRun.class)))
        .thenAnswer(
            invocation -> {
              TermIndexRefreshRun run = invocation.getArgument(0);
              if (run.getId() == null) {
                run.setId(7L);
              }
              refreshRun.set(run);
              return run;
            });
    when(termIndexRefreshRunRepository.findById(7L))
        .thenAnswer(invocation -> Optional.ofNullable(refreshRun.get()));
    when(termIndexRepositoryCursorRepository.save(any(TermIndexRepositoryCursor.class)))
        .thenAnswer(
            invocation -> {
              TermIndexRepositoryCursor savedCursor = invocation.getArgument(0);
              cursor.set(savedCursor);
              return savedCursor;
            });
    when(termIndexRepositoryCursorRepository.findByRepositoryId(5L))
        .thenAnswer(invocation -> Optional.ofNullable(cursor.get()));
    when(termIndexRepositoryCursorRepository.acquireLease(
            eq(5L), anyString(), anyString(), any(ZonedDateTime.class), any(), any()))
        .thenAnswer(
            invocation -> {
              TermIndexRepositoryCursor leasedCursor = cursor.get();
              leasedCursor.setStatus(TermIndexRepositoryCursor.STATUS_RUNNING);
              leasedCursor.setLeaseOwner(invocation.getArgument(1));
              leasedCursor.setLeaseToken(invocation.getArgument(2));
              leasedCursor.setLeaseExpiresAt(invocation.getArgument(3));
              leasedCursor.setCurrentRefreshRun(invocation.getArgument(4));
              leasedCursor.setErrorMessage(null);
              return 1;
            });
    when(termIndexRepositoryCursorRepository.resetCheckpointForLease(
            eq(5L), anyString(), any(ZonedDateTime.class)))
        .thenAnswer(
            invocation -> {
              TermIndexRepositoryCursor leasedCursor = cursor.get();
              if (!invocation.getArgument(1).equals(leasedCursor.getLeaseToken())) {
                return 0;
              }
              leasedCursor.setLastProcessedCreatedAt(null);
              leasedCursor.setLastProcessedTmTextUnitId(null);
              leasedCursor.setLeaseExpiresAt(invocation.getArgument(2));
              return 1;
            });
    when(termIndexRepositoryCursorRepository.checkpointLease(
            eq(5L),
            anyString(),
            any(ZonedDateTime.class),
            any(Long.class),
            any(ZonedDateTime.class)))
        .thenAnswer(
            invocation -> {
              TermIndexRepositoryCursor leasedCursor = cursor.get();
              if (!invocation.getArgument(1).equals(leasedCursor.getLeaseToken())) {
                return 0;
              }
              leasedCursor.setLastProcessedCreatedAt(invocation.getArgument(2));
              leasedCursor.setLastProcessedTmTextUnitId(invocation.getArgument(3));
              leasedCursor.setLeaseExpiresAt(invocation.getArgument(4));
              return 1;
            });
    when(termIndexRepositoryCursorRepository.completeLease(
            eq(5L), anyString(), any(ZonedDateTime.class)))
        .thenAnswer(
            invocation -> {
              TermIndexRepositoryCursor leasedCursor = cursor.get();
              if (!invocation.getArgument(1).equals(leasedCursor.getLeaseToken())) {
                return 0;
              }
              leasedCursor.setStatus(TermIndexRepositoryCursor.STATUS_IDLE);
              leasedCursor.setLastSuccessfulScanAt(invocation.getArgument(2));
              leasedCursor.setErrorMessage(null);
              leasedCursor.setLeaseOwner(null);
              leasedCursor.setLeaseToken(null);
              leasedCursor.setLeaseExpiresAt(null);
              leasedCursor.setCurrentRefreshRun(null);
              return 1;
            });
    when(termIndexExtractedTermRepository.save(any(TermIndexExtractedTerm.class)))
        .thenAnswer(
            invocation -> {
              TermIndexExtractedTerm entry = invocation.getArgument(0);
              if (entry.getId() == null) {
                entry.setId(200L);
              }
              termIndexExtractedTerms.put(entry.getId(), entry);
              return entry;
            });
    when(termIndexExtractedTermRepository.findById(any(Long.class)))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(termIndexExtractedTerms.get(invocation.getArgument(0))));
    when(termIndexExtractedTermRepository.findBySourceLocaleTagAndNormalizedKey(
            anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String sourceLocaleTag = invocation.getArgument(0);
              String normalizedKey = invocation.getArgument(1);
              return termIndexExtractedTerms.values().stream()
                  .filter(entry -> sourceLocaleTag.equals(entry.getSourceLocaleTag()))
                  .filter(entry -> normalizedKey.equals(entry.getNormalizedKey()))
                  .findFirst();
            });
    when(termIndexExtractedTermRepository.insertIfAbsent(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String sourceLocaleTag = invocation.getArgument(0);
              String normalizedKey = invocation.getArgument(1);
              boolean exists =
                  termIndexExtractedTerms.values().stream()
                      .anyMatch(
                          entry ->
                              sourceLocaleTag.equals(entry.getSourceLocaleTag())
                                  && normalizedKey.equals(entry.getNormalizedKey()));
              if (exists) {
                return 0;
              }

              TermIndexExtractedTerm entry = new TermIndexExtractedTerm();
              entry.setId(200L + termIndexExtractedTerms.size());
              entry.setSourceLocaleTag(sourceLocaleTag);
              entry.setNormalizedKey(normalizedKey);
              entry.setDisplayTerm(invocation.getArgument(2));
              entry.setFirstSeenAt(ZonedDateTime.now());
              entry.setLastSeenAt(entry.getFirstSeenAt());
              termIndexExtractedTerms.put(entry.getId(), entry);
              return 1;
            });
    when(termIndexOccurrenceRepository.saveAll(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(termIndexRefreshRunEntryRepository.countByRefreshRunId(7L))
        .thenAnswer(invocation -> (long) runEntryIds.size());
    when(termIndexRefreshRunEntryRepository.insertEntries(eq(7L), any(Collection.class)))
        .thenAnswer(
            invocation -> {
              Collection<Long> ids = invocation.getArgument(1);
              int beforeSize = runEntryIds.size();
              runEntryIds.addAll(ids);
              return runEntryIds.size() - beforeSize;
            });
    when(termIndexRefreshRunEntryRepository.findTermIndexExtractedTermIdsByRefreshRunIdAfter(
            eq(7L), any(Long.class), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              Long afterTermIndexExtractedTermId = invocation.getArgument(1);
              Pageable pageable = invocation.getArgument(2);
              return runEntryIds.stream()
                  .filter(id -> id > afterTermIndexExtractedTermId)
                  .sorted()
                  .limit(pageable.getPageSize())
                  .toList();
            });
  }

  @Test
  public void refreshIndexesLexicalOccurrencesAndUpdatesCursor() {
    Repository repository = repository(5L, "product-web", "en");
    TMTextUnit textUnit = textUnit(100L, "Acme", repository);

    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(repository));
    when(repositoryRepository.findById(5L)).thenReturn(Optional.of(repository));
    when(tmTextUnitRepository.findUsedTextUnitsForTermIndexRefresh(
            eq(5L), eq(null), eq(0L), any(Pageable.class)))
        .thenReturn(List.of(textUnit));
    when(termIndexOccurrenceRepository.findDistinctTermIndexExtractedTermIdsByTmTextUnitIdIn(
            List.of(100L)))
        .thenReturn(List.of());
    when(termIndexOccurrenceRepository.countByTermIndexExtractedTerm(
            any(TermIndexExtractedTerm.class)))
        .thenReturn(1L);
    when(termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexExtractedTerm(
            any(TermIndexExtractedTerm.class)))
        .thenReturn(1L);

    TermIndexRefreshService.RefreshResult result =
        termIndexRefreshService.refresh(
            new TermIndexRefreshService.RefreshCommand(List.of(5L), false, 100));

    assertThat(result)
        .isEqualTo(
            new TermIndexRefreshService.RefreshResult(
                7L, TermIndexRefreshRun.STATUS_SUCCEEDED, 1, 1, 1, 1));

    ArgumentCaptor<Iterable<TermIndexOccurrence>> occurrencesCaptor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(termIndexOccurrenceRepository).saveAll(occurrencesCaptor.capture());
    List<TermIndexOccurrence> occurrences =
        StreamSupport.stream(occurrencesCaptor.getValue().spliterator(), false).toList();
    assertThat(occurrences).hasSize(1);
    TermIndexOccurrence occurrence = occurrences.getFirst();
    assertThat(occurrence.getMatchedText()).isEqualTo("Acme");
    assertThat(occurrence.getTermIndexExtractedTerm().getNormalizedKey()).isEqualTo("acme");
    assertThat(occurrence.getTermIndexExtractedTerm().getSourceLocaleTag()).isEqualTo("en");
    assertThat(occurrence.getRepository()).isSameAs(repository);
    assertThat(occurrence.getTmTextUnit()).isSameAs(textUnit);
    assertThat(occurrence.getExtractionMethod())
        .isEqualTo(TermIndexOccurrence.METHOD_LEXICAL_TITLE_CASE);
    assertThat(occurrence.getExtractorId())
        .isEqualTo("LEXICAL:" + TermIndexOccurrence.METHOD_LEXICAL_TITLE_CASE);
    assertThat(occurrence.getStartIndex()).isZero();
    assertThat(occurrence.getEndIndex()).isEqualTo(4);
    assertThat(cursor.get().getStatus()).isEqualTo(TermIndexRepositoryCursor.STATUS_IDLE);
    assertThat(cursor.get().getLeaseToken()).isNull();
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_JOB_EVENTS)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_REFRESH,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_JOB_DURATION)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_REFRESH,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_PHASE_EVENTS)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_REFRESH,
                    "phase",
                    TermIndexJobObservability.PHASE_REFRESH_REPOSITORY,
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
                    TermIndexJobObservability.JOB_REFRESH,
                    "type",
                    TermIndexJobObservability.TYPE_REFRESH_TEXT_UNIT_BATCH,
                    "result",
                    TermIndexJobObservability.RESULT_SUCCEEDED)
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
    assertNoUnboundedEntityIdentifierMetricTags("5", "7", "100", "200");
  }

  @Test
  public void refreshDefaultsMissingRepositorySourceLocaleToEnglish() {
    Repository repository = repositoryWithoutSourceLocale(5L, "product-web");
    TMTextUnit textUnit = textUnit(100L, "Acme", repository);

    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(repository));
    when(repositoryRepository.findById(5L)).thenReturn(Optional.of(repository));
    when(tmTextUnitRepository.findUsedTextUnitsForTermIndexRefresh(
            eq(5L), eq(null), eq(0L), any(Pageable.class)))
        .thenReturn(List.of(textUnit));
    when(termIndexOccurrenceRepository.findDistinctTermIndexExtractedTermIdsByTmTextUnitIdIn(
            List.of(100L)))
        .thenReturn(List.of());
    when(termIndexOccurrenceRepository.countByTermIndexExtractedTerm(
            any(TermIndexExtractedTerm.class)))
        .thenReturn(1L);
    when(termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexExtractedTerm(
            any(TermIndexExtractedTerm.class)))
        .thenReturn(1L);

    termIndexRefreshService.refresh(
        new TermIndexRefreshService.RefreshCommand(List.of(5L), false, 100));

    ArgumentCaptor<Iterable<TermIndexOccurrence>> occurrencesCaptor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(termIndexOccurrenceRepository).saveAll(occurrencesCaptor.capture());
    List<TermIndexOccurrence> occurrences =
        StreamSupport.stream(occurrencesCaptor.getValue().spliterator(), false).toList();
    assertThat(occurrences.getFirst().getTermIndexExtractedTerm().getSourceLocaleTag())
        .isEqualTo(TermIndexExtractedTerm.DEFAULT_SOURCE_LOCALE_TAG);
  }

  @Test
  public void refreshIndexesApprovedGlossaryDictionaryOccurrences() {
    Repository repository = repository(5L, "product-web", "en");
    TMTextUnit textUnit = textUnit(100L, "billing portal dashboard", repository);
    Glossary glossary = glossary(30L, repository);
    GlossaryTermMetadata approvedTerm =
        glossaryTermMetadata(
            40L, glossary, "Billing portal", GlossaryTermMetadata.STATUS_APPROVED, false);
    GlossaryTermMetadata candidateTerm =
        glossaryTermMetadata(
            41L, glossary, "Dashboard", GlossaryTermMetadata.STATUS_CANDIDATE, false);

    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(repository));
    when(repositoryRepository.findById(5L)).thenReturn(Optional.of(repository));
    when(glossaryRepository.findEnabledByRepositoryId(5L)).thenReturn(List.of(glossary));
    when(glossaryTermMetadataRepository.findByGlossaryId(30L))
        .thenReturn(List.of(approvedTerm, candidateTerm));
    when(tmTextUnitRepository.findUsedTextUnitsForTermIndexRefresh(
            eq(5L), eq(null), eq(0L), any(Pageable.class)))
        .thenReturn(List.of(textUnit));
    when(termIndexOccurrenceRepository.findDistinctTermIndexExtractedTermIdsByTmTextUnitIdIn(
            List.of(100L)))
        .thenReturn(List.of());
    when(termIndexOccurrenceRepository.countByTermIndexExtractedTerm(
            any(TermIndexExtractedTerm.class)))
        .thenReturn(1L);
    when(termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexExtractedTerm(
            any(TermIndexExtractedTerm.class)))
        .thenReturn(1L);

    TermIndexRefreshService.RefreshResult result =
        termIndexRefreshService.refresh(
            new TermIndexRefreshService.RefreshCommand(List.of(5L), false, 100));

    assertThat(result)
        .isEqualTo(
            new TermIndexRefreshService.RefreshResult(
                7L, TermIndexRefreshRun.STATUS_SUCCEEDED, 1, 1, 1, 1));

    ArgumentCaptor<Iterable<TermIndexOccurrence>> occurrencesCaptor =
        ArgumentCaptor.forClass(Iterable.class);
    verify(termIndexOccurrenceRepository).saveAll(occurrencesCaptor.capture());
    List<TermIndexOccurrence> occurrences =
        StreamSupport.stream(occurrencesCaptor.getValue().spliterator(), false).toList();
    assertThat(occurrences).hasSize(1);
    TermIndexOccurrence occurrence = occurrences.getFirst();
    assertThat(occurrence.getMatchedText()).isEqualTo("billing portal");
    assertThat(occurrence.getTermIndexExtractedTerm().getDisplayTerm()).isEqualTo("Billing portal");
    assertThat(occurrence.getTermIndexExtractedTerm().getNormalizedKey())
        .isEqualTo("billing portal");
    assertThat(occurrence.getTermIndexExtractedTerm().getSourceLocaleTag()).isEqualTo("en");
    assertThat(occurrence.getExtractionMethod())
        .isEqualTo(TermIndexOccurrence.METHOD_EXTERNAL_GLOSSARY_IMPORT);
    assertThat(occurrence.getExtractorId())
        .isEqualTo(TermIndexRefreshService.GLOSSARY_DICTIONARY_EXTRACTOR_ID);
    assertThat(occurrence.getConfidence()).isEqualTo(100);
  }

  @Test
  public void fullRefreshDeletesRepositoryOccurrencesAndRecomputesStaleEntries() {
    Repository repository = repository(5L, "product-web", "en");
    TermIndexExtractedTerm staleEntry = new TermIndexExtractedTerm();
    staleEntry.setId(300L);
    staleEntry.setSourceLocaleTag("en");
    staleEntry.setNormalizedKey("old term");
    staleEntry.setDisplayTerm("Old Term");
    termIndexExtractedTerms.put(staleEntry.getId(), staleEntry);

    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(repository));
    when(repositoryRepository.findById(5L)).thenReturn(Optional.of(repository));
    when(termIndexRefreshRunEntryRepository.insertExistingRepositoryEntries(7L, 5L))
        .thenAnswer(
            invocation -> {
              runEntryIds.add(staleEntry.getId());
              return 1;
            });
    when(tmTextUnitRepository.findUsedTextUnitsForTermIndexRefresh(
            eq(5L), eq(null), eq(0L), any(Pageable.class)))
        .thenReturn(List.of());
    when(termIndexOccurrenceRepository.countByTermIndexExtractedTerm(staleEntry)).thenReturn(0L);
    when(termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexExtractedTerm(
            staleEntry))
        .thenReturn(0L);

    TermIndexRefreshService.RefreshResult result =
        termIndexRefreshService.refresh(
            new TermIndexRefreshService.RefreshCommand(List.of(5L), true, 100));

    assertThat(result)
        .isEqualTo(
            new TermIndexRefreshService.RefreshResult(
                7L, TermIndexRefreshRun.STATUS_SUCCEEDED, 1, 0, 1, 0));
    verify(termIndexOccurrenceRepository).deleteByRepositoryId(5L);
    verify(termIndexExtractedTermRepository).save(staleEntry);
    assertThat(staleEntry.getOccurrenceCount()).isZero();
    assertThat(staleEntry.getRepositoryCount()).isZero();
  }

  @Test
  public void refreshFailsWhenRepositoryLeaseIsAlreadyHeld() {
    Repository repository = repository(5L, "product-web", "en");

    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(repository));
    when(repositoryRepository.findById(5L)).thenReturn(Optional.of(repository));
    when(termIndexRepositoryCursorRepository.acquireLease(
            eq(5L), anyString(), anyString(), any(ZonedDateTime.class), any(), any()))
        .thenReturn(0);

    assertThatThrownBy(
            () ->
                termIndexRefreshService.refresh(
                    new TermIndexRefreshService.RefreshCommand(List.of(5L), false, 100)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already running");

    assertThat(refreshRun.get().getStatus()).isEqualTo(TermIndexRefreshRun.STATUS_FAILED);
    assertThat(refreshRun.get().getErrorMessage()).contains("already running");
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_JOB_EVENTS)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_REFRESH,
                    "result",
                    TermIndexJobObservability.RESULT_FAILED)
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            meterRegistry
                .get(TermIndexJobObservability.METRIC_JOB_DURATION)
                .tags(
                    "job",
                    TermIndexJobObservability.JOB_REFRESH,
                    "result",
                    TermIndexJobObservability.RESULT_FAILED)
                .timer()
                .count())
        .isEqualTo(1);
    assertNoUnboundedEntityIdentifierMetricTags("5", "7");
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

  private TransactionTemplate transactionTemplate() {
    return new TransactionTemplate(
        new PlatformTransactionManager() {
          @Override
          public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
          }

          @Override
          public void commit(TransactionStatus status) {}

          @Override
          public void rollback(TransactionStatus status) {}
        });
  }

  private Repository repository(Long id, String name, String sourceLocaleTag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(sourceLocaleTag);

    Repository repository = new Repository();
    repository.setId(id);
    repository.setName(name);
    repository.setSourceLocale(locale);
    return repository;
  }

  private Repository repositoryWithoutSourceLocale(Long id, String name) {
    Repository repository = new Repository();
    repository.setId(id);
    repository.setName(name);
    return repository;
  }

  private Glossary glossary(Long id, Repository backingRepository) {
    Glossary glossary = new Glossary();
    glossary.setId(id);
    glossary.setName("Product glossary");
    glossary.setBackingRepository(backingRepository);
    return glossary;
  }

  private GlossaryTermMetadata glossaryTermMetadata(
      Long id, Glossary glossary, String source, String status, boolean caseSensitive) {
    TMTextUnit glossaryTextUnit = new TMTextUnit();
    glossaryTextUnit.setId(1_000L + id);
    glossaryTextUnit.setContent(source);

    GlossaryTermMetadata metadata = new GlossaryTermMetadata();
    metadata.setId(id);
    metadata.setGlossary(glossary);
    metadata.setTmTextUnit(glossaryTextUnit);
    metadata.setStatus(status);
    metadata.setCaseSensitive(caseSensitive);
    return metadata;
  }

  private TMTextUnit textUnit(Long id, String content, Repository repository) {
    Asset asset = new Asset();
    asset.setId(50L);
    asset.setRepository(repository);

    TMTextUnit textUnit = new TMTextUnit();
    textUnit.setId(id);
    textUnit.setContent(content);
    textUnit.setCreatedDate(ZonedDateTime.parse("2026-04-28T10:00:00Z"));
    textUnit.setAsset(asset);
    return textUnit;
  }
}
