package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexOccurrence;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import com.box.l10n.mojito.quartz.QuartzJobInfo;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import io.micrometer.core.instrument.Timer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TermIndexRefreshService {

  private static final Logger logger = LoggerFactory.getLogger(TermIndexRefreshService.class);

  static final int DEFAULT_BATCH_SIZE = 1_000;
  static final int MAX_BATCH_SIZE = 10_000;
  static final String EXTRACTOR_ID_PREFIX = "LEXICAL:";
  static final String GLOSSARY_DICTIONARY_EXTRACTOR_ID = "GLOSSARY_DICTIONARY";

  private static final Duration LEASE_DURATION = Duration.ofMinutes(15);
  private static final int ACQUIRE_LEASE_ATTEMPT_COUNT = 2;
  private static final int ERROR_MESSAGE_MAX_LENGTH = 2048;
  private static final int DICTIONARY_OCCURRENCE_CONFIDENCE = 100;
  private static final int MAX_EXTRACTED_TERM_NORMALIZED_KEY_LENGTH = 255;
  private static final int MAX_EXTRACTED_TERM_DISPLAY_TERM_LENGTH = 512;
  private static final int MAX_DICTIONARY_TERM_TOKEN_COUNT = 12;
  private static final Pattern TITLE_PHRASE_PATTERN =
      Pattern.compile("\\b[A-Z][a-z0-9]+(?:[ -][A-Z][a-z0-9]+){0,3}\\b");
  private static final Pattern UPPER_TOKEN_PATTERN =
      Pattern.compile("\\b[A-Z]{2,}(?:[A-Z0-9_-]{0,30})\\b");
  private static final Pattern CAMEL_TOKEN_PATTERN =
      Pattern.compile("\\b[A-Za-z]+(?:[A-Z][a-z0-9]+)+\\b");
  private static final Pattern DICTIONARY_TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
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
  private final GlossaryRepository glossaryRepository;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  private final TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository;
  private final TermIndexRefreshRunRepository termIndexRefreshRunRepository;
  private final TermIndexRefreshRunEntryRepository termIndexRefreshRunEntryRepository;
  private final TransactionTemplate transactionTemplate;
  private final QuartzPollableTaskScheduler quartzPollableTaskScheduler;
  private final TermIndexJobObservability termIndexJobObservability;

  public TermIndexRefreshService(
      com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository,
      TMTextUnitRepository tmTextUnitRepository,
      GlossaryRepository glossaryRepository,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      TermIndexExtractedTermRepository termIndexExtractedTermRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository,
      TermIndexRefreshRunRepository termIndexRefreshRunRepository,
      TermIndexRefreshRunEntryRepository termIndexRefreshRunEntryRepository,
      TransactionTemplate transactionTemplate,
      QuartzPollableTaskScheduler quartzPollableTaskScheduler,
      TermIndexJobObservability termIndexJobObservability) {
    this.repositoryRepository = Objects.requireNonNull(repositoryRepository);
    this.tmTextUnitRepository = Objects.requireNonNull(tmTextUnitRepository);
    this.glossaryRepository = Objects.requireNonNull(glossaryRepository);
    this.glossaryTermMetadataRepository = Objects.requireNonNull(glossaryTermMetadataRepository);
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
    this.termIndexJobObservability = Objects.requireNonNull(termIndexJobObservability);
  }

  public PollableFuture<RefreshResult> scheduleRefresh(RefreshCommand command) {
    RefreshCommand validatedCommand = validate(command);
    QuartzJobInfo<RefreshCommand, RefreshResult> quartzJobInfo =
        QuartzJobInfo.newBuilder(TermIndexRefreshJob.class)
            .withInput(validatedCommand)
            .withMessage("Refresh term index")
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

    Timer.Sample jobTimer = termIndexJobObservability.startTimer();
    TermIndexRefreshRun refreshRun = createRefreshRun(repositories, pollableTaskId);
    termIndexJobObservability.recordJobStarted(TermIndexJobObservability.JOB_REFRESH);
    logger.info(
        "Term index refresh started: refreshRunId={}, repositoryCount={}, fullRefresh={}, batchSize={}, pollableTaskId={}",
        refreshRun.getId(),
        repositories.size(),
        fullRefresh,
        batchSize,
        pollableTaskId);
    long processedTextUnitCount = 0;
    long occurrenceCount = 0;

    try {
      for (Long repositoryId : repositoryIds) {
        long repositoryStartNanos = System.nanoTime();
        logger.info(
            "Term index refresh repository started: refreshRunId={}, repositoryId={}, fullRefresh={}, batchSize={}",
            refreshRun.getId(),
            repositoryId,
            fullRefresh,
            batchSize);
        RepositoryRefreshResult repositoryRefreshResult;
        try {
          repositoryRefreshResult =
              refreshRepository(repositoryId, refreshRun.getId(), fullRefresh, batchSize);
          termIndexJobObservability.recordPhase(
              TermIndexJobObservability.JOB_REFRESH,
              TermIndexJobObservability.PHASE_REFRESH_REPOSITORY,
              TermIndexJobObservability.RESULT_SUCCEEDED,
              elapsedSince(repositoryStartNanos));
        } catch (RuntimeException e) {
          termIndexJobObservability.recordPhase(
              TermIndexJobObservability.JOB_REFRESH,
              TermIndexJobObservability.PHASE_REFRESH_REPOSITORY,
              TermIndexJobObservability.RESULT_FAILED,
              elapsedSince(repositoryStartNanos));
          logger.info(
              "Term index refresh repository failed: refreshRunId={}, repositoryId={}, processedTextUnitCount={}, occurrenceCount={}, errorClass={}, errorMessage={}",
              refreshRun.getId(),
              repositoryId,
              processedTextUnitCount,
              occurrenceCount,
              e.getClass().getSimpleName(),
              errorMessage(e));
          throw e;
        }
        processedTextUnitCount += repositoryRefreshResult.processedTextUnitCount();
        occurrenceCount += repositoryRefreshResult.occurrenceCount();
        updateRefreshRunProgress(refreshRun.getId(), processedTextUnitCount, occurrenceCount);
        logger.info(
            "Term index refresh repository completed: refreshRunId={}, repositoryId={}, repositoryProcessedTextUnitCount={}, repositoryOccurrenceCount={}, totalProcessedTextUnitCount={}, totalOccurrenceCount={}",
            refreshRun.getId(),
            repositoryId,
            repositoryRefreshResult.processedTextUnitCount(),
            repositoryRefreshResult.occurrenceCount(),
            processedTextUnitCount,
            occurrenceCount);
      }

      long aggregateStartNanos = System.nanoTime();
      logger.info(
          "Term index refresh aggregate recomputation started: refreshRunId={}, batchSize={}",
          refreshRun.getId(),
          batchSize);
      long extractedTermCount;
      try {
        extractedTermCount = recomputeAggregatesForRefreshRun(refreshRun.getId(), batchSize);
        termIndexJobObservability.recordPhase(
            TermIndexJobObservability.JOB_REFRESH,
            TermIndexJobObservability.PHASE_REFRESH_AGGREGATES,
            TermIndexJobObservability.RESULT_SUCCEEDED,
            elapsedSince(aggregateStartNanos));
      } catch (RuntimeException e) {
        termIndexJobObservability.recordPhase(
            TermIndexJobObservability.JOB_REFRESH,
            TermIndexJobObservability.PHASE_REFRESH_AGGREGATES,
            TermIndexJobObservability.RESULT_FAILED,
            elapsedSince(aggregateStartNanos));
        logger.info(
            "Term index refresh aggregate recomputation failed: refreshRunId={}, processedTextUnitCount={}, occurrenceCount={}, errorClass={}, errorMessage={}",
            refreshRun.getId(),
            processedTextUnitCount,
            occurrenceCount,
            e.getClass().getSimpleName(),
            errorMessage(e));
        throw e;
      }
      logger.info(
          "Term index refresh aggregate recomputation completed: refreshRunId={}, extractedTermCount={}",
          refreshRun.getId(),
          extractedTermCount);
      refreshRun =
          completeRefreshRun(
              refreshRun.getId(), processedTextUnitCount, occurrenceCount, extractedTermCount);
      RefreshResult result =
          new RefreshResult(
              refreshRun.getId(),
              refreshRun.getStatus(),
              repositories.size(),
              processedTextUnitCount,
              refreshRun.getExtractedTermCount(),
              occurrenceCount);
      termIndexJobObservability.recordJobFinished(
          TermIndexJobObservability.JOB_REFRESH,
          TermIndexJobObservability.RESULT_SUCCEEDED,
          jobTimer);
      logger.info(
          "Term index refresh completed: refreshRunId={}, repositoryCount={}, processedTextUnitCount={}, extractedTermCount={}, occurrenceCount={}",
          refreshRun.getId(),
          repositories.size(),
          processedTextUnitCount,
          refreshRun.getExtractedTermCount(),
          occurrenceCount);
      return result;
    } catch (RuntimeException e) {
      failRefreshRun(refreshRun.getId(), processedTextUnitCount, occurrenceCount, e);
      termIndexJobObservability.recordJobFinished(
          TermIndexJobObservability.JOB_REFRESH, TermIndexJobObservability.RESULT_FAILED, jobTimer);
      logger.info(
          "Term index refresh failed: refreshRunId={}, repositoryCount={}, processedTextUnitCount={}, occurrenceCount={}, errorClass={}, errorMessage={}",
          refreshRun.getId(),
          repositories.size(),
          processedTextUnitCount,
          occurrenceCount,
          e.getClass().getSimpleName(),
          errorMessage(e));
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
      GlossaryDictionary glossaryDictionary = loadGlossaryDictionary(repositoryId);
      int batchNumber = 0;

      while (true) {
        long batchStartNanos = System.nanoTime();
        BatchRefreshResult batchRefreshResult;
        try {
          batchRefreshResult = refreshNextBatch(lease, fullRefresh, batchSize, glossaryDictionary);
          termIndexJobObservability.recordBatch(
              TermIndexJobObservability.JOB_REFRESH,
              TermIndexJobObservability.TYPE_REFRESH_TEXT_UNIT_BATCH,
              TermIndexJobObservability.RESULT_SUCCEEDED,
              elapsedSince(batchStartNanos));
        } catch (RuntimeException e) {
          termIndexJobObservability.recordBatch(
              TermIndexJobObservability.JOB_REFRESH,
              TermIndexJobObservability.TYPE_REFRESH_TEXT_UNIT_BATCH,
              TermIndexJobObservability.RESULT_FAILED,
              elapsedSince(batchStartNanos));
          throw e;
        }
        if (batchRefreshResult.processedTextUnitCount() == 0) {
          break;
        }

        batchNumber++;
        processedTextUnitCount += batchRefreshResult.processedTextUnitCount();
        occurrenceCount += batchRefreshResult.occurrenceCount();
        logger.info(
            "Term index refresh batch completed: refreshRunId={}, repositoryId={}, batchNumber={}, processedTextUnitCount={}, occurrenceCount={}, totalRepositoryProcessedTextUnitCount={}, totalRepositoryOccurrenceCount={}",
            refreshRunId,
            repositoryId,
            batchNumber,
            batchRefreshResult.processedTextUnitCount(),
            batchRefreshResult.occurrenceCount(),
            processedTextUnitCount,
            occurrenceCount);

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
      RepositoryLease lease,
      boolean fullRefresh,
      int batchSize,
      GlossaryDictionary glossaryDictionary) {
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

              long occurrenceCount =
                  indexTextUnits(repository, textUnits, affectedEntryIds, glossaryDictionary);
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
      Repository repository,
      List<TMTextUnit> textUnits,
      Set<Long> affectedEntryIds,
      GlossaryDictionary glossaryDictionary) {
    List<TermIndexOccurrence> occurrences = new ArrayList<>();
    String sourceLocaleTag = sourceLocaleTag(repository);

    for (TMTextUnit textUnit : textUnits) {
      for (TermMatch match : extractTermMatches(textUnit.getContent(), glossaryDictionary)) {
        String normalizedKey = match.normalizedKey();
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
        occurrence.setMatchedText(match.matchedText());
        occurrence.setStartIndex(match.startIndex());
        occurrence.setEndIndex(match.endIndex());
        occurrence.setSourceHash(sourceHash(textUnit.getContent()));
        occurrence.setExtractorId(match.extractorId());
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

  private GlossaryDictionary loadGlossaryDictionary(Long repositoryId) {
    if (repositoryId == null) {
      return GlossaryDictionary.empty();
    }
    List<Glossary> glossaries = glossaryRepository.findEnabledByRepositoryId(repositoryId);
    if (glossaries == null || glossaries.isEmpty()) {
      return GlossaryDictionary.empty();
    }

    Map<String, DictionaryTerm> caseInsensitiveTermsByNormalizedKey = new LinkedHashMap<>();
    Map<String, DictionaryTerm> caseSensitiveTermsByCaseKey = new LinkedHashMap<>();
    int maxTokenCount = 0;

    for (Glossary glossary : glossaries) {
      if (glossary == null || glossary.getId() == null) {
        continue;
      }
      List<GlossaryTermMetadata> metadataRows =
          glossaryTermMetadataRepository.findByGlossaryId(glossary.getId());
      if (metadataRows == null) {
        continue;
      }
      for (GlossaryTermMetadata metadata : metadataRows) {
        DictionaryTerm dictionaryTerm = toDictionaryTerm(metadata);
        if (dictionaryTerm == null) {
          continue;
        }

        Map<String, DictionaryTerm> termsByKey =
            dictionaryTerm.caseSensitive()
                ? caseSensitiveTermsByCaseKey
                : caseInsensitiveTermsByNormalizedKey;
        String key =
            dictionaryTerm.caseSensitive()
                ? dictionaryTerm.caseSensitiveKey()
                : dictionaryTerm.normalizedKey();
        if (!termsByKey.containsKey(key)) {
          termsByKey.put(key, dictionaryTerm);
          maxTokenCount = Math.max(maxTokenCount, dictionaryTerm.tokenCount());
        }
      }
    }

    if (maxTokenCount == 0) {
      return GlossaryDictionary.empty();
    }
    return new GlossaryDictionary(
        caseInsensitiveTermsByNormalizedKey, caseSensitiveTermsByCaseKey, maxTokenCount);
  }

  private DictionaryTerm toDictionaryTerm(GlossaryTermMetadata metadata) {
    if (metadata == null
        || !GlossaryTermMetadata.STATUS_APPROVED.equals(metadata.getStatus())
        || metadata.getTmTextUnit() == null) {
      return null;
    }
    String displayTerm = normalizeOptional(metadata.getTmTextUnit().getContent());
    if (displayTerm == null || displayTerm.length() > MAX_EXTRACTED_TERM_DISPLAY_TERM_LENGTH) {
      return null;
    }
    String normalizedKey = normalizeCandidateKey(displayTerm);
    String caseSensitiveKey = normalizeDictionaryCaseKey(displayTerm);
    if (normalizedKey == null
        || caseSensitiveKey == null
        || normalizedKey.length() > MAX_EXTRACTED_TERM_NORMALIZED_KEY_LENGTH) {
      return null;
    }
    int tokenCount = tokenCount(normalizedKey);
    if (tokenCount == 0 || tokenCount > MAX_DICTIONARY_TERM_TOKEN_COUNT) {
      return null;
    }
    return new DictionaryTerm(
        displayTerm,
        normalizedKey,
        caseSensitiveKey,
        Boolean.TRUE.equals(metadata.getCaseSensitive()),
        tokenCount);
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

  private Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
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

  private List<TermMatch> extractTermMatches(String source, GlossaryDictionary glossaryDictionary) {
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
    collectGlossaryDictionaryMatches(matchesBySpanAndMethod, source, glossaryDictionary);
    return matchesBySpanAndMethod.values().stream()
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
      String normalizedKey = normalizeCandidateKey(term);
      if (normalizedKey == null || !isUsableCandidate(term)) {
        continue;
      }
      String key = termMatchKey(matcher.start(), matcher.end(), extractionMethod, normalizedKey);
      matchesBySpanAndMethod.putIfAbsent(
          key,
          new TermMatch(
              term,
              term,
              normalizedKey,
              matcher.start(),
              matcher.end(),
              extractionMethod,
              EXTRACTOR_ID_PREFIX + extractionMethod,
              confidence));
    }
  }

  private void collectGlossaryDictionaryMatches(
      Map<String, TermMatch> matchesBySpanAndMethod,
      String source,
      GlossaryDictionary glossaryDictionary) {
    if (glossaryDictionary == null || glossaryDictionary.isEmpty()) {
      return;
    }
    List<SourceToken> tokens = tokenizeForDictionary(source);
    for (int startTokenIndex = 0; startTokenIndex < tokens.size(); startTokenIndex++) {
      StringBuilder normalizedKey = new StringBuilder();
      StringBuilder caseSensitiveKey = new StringBuilder();
      int maxEndTokenIndex =
          Math.min(tokens.size(), startTokenIndex + glossaryDictionary.maxTokenCount());
      for (int endTokenIndex = startTokenIndex; endTokenIndex < maxEndTokenIndex; endTokenIndex++) {
        SourceToken token = tokens.get(endTokenIndex);
        if (endTokenIndex > startTokenIndex) {
          normalizedKey.append(' ');
          caseSensitiveKey.append(' ');
        }
        normalizedKey.append(token.text().toLowerCase(Locale.ROOT));
        caseSensitiveKey.append(token.text());

        int startIndex = tokens.get(startTokenIndex).startIndex();
        int endIndex = token.endIndex();
        String matchedText = source.substring(startIndex, endIndex);
        addGlossaryDictionaryMatch(
            matchesBySpanAndMethod,
            glossaryDictionary.caseSensitiveTermsByCaseKey().get(caseSensitiveKey.toString()),
            matchedText,
            startIndex,
            endIndex);
        addGlossaryDictionaryMatch(
            matchesBySpanAndMethod,
            glossaryDictionary.caseInsensitiveTermsByNormalizedKey().get(normalizedKey.toString()),
            matchedText,
            startIndex,
            endIndex);
      }
    }
  }

  private List<SourceToken> tokenizeForDictionary(String source) {
    List<SourceToken> tokens = new ArrayList<>();
    Matcher matcher = DICTIONARY_TOKEN_PATTERN.matcher(source);
    while (matcher.find()) {
      tokens.add(new SourceToken(matcher.group(), matcher.start(), matcher.end()));
    }
    return tokens;
  }

  private void addGlossaryDictionaryMatch(
      Map<String, TermMatch> matchesBySpanAndMethod,
      DictionaryTerm dictionaryTerm,
      String matchedText,
      int startIndex,
      int endIndex) {
    if (dictionaryTerm == null
        || matchedText == null
        || matchedText.length() > MAX_EXTRACTED_TERM_DISPLAY_TERM_LENGTH) {
      return;
    }
    String key =
        termMatchKey(
            startIndex,
            endIndex,
            TermIndexOccurrence.METHOD_EXTERNAL_GLOSSARY_IMPORT,
            dictionaryTerm.normalizedKey());
    matchesBySpanAndMethod.putIfAbsent(
        key,
        new TermMatch(
            dictionaryTerm.displayTerm(),
            matchedText,
            dictionaryTerm.normalizedKey(),
            startIndex,
            endIndex,
            TermIndexOccurrence.METHOD_EXTERNAL_GLOSSARY_IMPORT,
            GLOSSARY_DICTIONARY_EXTRACTOR_ID,
            DICTIONARY_OCCURRENCE_CONFIDENCE));
  }

  private String termMatchKey(
      int startIndex, int endIndex, String extractionMethod, String normalizedKey) {
    return startIndex + ":" + endIndex + ":" + extractionMethod + ":" + normalizedKey;
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

  private String normalizeDictionaryCaseKey(String candidate) {
    String normalized = normalizeOptional(candidate);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    return normalized.isBlank() ? null : normalized;
  }

  private int tokenCount(String normalizedKey) {
    String normalized = normalizeOptional(normalizedKey);
    return normalized == null ? 0 : normalized.split(" ").length;
  }

  private String sourceLocaleTag(Repository repository) {
    if (repository.getSourceLocale() == null
        || repository.getSourceLocale().getBcp47Tag() == null
        || repository.getSourceLocale().getBcp47Tag().isBlank()) {
      return TermIndexExtractedTerm.DEFAULT_SOURCE_LOCALE_TAG;
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
      String displayTerm,
      String matchedText,
      String normalizedKey,
      int startIndex,
      int endIndex,
      String extractionMethod,
      String extractorId,
      int confidence) {}

  private record GlossaryDictionary(
      Map<String, DictionaryTerm> caseInsensitiveTermsByNormalizedKey,
      Map<String, DictionaryTerm> caseSensitiveTermsByCaseKey,
      int maxTokenCount) {

    private static GlossaryDictionary empty() {
      return new GlossaryDictionary(Map.of(), Map.of(), 0);
    }

    private boolean isEmpty() {
      return maxTokenCount == 0;
    }
  }

  private record DictionaryTerm(
      String displayTerm,
      String normalizedKey,
      String caseSensitiveKey,
      boolean caseSensitive,
      int tokenCount) {}

  private record SourceToken(String text, int startIndex, int endIndex) {}
}
