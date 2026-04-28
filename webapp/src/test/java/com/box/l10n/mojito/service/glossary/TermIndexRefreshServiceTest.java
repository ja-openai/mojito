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
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexEntry;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
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
  @Mock TermIndexEntryRepository termIndexEntryRepository;
  @Mock TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  @Mock TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository;
  @Mock TermIndexRefreshRunRepository termIndexRefreshRunRepository;
  @Mock TermIndexRefreshRunEntryRepository termIndexRefreshRunEntryRepository;

  TermIndexRefreshService termIndexRefreshService;
  AtomicReference<TermIndexRefreshRun> refreshRun = new AtomicReference<>();
  AtomicReference<TermIndexRepositoryCursor> cursor = new AtomicReference<>();
  Map<Long, TermIndexEntry> termIndexEntries = new LinkedHashMap<>();
  Set<Long> runEntryIds = new LinkedHashSet<>();

  @Before
  public void setUp() {
    termIndexRefreshService =
        new TermIndexRefreshService(
            repositoryRepository,
            tmTextUnitRepository,
            termIndexEntryRepository,
            termIndexOccurrenceRepository,
            termIndexRepositoryCursorRepository,
            termIndexRefreshRunRepository,
            termIndexRefreshRunEntryRepository,
            transactionTemplate());

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
    when(termIndexEntryRepository.save(any(TermIndexEntry.class)))
        .thenAnswer(
            invocation -> {
              TermIndexEntry entry = invocation.getArgument(0);
              if (entry.getId() == null) {
                entry.setId(200L);
              }
              termIndexEntries.put(entry.getId(), entry);
              return entry;
            });
    when(termIndexEntryRepository.findById(any(Long.class)))
        .thenAnswer(
            invocation -> Optional.ofNullable(termIndexEntries.get(invocation.getArgument(0))));
    when(termIndexEntryRepository.findBySourceLocaleTagAndNormalizedKey(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String sourceLocaleTag = invocation.getArgument(0);
              String normalizedKey = invocation.getArgument(1);
              return termIndexEntries.values().stream()
                  .filter(entry -> sourceLocaleTag.equals(entry.getSourceLocaleTag()))
                  .filter(entry -> normalizedKey.equals(entry.getNormalizedKey()))
                  .findFirst();
            });
    when(termIndexEntryRepository.insertIfAbsent(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String sourceLocaleTag = invocation.getArgument(0);
              String normalizedKey = invocation.getArgument(1);
              boolean exists =
                  termIndexEntries.values().stream()
                      .anyMatch(
                          entry ->
                              sourceLocaleTag.equals(entry.getSourceLocaleTag())
                                  && normalizedKey.equals(entry.getNormalizedKey()));
              if (exists) {
                return 0;
              }

              TermIndexEntry entry = new TermIndexEntry();
              entry.setId(200L + termIndexEntries.size());
              entry.setSourceLocaleTag(sourceLocaleTag);
              entry.setNormalizedKey(normalizedKey);
              entry.setDisplayTerm(invocation.getArgument(2));
              entry.setFirstSeenAt(ZonedDateTime.now());
              entry.setLastSeenAt(entry.getFirstSeenAt());
              termIndexEntries.put(entry.getId(), entry);
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
    when(termIndexRefreshRunEntryRepository.findTermIndexEntryIdsByRefreshRunIdAfter(
            eq(7L), any(Long.class), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              Long afterTermIndexEntryId = invocation.getArgument(1);
              Pageable pageable = invocation.getArgument(2);
              return runEntryIds.stream()
                  .filter(id -> id > afterTermIndexEntryId)
                  .sorted()
                  .limit(pageable.getPageSize())
                  .toList();
            });
  }

  @Test
  public void refreshIndexesLexicalOccurrencesAndUpdatesCursor() {
    Repository repository = repository(5L, "chatgpt-web", "en");
    TMTextUnit textUnit = textUnit(100L, "Sora", repository);

    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(List.of(5L)))
        .thenReturn(List.of(repository));
    when(repositoryRepository.findById(5L)).thenReturn(Optional.of(repository));
    when(tmTextUnitRepository.findUsedTextUnitsForTermIndexRefresh(
            eq(5L), eq(null), eq(0L), any(Pageable.class)))
        .thenReturn(List.of(textUnit));
    when(termIndexOccurrenceRepository.findDistinctTermIndexEntryIdsByTmTextUnitIdIn(List.of(100L)))
        .thenReturn(List.of());
    when(termIndexOccurrenceRepository.countByTermIndexEntry(any(TermIndexEntry.class)))
        .thenReturn(1L);
    when(termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexEntry(
            any(TermIndexEntry.class)))
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
    assertThat(occurrence.getMatchedText()).isEqualTo("Sora");
    assertThat(occurrence.getTermIndexEntry().getNormalizedKey()).isEqualTo("sora");
    assertThat(occurrence.getTermIndexEntry().getSourceLocaleTag()).isEqualTo("en");
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
  }

  @Test
  public void fullRefreshDeletesRepositoryOccurrencesAndRecomputesStaleEntries() {
    Repository repository = repository(5L, "chatgpt-web", "en");
    TermIndexEntry staleEntry = new TermIndexEntry();
    staleEntry.setId(300L);
    staleEntry.setSourceLocaleTag("en");
    staleEntry.setNormalizedKey("old term");
    staleEntry.setDisplayTerm("Old Term");
    termIndexEntries.put(staleEntry.getId(), staleEntry);

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
    when(termIndexOccurrenceRepository.countByTermIndexEntry(staleEntry)).thenReturn(0L);
    when(termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexEntry(staleEntry))
        .thenReturn(0L);

    TermIndexRefreshService.RefreshResult result =
        termIndexRefreshService.refresh(
            new TermIndexRefreshService.RefreshCommand(List.of(5L), true, 100));

    assertThat(result)
        .isEqualTo(
            new TermIndexRefreshService.RefreshResult(
                7L, TermIndexRefreshRun.STATUS_SUCCEEDED, 1, 0, 1, 0));
    verify(termIndexOccurrenceRepository).deleteByRepositoryId(5L);
    verify(termIndexEntryRepository).save(staleEntry);
    assertThat(staleEntry.getOccurrenceCount()).isZero();
    assertThat(staleEntry.getRepositoryCount()).isZero();
  }

  @Test
  public void refreshFailsWhenRepositoryLeaseIsAlreadyHeld() {
    Repository repository = repository(5L, "chatgpt-web", "en");

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
