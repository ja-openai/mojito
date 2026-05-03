package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TermIndexRefreshService {

  static final int DEFAULT_BATCH_SIZE = 1_000;
  static final int MAX_BATCH_SIZE = 10_000;
  static final String EXTRACTOR_ID_PREFIX = "LEXICAL:";

  private static final Duration LEASE_DURATION = Duration.ofMinutes(15);
  private static final int ACQUIRE_LEASE_ATTEMPT_COUNT = 2;
  private static final int ERROR_MESSAGE_MAX_LENGTH = 2048;
  private static final Pattern TITLE_PHRASE_PATTERN =
      Pattern.compile("\\b[A-Z][a-z0-9]+(?:[ -][A-Z][a-z0-9]+){0,3}\\b");
  private static final Pattern UPPER_TOKEN_PATTERN =
      Pattern.compile("\\b[A-Z]{2,}(?:[A-Z0-9_-]{0,30})\\b");
  private static final Pattern CAMEL_TOKEN_PATTERN =
      Pattern.compile("\\b[A-Za-z]+(?:[A-Z][a-z0-9]+)+\\b");
  private static final Set<String> EXTRACTION_STOP_WORDS =
      Set.of(
          "a",
          "an",
          "and",
          "app",
          "button",
          "cancel",
          "click",
          "continue",
          "create",
          "delete",
          "edit",
          "for",
          "from",
          "go",
          "help",
          "in",
          "learn",
          "more",
          "new",
          "of",
          "on",
          "open",
          "please",
          "remove",
          "review",
          "save",
          "select",
          "settings",
          "start",
          "the",
          "this",
          "to",
          "update",
          "view",
          "with",
          "your");

  private final com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  private final TMTextUnitRepository tmTextUnitRepository;
  private final TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  private final TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository;
  private final TermIndexRefreshRunRepository termIndexRefreshRunRepository;
  private final TermIndexRefreshRunEntryRepository termIndexRefreshRunEntryRepository;
  private final TransactionTemplate transactionTemplate;
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler;

  public TermIndexRefreshService(
      com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository,
      TMTextUnitRepository tmTextUnitRepository,
      TermIndexExtractedTermRepository termIndexExtractedTermRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository,
      TermIndexRefreshRunRepository termIndexRefreshRunRepository,
      TermIndexRefreshRunEntryRepository termIndexRefreshRunEntryRepository,
      TransactionTemplate transactionTemplate,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler) {
    this.repositoryRepository = Objects.requireNonNull(repositoryRepository);
    this.tmTextUnitRepository = Objects.requireNonNull(tmTextUnitRepository);
    this.termIndexExtractedTermRepository =
        Objects.requireNonNull(termIndexExtractedTermRepository);
    this.termIndexOccurrenceRepository = Objects.requireNonNull(termIndexOccurrenceRepository);
    this.termIndexRepositoryCursorRepository =
        Objects.requireNonNull(termIndexRepositoryCursorRepository);
    this.termIndexRefreshRunRepository = Objects.requireNonNull(termIndexRefreshRunRepository);
    this.termIndexRefreshRunEntryRepository =
        Objects.requireNonNull(termIndexRefreshRunEntryRepository);
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
    this.quartzPollableTaskScheduler = Objects.requireNonNull(quartzPollableTaskScheduler);
  }

  public PollableFuture<RefreshResult> scheduleRefresh(RefreshCommand command) {
    RefreshCommand validatedCommand = validate(command);
    QuartzJobInfo<RefreshCommand, RefreshResult> quartzJobInfo =
        QuartzJobInfo.newBuilder(TermIndexRefreshJob.class)
            .withInput(validatedCommand)
            .withMessage("Refresh raw term index")
            .withRequestRecovery(true)
            .build();
    return quartzPollableTaskScheduler.scheduleJob(quartzJobInfo);
  }

  public RefreshResult refresh(RefreshCommand command) {
    return refresh(command, null);
  }

  public RefreshResult refresh(RefreshCommand command, Long pollableTaskId) {
    RefreshCommand validatedCommand = validate(command);
    List<Repository> repositories = resolveRepositories(validatedCommand.repositoryIds());
    List<Long> repositoryIds = repositories.stream().map(Repository::getId).toList();
    int batchSize = normalizeBatchSize(validatedCommand.batchSize());
    boolean fullRefresh = Boolean.TRUE.equals(validatedCommand.fullRefresh());

    TermIndexRefreshRun refreshRun = createRefreshRun(repositories, pollableTaskId);
    long processedTextUnitCount = 0;
    long occurrenceCount = 0;

    try {
      for (Long repositoryId : repositoryIds) {
        RepositoryRefreshResult repositoryRefreshResult =
            refreshRepository(repositoryId, refreshRun.getId(), fullRefresh, batchSize);
        processedTextUnitCount += repositoryRefreshResult.processedTextUnitCount();
        occurrenceCount += repositoryRefreshResult.occurrenceCount();
        updateRefreshRunProgress(refreshRun.getId(), processedTextUnitCount, occurrenceCount);
      }

      long extractedTermCount = recomputeAggregatesForRefreshRun(refreshRun.getId(), batchSize);
      refreshRun =
          completeRefreshRun(
              refreshRun.getId(), processedTextUnitCount, occurrenceCount, extractedTermCount);
      return new RefreshResult(
          refreshRun.getId(),
          refreshRun.getStatus(),
          repositories.size(),
          processedTextUnitCount,
          refreshRun.getExtractedTermCount(),
          occurrenceCount);
    } catch (RuntimeException e) {
      failRefreshRun(refreshRun.getId(), processedTextUnitCount, occurrenceCount, e);
      throw e;
    }
  }

  private RepositoryRefreshResult refreshRepository(
      Long repositoryId, Long refreshRunId, boolean fullRefresh, int batchSize) {
    RepositoryLease lease = acquireRepositoryLease(repositoryId, refreshRunId);
    long processedTextUnitCount = 0;
    long occurrenceCount = 0;

    try {
      if (fullRefresh) {
        prepareFullRefresh(lease);
      }

      while (true) {
        BatchRefreshResult batchRefreshResult = refreshNextBatch(lease, fullRefresh, batchSize);
        if (batchRefreshResult.processedTextUnitCount() == 0) {
          break;
        }

        processedTextUnitCount += batchRefreshResult.processedTextUnitCount();
        occurrenceCount += batchRefreshResult.occurrenceCount();

        if (batchRefreshResult.processedTextUnitCount() < batchSize) {
          break;
        }
      }

      completeRepositoryLease(lease);
      return new RepositoryRefreshResult(processedTextUnitCount, occurrenceCount);
    } catch (RuntimeException e) {
      failRepositoryLease(lease, e);
      throw e;
    }
  }

  private void prepareFullRefresh(RepositoryLease lease) {
    transactionTemplate.executeWithoutResult(
        status -> {
          findCursorForLease(lease);
          termIndexRefreshRunEntryRepository.insertExistingRepositoryEntries(
              lease.refreshRunId(), lease.repositoryId());
          termIndexOccurrenceRepository.deleteByRepositoryId(lease.repositoryId());
          assertLeaseUpdated(
              termIndexRepositoryCursorRepository.resetCheckpointForLease(
                  lease.repositoryId(), lease.leaseToken(), nextLeaseExpiresAt()),
              lease);
        });
  }

  private BatchRefreshResult refreshNextBatch(
      RepositoryLease lease, boolean fullRefresh, int batchSize) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status -> {
              TermIndexRepositoryCursor cursor = findCursorForLease(lease);
              Repository repository =
                  repositoryRepository
                      .findById(lease.repositoryId())
                      .orElseThrow(
                          () ->
                              new IllegalArgumentException(
                                  "Unknown repository: " + lease.repositoryId()));
              List<TMTextUnit> textUnits = loadNextBatch(repository.getId(), cursor, batchSize);
              if (textUnits.isEmpty()) {
                return BatchRefreshResult.empty();
              }

              Set<Long> affectedEntryIds = new LinkedHashSet<>();
              if (!fullRefresh) {
                List<Long> textUnitIds = textUnits.stream().map(TMTextUnit::getId).toList();
                affectedEntryIds.addAll(
                    termIndexOccurrenceRepository
                        .findDistinctTermIndexExtractedTermIdsByTmTextUnitIdIn(textUnitIds));
                termIndexOccurrenceRepository.deleteByTmTextUnitIdIn(textUnitIds);
              }

              long occurrenceCount = indexTextUnits(repository, textUnits, affectedEntryIds);
              stageAffectedEntries(lease.refreshRunId(), affectedEntryIds);
              TMTextUnit lastTextUnit = textUnits.getLast();
              assertLeaseUpdated(
                  termIndexRepositoryCursorRepository.checkpointLease(
                      lease.repositoryId(),
                      lease.leaseToken(),
                      lastTextUnit.getCreatedDate(),
                      lastTextUnit.getId(),
                      nextLeaseExpiresAt()),
                  lease);
              return new BatchRefreshResult(textUnits.size(), occurrenceCount);
            }));
  }

  private RepositoryLease acquireRepositoryLease(Long repositoryId, Long refreshRunId) {
    String leaseToken = UUID.randomUUID().toString();
    for (int attempt = 0; attempt < ACQUIRE_LEASE_ATTEMPT_COUNT; attempt++) {
      try {
        return Objects.requireNonNull(
            transactionTemplate.execute(
                status -> {
                  Repository repository =
                      repositoryRepository
                          .findById(repositoryId)
                          .orElseThrow(
                              () ->
                                  new IllegalArgumentException(
                                      "Unknown repository: " + repositoryId));
                  termIndexRepositoryCursorRepository
                      .findByRepositoryId(repositoryId)
                      .orElseGet(
                          () -> termIndexRepositoryCursorRepository.save(newCursor(repository)));
                  TermIndexRefreshRun refreshRun =
                      termIndexRefreshRunRepository
                          .findById(refreshRunId)
                          .orElseThrow(
                              () ->
                                  new IllegalArgumentException(
                                      "Unknown term index refresh run: " + refreshRunId));
                  ZonedDateTime now = ZonedDateTime.now();
                  int updatedRows =
                      termIndexRepositoryCursorRepository.acquireLease(
                          repositoryId,
                          leaseOwner(),
                          leaseToken,
                          now.plus(LEASE_DURATION),
                          refreshRun,
                          now);
                  if (updatedRows != 1) {
                    throw new IllegalStateException(
                        "Term index refresh already running for repository: " + repositoryId);
                  }
                  return new RepositoryLease(repositoryId, refreshRunId, leaseToken);
                }));
      } catch (DataIntegrityViolationException e) {
        if (attempt == ACQUIRE_LEASE_ATTEMPT_COUNT - 1) {
          throw e;
        }
      }
    }
    throw new IllegalStateException("Unable to acquire term index refresh lease: " + repositoryId);
  }

  private void completeRepositoryLease(RepositoryLease lease) {
    transactionTemplate.executeWithoutResult(
        status ->
            assertLeaseUpdated(
                termIndexRepositoryCursorRepository.completeLease(
                    lease.repositoryId(), lease.leaseToken(), ZonedDateTime.now()),
                lease));
  }

  private void failRepositoryLease(RepositoryLease lease, RuntimeException cause) {
    try {
      transactionTemplate.executeWithoutResult(
          status ->
              termIndexRepositoryCursorRepository.failLease(
                  lease.repositoryId(), lease.leaseToken(), errorMessage(cause)));
    } catch (RuntimeException failLeaseException) {
      cause.addSuppressed(failLeaseException);
    }
  }

  private TermIndexRepositoryCursor findCursorForLease(RepositoryLease lease) {
    TermIndexRepositoryCursor cursor =
        termIndexRepositoryCursorRepository
            .findByRepositoryId(lease.repositoryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Missing term index refresh cursor for repository: "
                            + lease.repositoryId()));
    if (!lease.leaseToken().equals(cursor.getLeaseToken())) {
      throw new IllegalStateException(
          "Lost term index refresh lease for repository: " + lease.repositoryId());
    }
    return cursor;
  }

  private long indexTextUnits(
      Repository repository, List<TMTextUnit> textUnits, Set<Long> affectedEntryIds) {
    List<TermIndexOccurrence> occurrences = new ArrayList<>();
    String sourceLocaleTag = sourceLocaleTag(repository);

    for (TMTextUnit textUnit : textUnits) {
      for (TermMatch match : extractTermMatches(textUnit.getContent())) {
        String normalizedKey = normalizeCandidateKey(match.displayTerm());
        if (normalizedKey == null) {
          continue;
        }
        TermIndexExtractedTerm entry =
            findOrCreateEntry(sourceLocaleTag, normalizedKey, match.displayTerm());
        affectedEntryIds.add(entry.getId());

        TermIndexOccurrence occurrence = new TermIndexOccurrence();
        occurrence.setTermIndexExtractedTerm(entry);
        occurrence.setTmTextUnit(textUnit);
        occurrence.setRepository(repository);
        occurrence.setAsset(textUnit.getAsset());
        occurrence.setMatchedText(match.displayTerm());
        occurrence.setStartIndex(match.startIndex());
        occurrence.setEndIndex(match.endIndex());
        occurrence.setSourceHash(sourceHash(textUnit.getContent()));
        occurrence.setExtractorId(EXTRACTOR_ID_PREFIX + match.extractionMethod());
        occurrence.setExtractionMethod(match.extractionMethod());
        occurrence.setConfidence(match.confidence());
        occurrences.add(occurrence);
      }
    }

    if (occurrences.isEmpty()) {
      return 0;
    }
    termIndexOccurrenceRepository.saveAll(occurrences);
    return occurrences.size();
  }

  private TermIndexExtractedTerm findOrCreateEntry(
      String sourceLocaleTag, String normalizedKey, String displayTerm) {
    Optional<TermIndexExtractedTerm> existingEntry =
        termIndexExtractedTermRepository.findBySourceLocaleTagAndNormalizedKey(
            sourceLocaleTag, normalizedKey);
    if (existingEntry.isPresent()) {
      return existingEntry.get();
    }

    termIndexExtractedTermRepository.insertIfAbsent(sourceLocaleTag, normalizedKey, displayTerm);
    return termIndexExtractedTermRepository
        .findBySourceLocaleTagAndNormalizedKey(sourceLocaleTag, normalizedKey)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Unable to create term index entry: " + sourceLocaleTag + "/" + normalizedKey));
  }

  private void updateAggregates(Collection<Long> termIndexExtractedTermIds) {
    for (Long termIndexExtractedTermId : termIndexExtractedTermIds) {
      termIndexExtractedTermRepository
          .findById(termIndexExtractedTermId)
          .ifPresent(this::updateAggregate);
    }
  }

  private void stageAffectedEntries(Long refreshRunId, Collection<Long> termIndexExtractedTermIds) {
    if (termIndexExtractedTermIds.isEmpty()) {
      return;
    }
    termIndexRefreshRunEntryRepository.insertEntries(refreshRunId, termIndexExtractedTermIds);
  }

  private long recomputeAggregatesForRefreshRun(Long refreshRunId, int batchSize) {
    long extractedTermCount = countAffectedEntries(refreshRunId);
    Long afterTermIndexExtractedTermId = 0L;

    while (true) {
      List<Long> termIndexExtractedTermIds =
          loadAffectedEntryIdPage(refreshRunId, afterTermIndexExtractedTermId, batchSize);
      if (termIndexExtractedTermIds.isEmpty()) {
        break;
      }

      transactionTemplate.executeWithoutResult(
          status -> updateAggregates(termIndexExtractedTermIds));
      afterTermIndexExtractedTermId = termIndexExtractedTermIds.getLast();
    }

    return extractedTermCount;
  }

  private List<Long> loadAffectedEntryIdPage(
      Long refreshRunId, Long afterTermIndexExtractedTermId, int batchSize) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status ->
                termIndexRefreshRunEntryRepository.findTermIndexExtractedTermIdsByRefreshRunIdAfter(
                    refreshRunId, afterTermIndexExtractedTermId, PageRequest.of(0, batchSize))));
  }

  private long countAffectedEntries(Long refreshRunId) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status -> termIndexRefreshRunEntryRepository.countByRefreshRunId(refreshRunId)));
  }

  private void updateAggregate(TermIndexExtractedTerm entry) {
    long occurrenceCount = termIndexOccurrenceRepository.countByTermIndexExtractedTerm(entry);
    long repositoryCount =
        termIndexOccurrenceRepository.countDistinctRepositoriesByTermIndexExtractedTerm(entry);
    entry.setOccurrenceCount(occurrenceCount);
    entry.setRepositoryCount(Math.toIntExact(repositoryCount));
    if (occurrenceCount > 0) {
      if (entry.getFirstSeenAt() == null) {
        entry.setFirstSeenAt(ZonedDateTime.now());
      }
      entry.setLastSeenAt(ZonedDateTime.now());
    }
    termIndexExtractedTermRepository.save(entry);
  }

  private List<TMTextUnit> loadNextBatch(
      Long repositoryId, TermIndexRepositoryCursor cursor, int batchSize) {
    ZonedDateTime afterCreatedAt = cursor.getLastProcessedCreatedAt();
    Long afterTextUnitId = afterCreatedAt == null ? null : cursor.getLastProcessedTmTextUnitId();
    return tmTextUnitRepository.findUsedTextUnitsForTermIndexRefresh(
        repositoryId,
        afterCreatedAt,
        afterTextUnitId == null ? 0L : afterTextUnitId,
        PageRequest.of(0, batchSize));
  }

  private TermIndexRefreshRun createRefreshRun(List<Repository> repositories, Long pollableTaskId) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status -> {
              TermIndexRefreshRun refreshRun = new TermIndexRefreshRun();
              refreshRun.setStatus(TermIndexRefreshRun.STATUS_RUNNING);
              refreshRun.setRequestedRepositoryIds(joinRepositoryIds(repositories));
              refreshRun.setPollableTaskId(pollableTaskId);
              refreshRun.setStartedAt(ZonedDateTime.now());
              return termIndexRefreshRunRepository.save(refreshRun);
            }));
  }

  private void updateRefreshRunProgress(
      Long refreshRunId, long processedTextUnitCount, long occurrenceCount) {
    transactionTemplate.executeWithoutResult(
        status -> {
          TermIndexRefreshRun refreshRun = findRefreshRun(refreshRunId);
          refreshRun.setProcessedTextUnitCount(processedTextUnitCount);
          refreshRun.setOccurrenceCount(occurrenceCount);
          refreshRun.setExtractedTermCount(
              termIndexRefreshRunEntryRepository.countByRefreshRunId(refreshRunId));
          termIndexRefreshRunRepository.save(refreshRun);
        });
  }

  private TermIndexRefreshRun completeRefreshRun(
      Long refreshRunId,
      long processedTextUnitCount,
      long occurrenceCount,
      long affectedExtractedTermCount) {
    return Objects.requireNonNull(
        transactionTemplate.execute(
            status -> {
              TermIndexRefreshRun refreshRun = findRefreshRun(refreshRunId);
              refreshRun.setStatus(TermIndexRefreshRun.STATUS_SUCCEEDED);
              refreshRun.setProcessedTextUnitCount(processedTextUnitCount);
              refreshRun.setOccurrenceCount(occurrenceCount);
              refreshRun.setExtractedTermCount(affectedExtractedTermCount);
              refreshRun.setCompletedAt(ZonedDateTime.now());
              return termIndexRefreshRunRepository.save(refreshRun);
            }));
  }

  private void failRefreshRun(
      Long refreshRunId,
      long processedTextUnitCount,
      long occurrenceCount,
      RuntimeException cause) {
    try {
      transactionTemplate.executeWithoutResult(
          status -> {
            TermIndexRefreshRun refreshRun = findRefreshRun(refreshRunId);
            refreshRun.setStatus(TermIndexRefreshRun.STATUS_FAILED);
            refreshRun.setProcessedTextUnitCount(processedTextUnitCount);
            refreshRun.setOccurrenceCount(occurrenceCount);
            refreshRun.setExtractedTermCount(
                termIndexRefreshRunEntryRepository.countByRefreshRunId(refreshRunId));
            refreshRun.setCompletedAt(ZonedDateTime.now());
            refreshRun.setErrorMessage(errorMessage(cause));
            termIndexRefreshRunRepository.save(refreshRun);
          });
    } catch (RuntimeException failRunException) {
      cause.addSuppressed(failRunException);
    }
  }

  private TermIndexRefreshRun findRefreshRun(Long refreshRunId) {
    return termIndexRefreshRunRepository
        .findById(refreshRunId)
        .orElseThrow(
            () -> new IllegalArgumentException("Unknown term index refresh run: " + refreshRunId));
  }

  private TermIndexRepositoryCursor newCursor(Repository repository) {
    TermIndexRepositoryCursor cursor = new TermIndexRepositoryCursor();
    cursor.setRepository(repository);
    cursor.setStatus(TermIndexRepositoryCursor.STATUS_IDLE);
    return cursor;
  }

  private void assertLeaseUpdated(int updatedRows, RepositoryLease lease) {
    if (updatedRows != 1) {
      throw new IllegalStateException(
          "Lost term index refresh lease for repository: " + lease.repositoryId());
    }
  }

  private ZonedDateTime nextLeaseExpiresAt() {
    return ZonedDateTime.now().plus(LEASE_DURATION);
  }

  private String leaseOwner() {
    return ManagementFactory.getRuntimeMXBean().getName();
  }

  private String errorMessage(Throwable throwable) {
    String message = throwable.getMessage();
    if (message == null || message.isBlank()) {
      message = throwable.getClass().getSimpleName();
    }
    return message.length() <= ERROR_MESSAGE_MAX_LENGTH
        ? message
        : message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
  }

  private List<TermMatch> extractTermMatches(String source) {
    if (source == null || source.isBlank()) {
      return List.of();
    }
    Map<String, TermMatch> matchesBySpanAndMethod = new LinkedHashMap<>();
    collectMatches(
        matchesBySpanAndMethod,
        TITLE_PHRASE_PATTERN,
        source,
        TermIndexOccurrence.METHOD_LEXICAL_TITLE_CASE,
        45);
    collectMatches(
        matchesBySpanAndMethod,
        UPPER_TOKEN_PATTERN,
        source,
        TermIndexOccurrence.METHOD_LEXICAL_UPPER_CASE,
        35);
    collectMatches(
        matchesBySpanAndMethod,
        CAMEL_TOKEN_PATTERN,
        source,
        TermIndexOccurrence.METHOD_LEXICAL_CAMEL_CASE,
        40);
    return matchesBySpanAndMethod.values().stream()
        .filter(match -> isUsableCandidate(match.displayTerm()))
        .sorted(Comparator.comparingInt(TermMatch::startIndex).thenComparing(TermMatch::endIndex))
        .toList();
  }

  private void collectMatches(
      Map<String, TermMatch> matchesBySpanAndMethod,
      Pattern pattern,
      String source,
      String extractionMethod,
      int confidence) {
    Matcher matcher = pattern.matcher(source);
    while (matcher.find()) {
      String term = normalizeOptional(matcher.group());
      if (term == null) {
        continue;
      }
      String key = matcher.start() + ":" + matcher.end() + ":" + extractionMethod;
      matchesBySpanAndMethod.putIfAbsent(
          key, new TermMatch(term, matcher.start(), matcher.end(), extractionMethod, confidence));
    }
  }

  private boolean isUsableCandidate(String candidate) {
    if (candidate == null || candidate.length() < 2 || candidate.length() > 80) {
      return false;
    }
    String normalized = normalizeCandidateKey(candidate);
    if (normalized == null) {
      return false;
    }
    List<String> tokens = List.of(normalized.split("[\\s_-]+"));
    if (tokens.stream().allMatch(EXTRACTION_STOP_WORDS::contains)) {
      return false;
    }
    return tokens.stream()
        .anyMatch(token -> token.length() > 2 && !EXTRACTION_STOP_WORDS.contains(token));
  }

  private String normalizeCandidateKey(String candidate) {
    String normalized = normalizeOptional(candidate);
    if (normalized == null) {
      return null;
    }
    normalized =
        normalized
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim()
            .replaceAll("\\s+", " ");
    return normalized.isBlank() ? null : normalized;
  }

  private String sourceLocaleTag(Repository repository) {
    if (repository.getSourceLocale() == null
        || repository.getSourceLocale().getBcp47Tag() == null
        || repository.getSourceLocale().getBcp47Tag().isBlank()) {
      return TermIndexExtractedTerm.SOURCE_LOCALE_ROOT;
    }
    return repository.getSourceLocale().getBcp47Tag();
  }

  private String sourceHash(String source) {
    return source == null ? null : DigestUtils.sha256Hex(source);
  }

  private RefreshCommand validate(RefreshCommand command) {
    if (command == null) {
      return new RefreshCommand(List.of(), false, DEFAULT_BATCH_SIZE);
    }
    return new RefreshCommand(
        command.repositoryIds() == null
            ? List.of()
            : command.repositoryIds().stream().filter(Objects::nonNull).distinct().toList(),
        command.fullRefresh(),
        command.batchSize());
  }

  private List<Repository> resolveRepositories(List<Long> repositoryIds) {
    if (repositoryIds.isEmpty()) {
      return repositoryRepository.findByDeletedFalseAndHiddenFalseOrderByNameAsc();
    }
    List<Repository> repositories =
        repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(repositoryIds);
    if (repositories.size() != repositoryIds.size()) {
      Set<Long> foundIds =
          repositories.stream().map(Repository::getId).collect(java.util.stream.Collectors.toSet());
      List<Long> missingIds = repositoryIds.stream().filter(id -> !foundIds.contains(id)).toList();
      throw new IllegalArgumentException("Unknown repositories: " + missingIds);
    }
    return repositories;
  }

  private int normalizeBatchSize(Integer batchSize) {
    int normalized = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
    if (normalized < 1 || normalized > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "batchSize must be between 1 and " + MAX_BATCH_SIZE + ": " + normalized);
    }
    return normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private String joinRepositoryIds(List<Repository> repositories) {
    return repositories.stream()
        .map(Repository::getId)
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .collect(java.util.stream.Collectors.joining(","));
  }

  public record RefreshCommand(List<Long> repositoryIds, Boolean fullRefresh, Integer batchSize) {}

  public record RefreshResult(
      Long refreshRunId,
      String status,
      int repositoryCount,
      long processedTextUnitCount,
      long extractedTermCount,
      long occurrenceCount) {}

  private record RepositoryLease(Long repositoryId, Long refreshRunId, String leaseToken) {}

  private record RepositoryRefreshResult(long processedTextUnitCount, long occurrenceCount) {}

  private record BatchRefreshResult(long processedTextUnitCount, long occurrenceCount) {

    private static BatchRefreshResult empty() {
      return new BatchRefreshResult(0, 0);
    }
  }

  private record TermMatch(
      String displayTerm, int startIndex, int endIndex, String extractionMethod, int confidence) {}
}
