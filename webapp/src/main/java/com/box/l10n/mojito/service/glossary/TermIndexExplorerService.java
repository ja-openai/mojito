package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexAutomationRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexCandidate;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexExtractedTerm;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexReview;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Service
public class TermIndexExplorerService {

  private static final int DEFAULT_LIMIT = 1000;
  private static final int MAX_LIMIT = 10000;
  private static final int DEFAULT_RECENT_RUN_LIMIT = 20;
  private static final int MAX_RECENT_RUN_LIMIT = 500;
  private static final List<Long> EMPTY_FILTER_SENTINEL = List.of(-1L);
  private static final String ENTRY_SORT_HITS = "HITS";
  private static final String ENTRY_SORT_REVIEW_CONFIDENCE_DESC = "REVIEW_CONFIDENCE_DESC";
  private static final String ENTRY_SORT_REVIEW_CONFIDENCE_ASC = "REVIEW_CONFIDENCE_ASC";

  private final UserService userService;
  private final TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  private final TermIndexCandidateRepository termIndexCandidateRepository;
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  private final TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository;
  private final TermIndexRefreshRunRepository termIndexRefreshRunRepository;
  private final TermIndexAutomationRunRepository termIndexAutomationRunRepository;
  private final PlatformTransactionManager transactionManager;

  public TermIndexExplorerService(
      UserService userService,
      TermIndexExtractedTermRepository termIndexExtractedTermRepository,
      TermIndexCandidateRepository termIndexCandidateRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository,
      TermIndexRefreshRunRepository termIndexRefreshRunRepository,
      TermIndexAutomationRunRepository termIndexAutomationRunRepository,
      PlatformTransactionManager transactionManager) {
    this.userService = Objects.requireNonNull(userService);
    this.termIndexExtractedTermRepository =
        Objects.requireNonNull(termIndexExtractedTermRepository);
    this.termIndexCandidateRepository = Objects.requireNonNull(termIndexCandidateRepository);
    this.termIndexOccurrenceRepository = Objects.requireNonNull(termIndexOccurrenceRepository);
    this.termIndexRepositoryCursorRepository =
        Objects.requireNonNull(termIndexRepositoryCursorRepository);
    this.termIndexRefreshRunRepository = Objects.requireNonNull(termIndexRefreshRunRepository);
    this.termIndexAutomationRunRepository =
        Objects.requireNonNull(termIndexAutomationRunRepository);
    this.transactionManager = Objects.requireNonNull(transactionManager);
  }

