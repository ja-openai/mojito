package com.box.l10n.mojito.rest.glossary;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.glossary.GlossaryRepository;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.TermIndexExplorerService;
import com.box.l10n.mojito.service.glossary.TermIndexRefreshService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.security.user.UserService;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/glossary-term-index")
public class TermIndexExplorerWS {

  private final UserService userService;
  private final GlossaryRepository glossaryRepository;
  private final TermIndexRefreshService termIndexRefreshService;
  private final TermIndexExplorerService termIndexExplorerService;
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;
  private final StructuredBlobStorage structuredBlobStorage;
  private final ObjectMapper objectMapper;
  private final TermIndexEntriesHybridProperties termIndexEntriesHybridProperties;
  private final AsyncTaskExecutor termIndexEntriesHybridExecutor;

  public TermIndexExplorerWS(
      UserService userService,
      GlossaryRepository glossaryRepository,
      TermIndexRefreshService termIndexRefreshService,
      TermIndexExplorerService termIndexExplorerService,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService,
      StructuredBlobStorage structuredBlobStorage,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TermIndexEntriesHybridProperties termIndexEntriesHybridProperties,
      @Qualifier("termIndexEntriesHybridExecutor")
          AsyncTaskExecutor termIndexEntriesHybridExecutor) {
    this.userService = Objects.requireNonNull(userService);
    this.glossaryRepository = Objects.requireNonNull(glossaryRepository);
    this.termIndexRefreshService = Objects.requireNonNull(termIndexRefreshService);
    this.termIndexExplorerService = Objects.requireNonNull(termIndexExplorerService);
    this.glossaryTermIndexCurationService =
        Objects.requireNonNull(glossaryTermIndexCurationService);
    this.structuredBlobStorage = Objects.requireNonNull(structuredBlobStorage);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.termIndexEntriesHybridProperties =
        Objects.requireNonNull(termIndexEntriesHybridProperties);
    this.termIndexEntriesHybridExecutor = Objects.requireNonNull(termIndexEntriesHybridExecutor);
  }

