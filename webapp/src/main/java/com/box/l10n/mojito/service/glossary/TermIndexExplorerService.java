package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRefreshRun;
import com.box.l10n.mojito.entity.glossary.termindex.TermIndexRepositoryCursor;
import com.box.l10n.mojito.service.security.user.UserService;
import java.time.ZonedDateTime;
import java.util.List;
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

  private final UserService userService;
  private final TermIndexExtractedTermRepository termIndexExtractedTermRepository;
  private final TermIndexOccurrenceRepository termIndexOccurrenceRepository;
  private final TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository;
  private final TermIndexRefreshRunRepository termIndexRefreshRunRepository;

  public TermIndexExplorerService(
      UserService userService,
      TermIndexExtractedTermRepository termIndexExtractedTermRepository,
      TermIndexOccurrenceRepository termIndexOccurrenceRepository,
      TermIndexRepositoryCursorRepository termIndexRepositoryCursorRepository,
      TermIndexRefreshRunRepository termIndexRefreshRunRepository) {
    this.userService = Objects.requireNonNull(userService);
    this.termIndexExtractedTermRepository =
        Objects.requireNonNull(termIndexExtractedTermRepository);
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

    List<EntrySummaryView> entries =
        termIndexExtractedTermRepository
            .searchEntries(
                repositoryIdsEmpty,
                repositoryIds,
                normalizeOptional(normalized.searchQuery()),
                normalizeOptional(normalized.extractionMethod()),
                normalized.minOccurrences(),
                pageRequest(normalized.limit()))
            .stream()
            .map(this::toEntrySummaryView)
            .toList();
    return new EntrySearchView(entries);
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

  private EntrySummaryView toEntrySummaryView(TermIndexExtractedTermRepository.SearchRow row) {
    return new EntrySummaryView(
        row.getId(),
        row.getNormalizedKey(),
        row.getDisplayTerm(),
        row.getSourceLocaleTag(),
        nullToZero(row.getOccurrenceCount()),
        Math.toIntExact(nullToZero(row.getRepositoryCount())),
        row.getLastOccurrenceAt());
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
        run.getProcessedTextUnitCount(),
        run.getExtractedTermCount(),
        run.getOccurrenceCount(),
        run.getStartedAt(),
        run.getCompletedAt(),
        run.getErrorMessage());
  }

  private EntrySearchCommand normalize(EntrySearchCommand command) {
    if (command == null) {
      return new EntrySearchCommand(List.of(), null, null, 1L, DEFAULT_LIMIT);
    }
    return new EntrySearchCommand(
        normalizeRepositoryIds(command.repositoryIds()),
        command.searchQuery(),
        command.extractionMethod(),
        Math.max(1L, command.minOccurrences() == null ? 1L : command.minOccurrences()),
        normalizeLimit(command.limit()));
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

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  public record EntrySearchCommand(
      List<Long> repositoryIds,
      String searchQuery,
      String extractionMethod,
      Long minOccurrences,
      Integer limit) {}

  public record OccurrenceSearchCommand(
      List<Long> repositoryIds, String extractionMethod, Integer limit) {}

  public record EntrySearchView(List<EntrySummaryView> entries) {}

  public record EntrySummaryView(
      Long id,
      String normalizedKey,
      String displayTerm,
      String sourceLocaleTag,
      long occurrenceCount,
      int repositoryCount,
      ZonedDateTime lastOccurrenceAt) {}

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
      Long processedTextUnitCount,
      Long extractedTermCount,
      Long occurrenceCount,
      ZonedDateTime startedAt,
      ZonedDateTime completedAt,
      String errorMessage) {}
}