  public EntrySearchView searchEntries(EntrySearchCommand command) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());

    try {
      EntrySearchView result = searchEntriesNoTx(command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  EntrySearchView searchEntriesNoTx(EntrySearchCommand command) {
    requireAdmin();
    EntrySearchCommand normalized = normalize(command);
    List<Long> repositoryIds = repositoryIdsOrSentinel(normalized.repositoryIds());
    boolean repositoryIdsEmpty = normalized.repositoryIds().isEmpty();

    List<TermIndexExtractedTermRepository.SearchRow> rows =
        termIndexExtractedTermRepository.searchEntries(
            repositoryIdsEmpty,
            repositoryIds,
            normalizeOptional(normalized.searchQuery()),
            normalizeOptional(normalized.extractionMethod()),
            normalized.reviewStatusFilter(),
            normalized.reviewAuthorityFilter(),
            normalized.minOccurrences(),
            normalized.lastOccurrenceAfter(),
            normalized.lastOccurrenceBefore(),
            normalized.reviewChangedAfter(),
            normalized.reviewChangedBefore(),
            normalized.sortBy(),
            pageRequest(normalized.limit()));
    Map<Long, CandidateSummary> candidatesByExtractedTermId =
        findCandidatesByExtractedTermId(
            rows.stream().map(TermIndexExtractedTermRepository.SearchRow::getId).toList());
    List<EntrySummaryView> entries =
        rows.stream()
            .map(row -> toEntrySummaryView(row, candidatesByExtractedTermId.get(row.getId())))
            .toList();
    return new EntrySearchView(entries);
  }

  public EntrySummaryView updateEntryReview(
      Long termIndexExtractedTermId, ReviewUpdateCommand command) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      EntrySummaryView result = updateEntryReviewNoTx(termIndexExtractedTermId, command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  EntrySummaryView updateEntryReviewNoTx(
      Long termIndexExtractedTermId, ReviewUpdateCommand command) {
    requireAdmin();
    TermIndexExtractedTerm entry =
        termIndexExtractedTermRepository
            .findById(termIndexExtractedTermId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Term index extracted term not found: " + termIndexExtractedTermId));
    ReviewUpdateCommand normalized = normalize(command);
    applyHumanReview(
        entry,
        normalized.reviewStatus(),
        normalized.reviewReason(),
        normalized.reviewRationale(),
        normalized.reviewConfidence(),
        currentUserOrNull());
    TermIndexExtractedTerm saved = termIndexExtractedTermRepository.save(entry);
    return toEntrySummaryView(saved, findCandidateByExtractedTermId(saved.getId()));
  }

  public CandidateSearchView searchCandidates(CandidateSearchCommand command) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());

    try {
      CandidateSearchView result = searchCandidatesNoTx(command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  CandidateSearchView searchCandidatesNoTx(CandidateSearchCommand command) {
    requireAdmin();
    CandidateSearchCommand normalized = normalize(command);
    List<Long> repositoryIds = repositoryIdsOrSentinel(normalized.repositoryIds());
    boolean repositoryIdsEmpty = normalized.repositoryIds().isEmpty();

    List<CandidateSummaryView> candidates =
        termIndexCandidateRepository
            .searchForExplorer(
                repositoryIdsEmpty,
                repositoryIds,
                normalizeOptional(normalized.searchQuery()),
                normalized.reviewStatusFilter(),
                normalized.reviewAuthorityFilter(),
                normalized.minOccurrences(),
                normalized.reviewChangedAfter(),
                normalized.reviewChangedBefore(),
                pageRequest(normalized.limit()))
            .stream()
            .map(this::toCandidateSummaryView)
            .toList();
    return new CandidateSearchView(candidates);
  }

  public CandidateSummaryView updateCandidate(
      Long termIndexCandidateId, CandidateUpdateCommand command) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      CandidateSummaryView result = updateCandidateNoTx(termIndexCandidateId, command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  CandidateSummaryView updateCandidateNoTx(
      Long termIndexCandidateId, CandidateUpdateCommand command) {
    requireAdmin();
    TermIndexCandidate candidate =
        termIndexCandidateRepository
            .findById(termIndexCandidateId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Term index candidate not found: " + termIndexCandidateId));
    CandidateUpdateCommand normalized = normalize(command);
    candidate.setDefinition(truncate(normalized.definition(), 2048));
    candidate.setRationale(truncate(normalized.rationale(), 2048));
    candidate.setTermType(truncate(normalized.termType(), 32));
    candidate.setPartOfSpeech(truncate(normalized.partOfSpeech(), 64));
    candidate.setEnforcement(truncate(normalized.enforcement(), 32));
    candidate.setDoNotTranslate(normalized.doNotTranslate());
    candidate.setConfidence(clampConfidence(normalized.confidence()));

    if (normalized.reviewStatus() != null
        && !normalized.reviewStatus().equals(candidate.getReviewStatus())) {
      applyHumanReview(
          candidate,
          normalized.reviewStatus(),
          normalized.reviewReason(),
          normalized.reviewRationale(),
          normalized.reviewConfidence(),
          currentUserOrNull());
    }

    TermIndexCandidate saved = termIndexCandidateRepository.save(candidate);
    return toCandidateSummaryView(saved);
  }

  public BatchReviewUpdateView updateEntryReviews(BatchReviewUpdateCommand command) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      BatchReviewUpdateView result = updateEntryReviewsNoTx(command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  BatchReviewUpdateView updateEntryReviewsNoTx(BatchReviewUpdateCommand command) {
    requireAdmin();
    BatchReviewUpdateCommand normalized = normalize(command);
    List<Long> termIndexExtractedTermIds =
        normalized.termIndexExtractedTermIds().stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (termIndexExtractedTermIds.isEmpty()) {
      throw new IllegalArgumentException("termIndexEntryIds are required");
    }

    List<TermIndexExtractedTerm> entries =
        termIndexExtractedTermRepository.findAllById(termIndexExtractedTermIds);
    User currentUser = currentUserOrNull();
    for (TermIndexExtractedTerm entry : entries) {
      applyHumanReview(
          entry,
          normalized.reviewStatus(),
          Boolean.TRUE.equals(normalized.updateReviewReason())
              ? normalized.reviewReason()
              : entry.getReviewReason(),
          Boolean.TRUE.equals(normalized.updateReviewRationale())
              ? normalized.reviewRationale()
              : entry.getReviewRationale(),
          Boolean.TRUE.equals(normalized.updateReviewConfidence())
              ? normalized.reviewConfidence()
              : entry.getReviewConfidence(),
          currentUser);
    }
    termIndexExtractedTermRepository.saveAll(entries);
    return new BatchReviewUpdateView(entries.size());
  }

  public CandidateBatchReviewUpdateView updateCandidateReviews(
      CandidateBatchReviewUpdateCommand command) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      CandidateBatchReviewUpdateView result = updateCandidateReviewsNoTx(command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  CandidateBatchReviewUpdateView updateCandidateReviewsNoTx(
      CandidateBatchReviewUpdateCommand command) {
    requireAdmin();
    CandidateBatchReviewUpdateCommand normalized = normalize(command);
    List<Long> termIndexCandidateIds =
        normalized.termIndexCandidateIds().stream().filter(Objects::nonNull).distinct().toList();
    if (termIndexCandidateIds.isEmpty()) {
      throw new IllegalArgumentException("termIndexCandidateIds are required");
    }

    List<TermIndexCandidate> candidates =
        termIndexCandidateRepository.findAllById(termIndexCandidateIds);
    User currentUser = currentUserOrNull();
    for (TermIndexCandidate candidate : candidates) {
      applyHumanReview(
          candidate,
          normalized.reviewStatus(),
          candidate.getReviewReason(),
          candidate.getReviewRationale(),
          candidate.getReviewConfidence(),
          currentUser);
    }
    termIndexCandidateRepository.saveAll(candidates);
    return new CandidateBatchReviewUpdateView(candidates.size());
  }

  public OccurrenceSearchView searchOccurrences(
      Long termIndexExtractedTermId, OccurrenceSearchCommand command) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());

    try {
      OccurrenceSearchView result = searchOccurrencesNoTx(termIndexExtractedTermId, command);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  OccurrenceSearchView searchOccurrencesNoTx(
      Long termIndexExtractedTermId, OccurrenceSearchCommand command) {
    requireAdmin();
    OccurrenceSearchCommand normalized = normalize(command);
    List<Long> repositoryIds = repositoryIdsOrSentinel(normalized.repositoryIds());
    boolean repositoryIdsEmpty = normalized.repositoryIds().isEmpty();

    List<OccurrenceView> occurrences =
        termIndexOccurrenceRepository
            .findDetailsByTermIndexExtractedTermId(
                termIndexExtractedTermId,
                repositoryIdsEmpty,
                repositoryIds,
                normalizeOptional(normalized.extractionMethod()),
                pageRequest(normalized.limit()))
            .stream()
            .map(this::toOccurrenceView)
            .toList();
    return new OccurrenceSearchView(occurrences);
  }

  public StatusView getStatus(List<Long> repositoryIds, Integer recentRunLimit) {
    TransactionStatus transaction = transactionManager.getTransaction(readOnlyTransaction());

    try {
      StatusView result = getStatusNoTx(repositoryIds, recentRunLimit);
      transactionManager.commit(transaction);
      return result;
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  StatusView getStatusNoTx(List<Long> repositoryIds, Integer recentRunLimit) {
    requireAdmin();
    List<Long> normalizedRepositoryIds = normalizeRepositoryIds(repositoryIds);
    List<Long> repositoryIdsFilter = repositoryIdsOrSentinel(normalizedRepositoryIds);
    boolean repositoryIdsEmpty = normalizedRepositoryIds.isEmpty();
    int normalizedRecentRunLimit = normalizeRecentRunLimit(recentRunLimit);

    List<CursorView> cursors =
        termIndexRepositoryCursorRepository
            .findForExplorer(repositoryIdsEmpty, repositoryIdsFilter)
            .stream()
            .map(this::toCursorView)
            .toList();
    List<RefreshRunView> recentRuns =
        termIndexRefreshRunRepository
            .findAllByOrderByIdDesc(PageRequest.of(0, normalizedRecentRunLimit))
            .stream()
            .map(this::toRefreshRunView)
            .toList();
    List<JobView> recentJobs =
        termIndexAutomationRunRepository
            .findAllByOrderByIdDesc(PageRequest.of(0, normalizedRecentRunLimit))
            .stream()
            .map(this::toJobView)
            .toList();
    List<String> extractionMethods = termIndexOccurrenceRepository.findDistinctExtractionMethods();
    return new StatusView(cursors, recentRuns, recentJobs, extractionMethods);
  }

  private DefaultTransactionDefinition readOnlyTransaction() {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setReadOnly(true);
    return transactionDefinition;
  }

  private EntrySummaryView toEntrySummaryView(
      TermIndexExtractedTermRepository.SearchRow row, CandidateSummary candidate) {
    return new EntrySummaryView(
        row.getId(),
        candidate == null ? null : candidate.id(),
        candidate == null ? null : candidate.definition(),
        candidate == null ? null : candidate.rationale(),
        candidate == null ? null : candidate.termType(),
        candidate == null ? null : candidate.partOfSpeech(),
        candidate == null ? null : candidate.enforcement(),
        candidate == null ? null : candidate.doNotTranslate(),
        candidate == null ? null : candidate.confidence(),
        candidate == null ? null : candidate.reviewStatus(),
        candidate == null ? null : candidate.reviewAuthority(),
        candidate == null ? null : candidate.reviewReason(),
        candidate == null ? null : candidate.reviewRationale(),
        candidate == null ? null : candidate.reviewConfidence(),
        candidate == null ? null : candidate.reviewChangedAt(),
        candidate == null ? null : candidate.reviewChangedByUserId(),
        candidate == null ? null : candidate.reviewChangedByUsername(),
        candidate == null ? null : candidate.reviewChangedByCommonName(),
        row.getNormalizedKey(),
        row.getDisplayTerm(),
        row.getSourceLocaleTag(),
        row.getCreatedDate(),
        row.getLastModifiedDate(),
        row.getReviewStatus(),
        row.getReviewAuthority(),
        row.getReviewReason(),
        row.getReviewRationale(),
        row.getReviewConfidence(),
        row.getReviewChangedAt(),
        row.getReviewChangedByUserId(),
        row.getReviewChangedByUsername(),
        row.getReviewChangedByCommonName(),
        nullToZero(row.getOccurrenceCount()),
        Math.toIntExact(nullToZero(row.getRepositoryCount())),
        row.getLastOccurrenceAt());
  }

  private EntrySummaryView toEntrySummaryView(
      TermIndexExtractedTerm entry, CandidateSummary candidate) {
    return new EntrySummaryView(
        entry.getId(),
        candidate == null ? null : candidate.id(),
        candidate == null ? null : candidate.definition(),
        candidate == null ? null : candidate.rationale(),
        candidate == null ? null : candidate.termType(),
        candidate == null ? null : candidate.partOfSpeech(),
        candidate == null ? null : candidate.enforcement(),
        candidate == null ? null : candidate.doNotTranslate(),
        candidate == null ? null : candidate.confidence(),
        candidate == null ? null : candidate.reviewStatus(),
        candidate == null ? null : candidate.reviewAuthority(),
        candidate == null ? null : candidate.reviewReason(),
        candidate == null ? null : candidate.reviewRationale(),
        candidate == null ? null : candidate.reviewConfidence(),
        candidate == null ? null : candidate.reviewChangedAt(),
        candidate == null ? null : candidate.reviewChangedByUserId(),
        candidate == null ? null : candidate.reviewChangedByUsername(),
        candidate == null ? null : candidate.reviewChangedByCommonName(),
        entry.getNormalizedKey(),
        entry.getDisplayTerm(),
        entry.getSourceLocaleTag(),
        entry.getCreatedDate(),
        entry.getLastModifiedDate(),
        entry.getReviewStatus(),
        entry.getReviewAuthority(),
        entry.getReviewReason(),
        entry.getReviewRationale(),
        entry.getReviewConfidence(),
        entry.getReviewChangedAt(),
        entry.getReviewChangedByUser() == null ? null : entry.getReviewChangedByUser().getId(),
        entry.getReviewChangedByUser() == null
            ? null
            : entry.getReviewChangedByUser().getUsername(),
        entry.getReviewChangedByUser() == null
            ? null
            : entry.getReviewChangedByUser().getCommonName(),
        nullToZero(entry.getOccurrenceCount()),
        entry.getRepositoryCount() == null ? 0 : entry.getRepositoryCount(),
        entry.getLastSeenAt());
  }

  private Map<Long, CandidateSummary> findCandidatesByExtractedTermId(
      List<Long> termIndexExtractedTermIds) {
    Map<Long, CandidateSummary> candidatesByExtractedTermId = new HashMap<>();
    if (termIndexExtractedTermIds.isEmpty()) {
      return candidatesByExtractedTermId;
    }
    termIndexCandidateRepository
        .findByTermIndexExtractedTermIdInOrderByCreatedDateDesc(termIndexExtractedTermIds)
        .forEach(
            candidate -> {
              if (candidate.getTermIndexExtractedTerm() == null) {
                return;
              }
              candidatesByExtractedTermId.putIfAbsent(
                  candidate.getTermIndexExtractedTerm().getId(), toCandidateSummary(candidate));
            });
    return candidatesByExtractedTermId;
  }

  private CandidateSummary findCandidateByExtractedTermId(Long termIndexExtractedTermId) {
    if (termIndexExtractedTermId == null) {
      return null;
    }
    return findCandidatesByExtractedTermId(List.of(termIndexExtractedTermId))
        .get(termIndexExtractedTermId);
  }

  private CandidateSummary toCandidateSummary(TermIndexCandidate candidate) {
    return new CandidateSummary(
        candidate.getId(),
        candidate.getDefinition(),
        candidate.getRationale(),
        candidate.getTermType(),
        candidate.getPartOfSpeech(),
        candidate.getEnforcement(),
        candidate.getDoNotTranslate(),
        candidate.getConfidence(),
        candidate.getReviewStatus(),
        candidate.getReviewAuthority(),
        candidate.getReviewReason(),
        candidate.getReviewRationale(),
        candidate.getReviewConfidence(),
        candidate.getReviewChangedAt(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getId(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getUsername(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getCommonName());
  }

  private CandidateSummaryView toCandidateSummaryView(
      TermIndexCandidateRepository.CandidateExplorerRow row) {
    return new CandidateSummaryView(
        row.getId(),
        row.getTermIndexExtractedTermId(),
        row.getNormalizedKey(),
        row.getTerm(),
        row.getLabel(),
        row.getSourceLocaleTag(),
        row.getMetadataJson(),
        row.getDefinition(),
        row.getRationale(),
        row.getTermType(),
        row.getPartOfSpeech(),
        row.getEnforcement(),
        row.getDoNotTranslate(),
        row.getConfidence(),
        row.getReviewStatus(),
        row.getReviewAuthority(),
        row.getReviewReason(),
        row.getReviewRationale(),
        row.getReviewConfidence(),
        row.getReviewChangedAt(),
        row.getReviewChangedByUserId(),
        row.getReviewChangedByUsername(),
        row.getReviewChangedByCommonName(),
        nullToZero(row.getOccurrenceCount()),
        Math.toIntExact(nullToZero(row.getRepositoryCount())),
        row.getLastOccurrenceAt(),
        row.getCandidateCreatedDate());
  }

  private CandidateSummaryView toCandidateSummaryView(TermIndexCandidate candidate) {
    Long termIndexExtractedTermId =
        candidate.getTermIndexExtractedTerm() == null
            ? null
            : candidate.getTermIndexExtractedTerm().getId();
    return new CandidateSummaryView(
        candidate.getId(),
        termIndexExtractedTermId,
        candidate.getNormalizedKey(),
        candidate.getTerm(),
        candidate.getLabel(),
        candidate.getSourceLocaleTag(),
        candidate.getMetadataJson(),
        candidate.getDefinition(),
        candidate.getRationale(),
        candidate.getTermType(),
        candidate.getPartOfSpeech(),
        candidate.getEnforcement(),
        candidate.getDoNotTranslate(),
        candidate.getConfidence(),
        candidate.getReviewStatus(),
        candidate.getReviewAuthority(),
        candidate.getReviewReason(),
        candidate.getReviewRationale(),
        candidate.getReviewConfidence(),
        candidate.getReviewChangedAt(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getId(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getUsername(),
        candidate.getReviewChangedByUser() == null
            ? null
            : candidate.getReviewChangedByUser().getCommonName(),
        candidate.getTermIndexExtractedTerm() == null
            ? 0
            : nullToZero(candidate.getTermIndexExtractedTerm().getOccurrenceCount()),
        candidate.getTermIndexExtractedTerm() == null
            ? 0
            : candidate.getTermIndexExtractedTerm().getRepositoryCount() == null
                ? 0
                : candidate.getTermIndexExtractedTerm().getRepositoryCount(),
        candidate.getTermIndexExtractedTerm() == null
            ? null
            : candidate.getTermIndexExtractedTerm().getLastSeenAt(),
        candidate.getCreatedDate());
  }

  private OccurrenceView toOccurrenceView(TermIndexOccurrenceRepository.DetailRow row) {
    return new OccurrenceView(
        row.getId(),
        row.getRepositoryId(),
        row.getRepositoryName(),
        row.getAssetId(),
        row.getAssetPath(),
        row.getTmTextUnitId(),
        row.getTextUnitName(),
        row.getSourceText(),
        row.getMatchedText(),
        row.getStartIndex(),
        row.getEndIndex(),
        row.getExtractorId(),
        row.getExtractionMethod(),
        row.getConfidence(),
        row.getCreatedDate());
  }

  private CursorView toCursorView(TermIndexRepositoryCursor cursor) {
    return new CursorView(
        cursor.getRepository().getId(),
        cursor.getRepository().getName(),
        cursor.getStatus(),
        cursor.getLastProcessedCreatedAt(),
        cursor.getLastProcessedTmTextUnitId(),
        cursor.getLastSuccessfulScanAt(),
        cursor.getLeaseOwner(),
        cursor.getLeaseExpiresAt(),
        cursor.getCurrentRefreshRun() == null ? null : cursor.getCurrentRefreshRun().getId(),
        cursor.getErrorMessage());
  }

  private RefreshRunView toRefreshRunView(TermIndexRefreshRun run) {
    return new RefreshRunView(
        run.getId(),
        run.getStatus(),
        run.getRequestedRepositoryIds(),
        run.getPollableTaskId(),
        run.getProcessedTextUnitCount(),
        run.getExtractedTermCount(),
        run.getOccurrenceCount(),
        run.getStartedAt(),
        run.getCompletedAt(),
        run.getErrorMessage());
  }

  private JobView toJobView(TermIndexAutomationRun run) {
    return new JobView(
        run.getId(),
        run.getType(),
        run.getPollableTaskId(),
        toJobStatus(run.getStatus()),
        run.getMessage(),
        run.getErrorMessage(),
        run.getStartedAt(),
        run.getCompletedAt());
  }

  private String toJobStatus(String runStatus) {
    if (TermIndexAutomationRun.STATUS_SUCCEEDED.equals(runStatus)) {
      return "COMPLETED";
    }
    return runStatus;
  }

  private EntrySearchCommand normalize(EntrySearchCommand command) {
    if (command == null) {
      return new EntrySearchCommand(
          List.of(),
          null,
          null,
          TermIndexReview.STATUS_FILTER_NON_REJECTED,
          null,
          1L,
          DEFAULT_LIMIT,
          null,
          null,
          null,
          null,
          ENTRY_SORT_REVIEW_CONFIDENCE_DESC);
    }
    return new EntrySearchCommand(
        normalizeRepositoryIds(command.repositoryIds()),
        command.searchQuery(),
        command.extractionMethod(),
        normalizeReviewStatusFilter(command.reviewStatusFilter()),
        normalizeReviewAuthorityFilter(command.reviewAuthorityFilter()),
        Math.max(1L, command.minOccurrences() == null ? 1L : command.minOccurrences()),
        normalizeLimit(command.limit()),
        command.lastOccurrenceAfter(),
        command.lastOccurrenceBefore(),
        command.reviewChangedAfter(),
        command.reviewChangedBefore(),
        normalizeEntrySort(command.sortBy()));
  }

  private CandidateSearchCommand normalize(CandidateSearchCommand command) {
    if (command == null) {
      return new CandidateSearchCommand(
          List.of(),
          null,
          TermIndexReview.STATUS_FILTER_NON_REJECTED,
          TermIndexReview.AUTHORITY_FILTER_ALL,
          0L,
          DEFAULT_LIMIT,
          null,
          null);
    }
    return new CandidateSearchCommand(
        normalizeRepositoryIds(command.repositoryIds()),
        command.searchQuery(),
        normalizeReviewStatusFilter(command.reviewStatusFilter()),
        normalizeReviewAuthorityFilter(command.reviewAuthorityFilter(), true),
        Math.max(0L, command.minOccurrences() == null ? 0L : command.minOccurrences()),
        normalizeLimit(command.limit()),
        command.reviewChangedAfter(),
        command.reviewChangedBefore());
  }

  private OccurrenceSearchCommand normalize(OccurrenceSearchCommand command) {
    if (command == null) {
      return new OccurrenceSearchCommand(List.of(), null, DEFAULT_LIMIT);
    }
    return new OccurrenceSearchCommand(
        normalizeRepositoryIds(command.repositoryIds()),
        command.extractionMethod(),
        normalizeLimit(command.limit()));
  }

  private List<Long> normalizeRepositoryIds(List<Long> repositoryIds) {
    if (repositoryIds == null) {
      return List.of();
    }
    return repositoryIds.stream().filter(Objects::nonNull).distinct().toList();
  }

  private List<Long> repositoryIdsOrSentinel(List<Long> repositoryIds) {
    return repositoryIds.isEmpty() ? EMPTY_FILTER_SENTINEL : repositoryIds;
  }

  private ReviewUpdateCommand normalize(ReviewUpdateCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("reviewStatus is required");
    }
    return new ReviewUpdateCommand(
        normalizeReviewStatus(command.reviewStatus()),
        truncate(normalizeOptional(command.reviewReason()), 64),
        truncate(normalizeOptional(command.reviewRationale()), 2048),
        clampConfidence(command.reviewConfidence()));
  }

  private BatchReviewUpdateCommand normalize(BatchReviewUpdateCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("reviewStatus is required");
    }
    return new BatchReviewUpdateCommand(
        command.termIndexExtractedTermIds() == null
            ? List.of()
            : command.termIndexExtractedTermIds(),
        normalizeReviewStatus(command.reviewStatus()),
        Boolean.TRUE.equals(command.updateReviewReason()),
        truncate(normalizeOptional(command.reviewReason()), 64),
        Boolean.TRUE.equals(command.updateReviewRationale()),
        truncate(normalizeOptional(command.reviewRationale()), 2048),
        Boolean.TRUE.equals(command.updateReviewConfidence()),
        clampConfidence(command.reviewConfidence()));
  }

  private CandidateBatchReviewUpdateCommand normalize(CandidateBatchReviewUpdateCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("reviewStatus is required");
    }
    return new CandidateBatchReviewUpdateCommand(
        command.termIndexCandidateIds() == null ? List.of() : command.termIndexCandidateIds(),
        normalizeReviewStatus(command.reviewStatus()));
  }

  private CandidateUpdateCommand normalize(CandidateUpdateCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("candidate update is required");
    }
    return new CandidateUpdateCommand(
        normalizeOptional(command.definition()),
        normalizeOptional(command.rationale()),
        normalizeOptional(command.termType()),
        normalizeOptional(command.partOfSpeech()),
        normalizeOptional(command.enforcement()),
        command.doNotTranslate(),
        clampConfidence(command.confidence()),
        command.reviewStatus() == null ? null : normalizeReviewStatus(command.reviewStatus()),
        truncate(normalizeOptional(command.reviewReason()), 64),
        truncate(normalizeOptional(command.reviewRationale()), 2048),
        clampConfidence(command.reviewConfidence()));
  }

  private String normalizeReviewStatus(String reviewStatus) {
    String normalized = normalizeOptional(reviewStatus);
    if (normalized == null) {
      throw new IllegalArgumentException("reviewStatus is required");
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.STATUS_TO_REVIEW,
              TermIndexReview.STATUS_ACCEPTED,
              TermIndexReview.STATUS_REJECTED ->
          normalized;
      default -> throw new IllegalArgumentException("Unsupported reviewStatus: " + reviewStatus);
    };
  }

  private String normalizeReviewStatusFilter(String reviewStatusFilter) {
    String normalized = normalizeOptional(reviewStatusFilter);
    if (normalized == null) {
      return TermIndexReview.STATUS_FILTER_NON_REJECTED;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.STATUS_FILTER_ALL,
              TermIndexReview.STATUS_FILTER_NON_REJECTED,
              TermIndexReview.STATUS_TO_REVIEW,
              TermIndexReview.STATUS_ACCEPTED,
              TermIndexReview.STATUS_REJECTED ->
          normalized;
      default -> TermIndexReview.STATUS_FILTER_NON_REJECTED;
    };
  }

  private String normalizeReviewAuthorityFilter(String reviewAuthorityFilter) {
    return normalizeReviewAuthorityFilter(reviewAuthorityFilter, false);
  }

  private String normalizeReviewAuthorityFilter(
      String reviewAuthorityFilter, boolean defaultToAll) {
    String normalized = normalizeOptional(reviewAuthorityFilter);
    if (normalized == null) {
      return defaultToAll ? TermIndexReview.AUTHORITY_FILTER_ALL : null;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case TermIndexReview.AUTHORITY_FILTER_ALL,
              TermIndexReview.AUTHORITY_NONE,
              TermIndexReview.AUTHORITY_AI,
              TermIndexReview.AUTHORITY_HUMAN ->
          normalized;
      default -> null;
    };
  }

  private String normalizeEntrySort(String sortBy) {
    String normalized = normalizeOptional(sortBy);
    if (normalized == null) {
      return ENTRY_SORT_REVIEW_CONFIDENCE_DESC;
    }
    normalized = normalized.toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case ENTRY_SORT_HITS, ENTRY_SORT_REVIEW_CONFIDENCE_DESC, ENTRY_SORT_REVIEW_CONFIDENCE_ASC ->
          normalized;
      default -> ENTRY_SORT_REVIEW_CONFIDENCE_DESC;
    };
  }

  private int normalizeLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_LIMIT : limit;
    return Math.min(Math.max(1, normalized), MAX_LIMIT);
  }

  private int normalizeRecentRunLimit(Integer limit) {
    int normalized = limit == null ? DEFAULT_RECENT_RUN_LIMIT : limit;
    return Math.min(Math.max(1, normalized), MAX_RECENT_RUN_LIMIT);
  }

  private PageRequest pageRequest(int limit) {
    return PageRequest.of(0, limit);
  }

  private long nullToZero(Long value) {
    return value == null ? 0 : value;
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }

  private Integer clampConfidence(Integer value) {
    if (value == null) {
      return null;
    }
    return Math.min(100, Math.max(0, value));
  }

  private void applyHumanReview(
      TermIndexExtractedTerm entry,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      User currentUser) {
    entry.setReviewStatus(reviewStatus);
    entry.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
    entry.setReviewReason(reviewReason);
    entry.setReviewRationale(reviewRationale);
    entry.setReviewConfidence(reviewConfidence);
    entry.setReviewChangedAt(ZonedDateTime.now());
    entry.setReviewChangedByUser(currentUser);
  }

  private void applyHumanReview(
      TermIndexCandidate candidate,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      User currentUser) {
    candidate.setReviewStatus(reviewStatus);
    candidate.setReviewAuthority(TermIndexReview.AUTHORITY_HUMAN);
    candidate.setReviewReason(reviewReason);
    candidate.setReviewRationale(reviewRationale);
    candidate.setReviewConfidence(reviewConfidence);
    candidate.setReviewChangedAt(ZonedDateTime.now());
    candidate.setReviewChangedByUser(currentUser);
  }

  private User currentUserOrNull() {
    return userService.getCurrentUser().orElse(null);
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  public record EntrySearchCommand(
      List<Long> repositoryIds,
      String searchQuery,
      String extractionMethod,
      String reviewStatusFilter,
      String reviewAuthorityFilter,
      Long minOccurrences,
      Integer limit,
      ZonedDateTime lastOccurrenceAfter,
      ZonedDateTime lastOccurrenceBefore,
      ZonedDateTime reviewChangedAfter,
      ZonedDateTime reviewChangedBefore,
      String sortBy) {}

  public record CandidateSearchCommand(
      List<Long> repositoryIds,
      String searchQuery,
      String reviewStatusFilter,
      String reviewAuthorityFilter,
      Long minOccurrences,
      Integer limit,
      ZonedDateTime reviewChangedAfter,
      ZonedDateTime reviewChangedBefore) {}

  public record CandidateUpdateCommand(
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence) {}

  public record ReviewUpdateCommand(
      String reviewStatus, String reviewReason, String reviewRationale, Integer reviewConfidence) {}

  public record BatchReviewUpdateCommand(
      List<Long> termIndexExtractedTermIds,
      String reviewStatus,
      Boolean updateReviewReason,
      String reviewReason,
      Boolean updateReviewRationale,
      String reviewRationale,
      Boolean updateReviewConfidence,
      Integer reviewConfidence) {}

  public record BatchReviewUpdateView(int updatedEntryCount) {}

  public record CandidateBatchReviewUpdateCommand(
      List<Long> termIndexCandidateIds, String reviewStatus) {}

  public record CandidateBatchReviewUpdateView(int updatedCandidateCount) {}

  public record OccurrenceSearchCommand(
      List<Long> repositoryIds, String extractionMethod, Integer limit) {}

  public record EntrySearchView(List<EntrySummaryView> entries) {}

  public record CandidateSearchView(List<CandidateSummaryView> candidates) {}

  public record EntrySummaryView(
      Long id,
      Long termIndexCandidateId,
      String candidateDefinition,
      String candidateRationale,
      String candidateTermType,
      String candidatePartOfSpeech,
      String candidateEnforcement,
      Boolean candidateDoNotTranslate,
      Integer candidateConfidence,
      String candidateReviewStatus,
      String candidateReviewAuthority,
      String candidateReviewReason,
      String candidateReviewRationale,
      Integer candidateReviewConfidence,
      ZonedDateTime candidateReviewChangedAt,
      Long candidateReviewChangedByUserId,
      String candidateReviewChangedByUsername,
      String candidateReviewChangedByCommonName,
      String normalizedKey,
      String displayTerm,
      String sourceLocaleTag,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      ZonedDateTime reviewChangedAt,
      Long reviewChangedByUserId,
      String reviewChangedByUsername,
      String reviewChangedByCommonName,
      long occurrenceCount,
      int repositoryCount,
      ZonedDateTime lastOccurrenceAt) {}

  private record CandidateSummary(
      Long id,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      ZonedDateTime reviewChangedAt,
      Long reviewChangedByUserId,
      String reviewChangedByUsername,
      String reviewChangedByCommonName) {}

  public record CandidateSummaryView(
      Long id,
      Long termIndexExtractedTermId,
      String normalizedKey,
      String term,
      String label,
      String sourceLocaleTag,
      String metadataJson,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      ZonedDateTime reviewChangedAt,
      Long reviewChangedByUserId,
      String reviewChangedByUsername,
      String reviewChangedByCommonName,
      long occurrenceCount,
      int repositoryCount,
      ZonedDateTime lastOccurrenceAt,
      ZonedDateTime candidateCreatedDate) {}

  public record OccurrenceSearchView(List<OccurrenceView> occurrences) {}

  public record OccurrenceView(
      Long id,
      Long repositoryId,
      String repositoryName,
      Long assetId,
      String assetPath,
      Long tmTextUnitId,
      String textUnitName,
      String sourceText,
      String matchedText,
      Integer startIndex,
      Integer endIndex,
      String extractorId,
      String extractionMethod,
      Integer confidence,
      ZonedDateTime createdDate) {}

  public record StatusView(
      List<CursorView> cursors,
      List<RefreshRunView> recentRuns,
      List<JobView> recentJobs,
      List<String> extractionMethods) {}

  public record CursorView(
      Long repositoryId,
      String repositoryName,
      String status,
      ZonedDateTime lastProcessedCreatedAt,
      Long lastProcessedTmTextUnitId,
      ZonedDateTime lastSuccessfulScanAt,
      String leaseOwner,
      ZonedDateTime leaseExpiresAt,
      Long currentRefreshRunId,
      String errorMessage) {}

  public record RefreshRunView(
      Long id,
      String status,
      String requestedRepositoryIds,
      Long pollableTaskId,
      Long processedTextUnitCount,
      Long extractedTermCount,
      Long occurrenceCount,
      ZonedDateTime startedAt,
      ZonedDateTime completedAt,
      String errorMessage) {}

  public record JobView(
      Long id,
      String name,
      Long pollableTaskId,
      String status,
      String message,
      String errorMessage,
      ZonedDateTime createdDate,
      ZonedDateTime finishedDate) {}
}