  @PostMapping("/refresh")
  public StartRefreshResponse refresh(@RequestBody RefreshRequest request) {
    requireAdmin();
    List<Long> repositoryIds = resolveRefreshRepositoryIds(request);
    if (repositoryIds.stream().filter(Objects::nonNull).toList().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At least one repository is required to refresh the term index");
    }

    try {
      PollableFuture<TermIndexRefreshService.RefreshResult> pollableFuture =
          termIndexRefreshService.scheduleRefresh(
              new TermIndexRefreshService.RefreshCommand(
                  repositoryIds, request.fullRefresh(), request.batchSize()));
      return new StartRefreshResponse(pollableFuture.getPollableTask());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/entries")
  public TermIndexExplorerService.EntrySearchView searchEntries(
      @RequestParam(value = "repositoryId", required = false) List<Long> repositoryIds,
      @RequestParam(value = "search", required = false) String searchQuery,
      @RequestParam(value = "extractionMethod", required = false) String extractionMethod,
      @RequestParam(value = "reviewStatus", required = false) String reviewStatusFilter,
      @RequestParam(value = "minOccurrences", required = false) Long minOccurrences,
      @RequestParam(value = "limit", required = false) Integer limit) {
    requireAdmin();
    return termIndexExplorerService.searchEntries(
        new TermIndexExplorerService.EntrySearchCommand(
            repositoryIds,
            searchQuery,
            extractionMethod,
            reviewStatusFilter,
            minOccurrences,
            limit));
  }

  @PostMapping("/entries/search-hybrid")
  public ResponseEntity<TermIndexEntrySearchHybridResponse> searchEntriesHybrid(
      @RequestBody TermIndexEntrySearchRequest request) {
    requireAdmin();
    UUID requestId = UUID.randomUUID();
    AtomicBoolean forceAsyncPersistence = new AtomicBoolean(false);
    long searchStartedAtNanos = System.nanoTime();

    Future<TermIndexExplorerService.EntrySearchView> searchFuture =
        termIndexEntriesHybridExecutor.submit(
            () -> {
              TermIndexExplorerService.EntrySearchView results;
              try {
                results = termIndexExplorerService.searchEntries(toEntrySearchCommand(request));
              } catch (Exception e) {
                if (forceAsyncPersistence.get()) {
                  persistTermIndexEntrySearchHybridResponse(
                      requestId,
                      new TermIndexEntrySearchHybridResponse(
                          null,
                          null,
                          new TermIndexEntrySearchHybridResponse.HybridSearchError(
                              e.getClass().getName(),
                              e.getMessage(),
                              Throwables.getStackTraceAsString(e),
                              isExpectedHybridSearchError(e))));
                }
                throw e;
              }

              long searchCompletedAtNanos = System.nanoTime();
              if (forceAsyncPersistence.get()
                  || searchCompletedAtNanos - searchStartedAtNanos
                      >= termIndexEntriesHybridProperties.convertToAsyncAfter().toNanos()) {
                persistTermIndexEntrySearchHybridResponse(
                    requestId, new TermIndexEntrySearchHybridResponse(results, null, null));
              }
              return results;
            });

    try {
      TermIndexExplorerService.EntrySearchView results =
          searchFuture.get(
              termIndexEntriesHybridProperties.convertToAsyncAfter().toNanos(),
              TimeUnit.NANOSECONDS);
      return ResponseEntity.ok(new TermIndexEntrySearchHybridResponse(results, null, null));
    } catch (TimeoutException e) {
      forceAsyncPersistence.set(true);
      return ResponseEntity.accepted()
          .body(
              new TermIndexEntrySearchHybridResponse(
                  null, buildTermIndexEntrySearchPollingToken(requestId), null));
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AccessDeniedException accessDeniedException) {
        throw accessDeniedException;
      }
      if (e.getCause() instanceof ResponseStatusException responseStatusException) {
        throw responseStatusException;
      }
      throw new UncheckedExecutionException(e);
    } catch (InterruptedException e) {
      searchFuture.cancel(true);
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @GetMapping("/entries/search-hybrid/results/{requestId}")
  public ResponseEntity<TermIndexEntrySearchHybridResponse> getSearchEntriesHybridResults(
      @PathVariable UUID requestId) {
    requireAdmin();
    Optional<String> storedResult =
        structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.TERM_INDEX_ENTRY_SEARCH_ASYNC, requestId.toString());

    if (storedResult.isEmpty()) {
      return ResponseEntity.accepted()
          .body(
              new TermIndexEntrySearchHybridResponse(
                  null, buildTermIndexEntrySearchPollingToken(requestId), null));
    }

    TermIndexEntrySearchHybridResponse response =
        objectMapper.readValueUnchecked(
            storedResult.get(), TermIndexEntrySearchHybridResponse.class);
    if (response.error() == null) {
      return ResponseEntity.ok(response);
    }
    if (response.error().expected()) {
      return ResponseEntity.badRequest().body(response);
    }
    return ResponseEntity.internalServerError().body(response);
  }

  @GetMapping("/entries/{termIndexEntryId}/occurrences")
  public TermIndexExplorerService.OccurrenceSearchView searchOccurrences(
      @PathVariable Long termIndexEntryId,
      @RequestParam(value = "repositoryId", required = false) List<Long> repositoryIds,
      @RequestParam(value = "extractionMethod", required = false) String extractionMethod,
      @RequestParam(value = "limit", required = false) Integer limit) {
    requireAdmin();
    return termIndexExplorerService.searchOccurrences(
        termIndexEntryId,
        new TermIndexExplorerService.OccurrenceSearchCommand(
            repositoryIds, extractionMethod, limit));
  }

  @PostMapping("/entries/{termIndexEntryId}/review")
  public TermIndexExplorerService.EntrySummaryView updateEntryReview(
      @PathVariable Long termIndexEntryId, @RequestBody TermIndexEntryReviewRequest request) {
    try {
      return termIndexExplorerService.updateEntryReview(
          termIndexEntryId,
          new TermIndexExplorerService.ReviewUpdateCommand(
              request != null ? request.reviewStatus() : null,
              request != null ? request.reviewReason() : null,
              request != null ? request.reviewRationale() : null,
              request != null ? request.reviewConfidence() : null));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping("/entries/review")
  public TermIndexExplorerService.BatchReviewUpdateView updateEntryReviews(
      @RequestBody TermIndexEntryBatchReviewRequest request) {
    try {
      return termIndexExplorerService.updateEntryReviews(
          new TermIndexExplorerService.BatchReviewUpdateCommand(
              request != null ? request.termIndexEntryIds() : null,
              request != null ? request.reviewStatus() : null,
              request != null ? request.reviewReason() : null,
              request != null ? request.reviewRationale() : null,
              request != null ? request.reviewConfidence() : null));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping("/entries/triage")
  public StartTriageExtractedTermsResponse triageEntries(
      @RequestBody TriageExtractedTermsRequest request) {
    requireAdmin();
    try {
      PollableFuture<GlossaryTermIndexCurationService.TriageExtractedTermsResult> pollableFuture =
          glossaryTermIndexCurationService.scheduleTriageExtractedTerms(
              new GlossaryTermIndexCurationService.TriageExtractedTermsCommand(
                  request != null ? request.termIndexEntryIds() : null,
                  request != null ? request.repositoryIds() : null,
                  request != null ? request.search() : null,
                  request != null ? request.extractionMethod() : null,
                  request != null ? request.reviewStatus() : null,
                  request != null ? request.minOccurrences() : null,
                  request != null ? request.limit() : null,
                  request != null ? request.overwriteHumanReview() : null));
      return new StartTriageExtractedTermsResponse(pollableFuture.getPollableTask());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping("/candidates/generate")
  public StartGenerateCandidatesFromEntriesResponse generateCandidatesFromEntries(
      @RequestBody GenerateCandidatesFromEntriesRequest request) {
    requireAdmin();
    try {
      PollableFuture<GlossaryTermIndexCurationService.GenerateCandidatesResult> pollableFuture =
          glossaryTermIndexCurationService.scheduleGenerateCandidatesFromExtractedTerms(
              new GlossaryTermIndexCurationService.GenerateCandidatesFromExtractedTermsCommand(
                  request != null ? request.termIndexEntryIds() : null,
                  request != null ? request.repositoryIds() : null,
                  request == null
                      ? null
                      : new GlossaryTermIndexCurationService.CandidateFieldOverrides(
                          request.definition(),
                          request.rationale(),
                          request.termType(),
                          request.partOfSpeech(),
                          request.enforcement(),
                          request.doNotTranslate(),
                          request.confidence(),
                          request.reviewStatus(),
                          request.reviewReason(),
                          request.reviewRationale(),
                          request.reviewConfidence())));
      return new StartGenerateCandidatesFromEntriesResponse(pollableFuture.getPollableTask());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/status")
  public TermIndexExplorerService.StatusView status(
      @RequestParam(value = "repositoryId", required = false) List<Long> repositoryIds,
      @RequestParam(value = "recentRunLimit", required = false) Integer recentRunLimit) {
    requireAdmin();
    return termIndexExplorerService.getStatus(repositoryIds, recentRunLimit);
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  private List<Long> resolveRefreshRepositoryIds(RefreshRequest request) {
    List<Long> repositoryIds =
        request == null || request.repositoryIds() == null ? List.of() : request.repositoryIds();
    if (!Boolean.TRUE.equals(request == null ? null : request.excludeGlossaryRepositories())) {
      return repositoryIds;
    }

    Set<Long> glossaryRepositoryIds = new HashSet<>(glossaryRepository.findBackingRepositoryIds());
    return repositoryIds.stream()
        .filter(repositoryId -> !glossaryRepositoryIds.contains(repositoryId))
        .toList();
  }

  public record RefreshRequest(
      List<Long> repositoryIds,
      Boolean fullRefresh,
      Integer batchSize,
      Boolean excludeGlossaryRepositories) {}

  public record StartRefreshResponse(PollableTask pollableTask) {}

  public record TermIndexEntrySearchRequest(
      List<Long> repositoryIds,
      String search,
      String extractionMethod,
      String reviewStatus,
      Long minOccurrences,
      Integer limit) {}

  public record TermIndexEntryReviewRequest(
      String reviewStatus, String reviewReason, String reviewRationale, Integer reviewConfidence) {}

  public record TermIndexEntryBatchReviewRequest(
      List<Long> termIndexEntryIds,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence) {}

  public record TriageExtractedTermsRequest(
      List<Long> termIndexEntryIds,
      List<Long> repositoryIds,
      String search,
      String extractionMethod,
      String reviewStatus,
      Long minOccurrences,
      Integer limit,
      Boolean overwriteHumanReview) {}

  public record StartTriageExtractedTermsResponse(PollableTask pollableTask) {}

  public record GenerateCandidatesFromEntriesRequest(
      List<Long> termIndexEntryIds,
      List<Long> repositoryIds,
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

  public record GenerateCandidatesFromEntriesResponse(
      int candidateCount,
      int createdCandidateCount,
      int updatedCandidateCount,
      List<GeneratedCandidateResponse> candidates) {}

  public record StartGenerateCandidatesFromEntriesResponse(PollableTask pollableTask) {}

  public record GeneratedCandidateResponse(
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      String term,
      String normalizedKey,
      String label,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence) {}

  public record TermIndexEntrySearchHybridResponse(
      TermIndexExplorerService.EntrySearchView results,
      PollingToken pollingToken,
      HybridSearchError error) {
    public record PollingToken(UUID requestId, long recommendedPollingDurationMillis) {}

    public record HybridSearchError(
        String type, String message, String stackTrace, boolean expected) {}
  }

  private TermIndexExplorerService.EntrySearchCommand toEntrySearchCommand(
      TermIndexEntrySearchRequest request) {
    if (request == null) {
      return new TermIndexExplorerService.EntrySearchCommand(
          List.of(), null, null, null, null, null);
    }
    return new TermIndexExplorerService.EntrySearchCommand(
        request.repositoryIds(),
        request.search(),
        request.extractionMethod(),
        request.reviewStatus(),
        request.minOccurrences(),
        request.limit());
  }

  private GeneratedCandidateResponse toGeneratedCandidateResponse(
      GlossaryTermIndexCurationService.SeededTermView candidate) {
    return new GeneratedCandidateResponse(
        candidate.termIndexCandidateId(),
        candidate.termIndexExtractedTermId(),
        candidate.term(),
        candidate.normalizedKey(),
        candidate.label(),
        candidate.definition(),
        candidate.rationale(),
        candidate.termType(),
        candidate.partOfSpeech(),
        candidate.enforcement(),
        candidate.doNotTranslate(),
        candidate.confidence());
  }

  private void persistTermIndexEntrySearchHybridResponse(
      UUID requestId, TermIndexEntrySearchHybridResponse response) {
    String payloadJson = objectMapper.writeValueAsStringUnchecked(response);
    structuredBlobStorage.put(
        StructuredBlobStorage.Prefix.TERM_INDEX_ENTRY_SEARCH_ASYNC,
        requestId.toString(),
        payloadJson,
        Retention.MIN_1_DAY);
  }

  private TermIndexEntrySearchHybridResponse.PollingToken buildTermIndexEntrySearchPollingToken(
      UUID requestId) {
    return new TermIndexEntrySearchHybridResponse.PollingToken(
        requestId, termIndexEntriesHybridProperties.recommendedPollingDuration().toMillis());
  }

  private boolean isExpectedHybridSearchError(Exception e) {
    return e instanceof AccessDeniedException
        || e instanceof IllegalArgumentException
        || e instanceof ResponseStatusException responseStatusException
            && responseStatusException.getStatusCode().is4xxClientError();
  }
}
