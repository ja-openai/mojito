package com.box.l10n.mojito.service.glossary;

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
import org.springframework.transaction.annotation.Transactional;

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

  public TermIndexExplorerService(
      UserService userService,
      TermIndexExtractedTermRepository termIndexExtractedTermRepository,
      TermIndexCandidateRepository termIndexCandidateRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository,
      TermIndexRefreshRunRepository termIndexRefreshRunRepository) {
    this.userService = Objects.requireNonNull(userService);
    this.termIndexExtractedTermRepository =
        Objects.requireNonNull(termIndexExtractedTermRepository);
    this.termIndexCandidateRepository = Objects.requireNonNull(termIndexCandidateRepository);
    this.termIndexOccurrenceRepository = Objects.requireNonNull(termIndexOccurrenceRepository);
    this.termIndexRepositoryCursorRepository =
        Objects.requireNonNull(termIndexRepositoryCursorRepository);
    this.termIndexRefreshRunRepository = Objects.requireNonNull(termIndexRefreshRunRepository);
  }

  @Transactional(readOnly = true)
  public EntrySearchView searchEntries(EntrySearchCommand command) {
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

  @Transactional
  public EntrySummaryView updateEntryReview(
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

  @Transactional
  public BatchReviewUpdateView updateEntryReviews(BatchReviewUpdateCommand command) {
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

  @Transactional(readOnly = true)
  public OccurrenceSearchView searchOccurrences(
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

  @Transactional(readOnly = true)
  public StatusView getStatus(List<Long> repositoryIds, Integer recentRunLimit) {
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
    List<String> extractionMethods = termIndexOccurrenceRepository.findDistinctExtractionMethods();
    return new StatusView(cursors, recentRuns, extractionMethods);
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
    String normalized = normalizeOptional(reviewAuthorityFilter);
    if (normalized == null) {
      return null;
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

  public record OccurrenceSearchCommand(
      List<Long> repositoryIds, String extractionMethod, Integer limit) {}

  public record EntrySearchView(List<EntrySummaryView> entries) {}

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
      List<CursorView> cursors, List<RefreshRunView> recentRuns, List<String> extractionMethods) {}

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
}
