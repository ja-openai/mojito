package com.box.l10n.mojito.rest.glossary;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.glossary.TermIndexExplorerService;
import com.box.l10n.mojito.service.glossary.TermIndexRefreshService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.security.user.UserService;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
  private final TermIndexRefreshService termIndexRefreshService;
  private final TermIndexExplorerService termIndexExplorerService;
  private final StructuredBlobStorage structuredBlobStorage;
  private final ObjectMapper objectMapper;
  private final TermIndexEntriesHybridProperties termIndexEntriesHybridProperties;
  private final AsyncTaskExecutor termIndexEntriesHybridExecutor;

  public TermIndexExplorerWS(
      UserService userService,
      TermIndexRefreshService termIndexRefreshService,
      TermIndexExplorerService termIndexExplorerService,
      StructuredBlobStorage structuredBlobStorage,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TermIndexEntriesHybridProperties termIndexEntriesHybridProperties,
      @Qualifier("termIndexEntriesHybridExecutor")
          AsyncTaskExecutor termIndexEntriesHybridExecutor) {
    this.userService = Objects.requireNonNull(userService);
    this.termIndexRefreshService = Objects.requireNonNull(termIndexRefreshService);
    this.termIndexExplorerService = Objects.requireNonNull(termIndexExplorerService);
    this.structuredBlobStorage = Objects.requireNonNull(structuredBlobStorage);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.termIndexEntriesHybridProperties =
        Objects.requireNonNull(termIndexEntriesHybridProperties);
    this.termIndexEntriesHybridExecutor = Objects.requireNonNull(termIndexEntriesHybridExecutor);
  }

  @PostMapping("/refresh")
  public StartRefreshResponse refresh(@RequestBody RefreshRequest request) {
    requireAdmin();
    List<Long> repositoryIds =
        request == null || request.repositoryIds() == null ? List.of() : request.repositoryIds();
    if (repositoryIds.stream().filter(Objects::nonNull).toList().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "At least one repository is required to refresh the term index");
    }

    try {
      PollableFuture<Void> pollableFuture =
          termIndexRefreshService.refreshAsync(
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
      @RequestParam(value = "minOccurrences", required = false) Long minOccurrences,
      @RequestParam(value = "limit", required = false) Integer limit) {
    requireAdmin();
    return termIndexExplorerService.searchEntries(
        new TermIndexExplorerService.EntrySearchCommand(
            repositoryIds, searchQuery, extractionMethod, minOccurrences, limit));
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

  public record RefreshRequest(List<Long> repositoryIds, Boolean fullRefresh, Integer batchSize) {}

  public record StartRefreshResponse(PollableTask pollableTask) {}

  public record TermIndexEntrySearchRequest(
      List<Long> repositoryIds,
      String search,
      String extractionMethod,
      Long minOccurrences,
      Integer limit) {}

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
      return new TermIndexExplorerService.EntrySearchCommand(List.of(), null, null, null, null);
    }
    return new TermIndexExplorerService.EntrySearchCommand(
        request.repositoryIds(),
        request.search(),
        request.extractionMethod(),
        request.minOccurrences(),
        request.limit());
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
