package com.box.l10n.mojito.rest.glossary;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.glossary.GlossaryImportExportService;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermIndexCurationService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.security.user.UserService;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/glossaries")
public class GlossaryWS {

  private static final String GLOSSARY_WORKSPACE_CANDIDATE_SOURCE_NAME = "glossary-workspace";
  private static final String GLOSSARY_WORKSPACE_CANDIDATE_SOURCE_TYPE = "HUMAN";

  private final GlossaryManagementService glossaryManagementService;
  private final GlossaryImportExportService glossaryImportExportService;
  private final GlossaryTermService glossaryTermService;
  private final GlossaryTermIndexCurationService glossaryTermIndexCurationService;
  private final GlossaryService glossaryService;
  private final StructuredBlobStorage structuredBlobStorage;
  private final ObjectMapper objectMapper;
  private final TermIndexEntriesHybridProperties termIndexEntriesHybridProperties;
  private final AsyncTaskExecutor termIndexEntriesHybridExecutor;
  private final UserService userService;

  public GlossaryWS(
      GlossaryManagementService glossaryManagementService,
      GlossaryImportExportService glossaryImportExportService,
      GlossaryTermService glossaryTermService,
      GlossaryTermIndexCurationService glossaryTermIndexCurationService,
      GlossaryService glossaryService,
      StructuredBlobStorage structuredBlobStorage,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TermIndexEntriesHybridProperties termIndexEntriesHybridProperties,
      @Qualifier("termIndexEntriesHybridExecutor") AsyncTaskExecutor termIndexEntriesHybridExecutor,
      UserService userService) {
    this.glossaryManagementService = glossaryManagementService;
    this.glossaryImportExportService = glossaryImportExportService;
    this.glossaryTermService = glossaryTermService;
    this.glossaryTermIndexCurationService = glossaryTermIndexCurationService;
    this.glossaryService = glossaryService;
    this.structuredBlobStorage = Objects.requireNonNull(structuredBlobStorage);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.termIndexEntriesHybridProperties =
        Objects.requireNonNull(termIndexEntriesHybridProperties);
    this.termIndexEntriesHybridExecutor = Objects.requireNonNull(termIndexEntriesHybridExecutor);
    this.userService = Objects.requireNonNull(userService);
  }

  public record SearchGlossariesResponse(List<GlossarySummary> glossaries, long totalCount) {
    public record GlossarySummary(
        Long id,
        ZonedDateTime createdDate,
        ZonedDateTime lastModifiedDate,
        String name,
        String description,
        boolean enabled,
        int priority,
        String scopeMode,
        String assetPath,
        int repositoryCount,
        RepositoryRef backingRepository) {}
  }

  public record GlossaryResponse(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      String description,
      boolean enabled,
      int priority,
      String scopeMode,
      RepositoryRef backingRepository,
      String assetPath,
      List<String> localeTags,
      List<RepositoryRef> repositories,
      List<RepositoryRef> excludedRepositories) {}

  public record RepositoryRef(Long id, String name) {}

  public record UpsertGlossaryRequest(
      String name,
      String description,
      Boolean enabled,
      Integer priority,
      String scopeMode,
      List<String> localeTags,
      List<Long> repositoryIds,
      List<Long> excludedRepositoryIds,
      String backingRepositoryName) {}

  public record MatchGlossaryTermsRequest(
      Long repositoryId,
      String repositoryName,
      String glossaryName,
      String localeTag,
      String sourceText,
      Long excludeTmTextUnitId) {}

  public record ImportGlossaryRequest(String format, String content) {}

  public record ImportGlossaryResponse(
      int createdTermCount,
      int updatedTermCount,
      int createdTranslationCount,
      int updatedTranslationCount) {}

  public record SearchGlossaryTermsResponse(
      List<GlossaryTermResponse> terms, long totalCount, List<String> localeTags) {}

  public record SearchTermIndexSuggestionsResponse(
      List<TermIndexSuggestionResponse> suggestions, long totalCount) {}

  public record SearchTermIndexSuggestionsRequest(
      String search,
      Integer limit,
      Boolean useAi,
      Boolean includeReviewed,
      String reviewStatusFilter,
      String glossaryPresenceFilter) {}

  public record SearchTermIndexSuggestionsHybridResponse(
      SearchTermIndexSuggestionsResponse results,
      PollingToken pollingToken,
      HybridSearchError error) {
    public record PollingToken(UUID requestId, long recommendedPollingDurationMillis) {}

    public record HybridSearchError(
        String type, String message, String stackTrace, boolean expected) {}
  }

  public record TermIndexSuggestionResponse(
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      String normalizedKey,
      String term,
      String label,
      String sourceLocaleTag,
      long occurrenceCount,
      int repositoryCount,
      int sourceCount,
      int extractedTermMatchCount,
      int confidence,
      String definition,
      String rationale,
      String suggestedTermType,
      String suggestedProvenance,
      String suggestedPartOfSpeech,
      String suggestedEnforcement,
      boolean suggestedDoNotTranslate,
      List<TermIndexOccurrenceResponse> examples,
      List<TermIndexCandidateSourceResponse> sources,
      ZonedDateTime lastSignalAt,
      String candidateReviewStatus,
      String candidateReviewAuthority,
      String candidateReviewReason,
      String candidateReviewRationale,
      Integer candidateReviewConfidence,
      String reviewStatus,
      String glossaryPresence,
      String selectionMethod) {}

  public record TermIndexOccurrenceResponse(
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
      String extractionMethod,
      Integer confidence) {}

  public record TermIndexCandidateSourceResponse(
      Long id,
      String sourceType,
      String sourceName,
      String sourceExternalId,
      Integer confidence,
      String metadataJson,
      ZonedDateTime createdDate) {}

  public record SeedTermIndexCandidatesRequest(List<SeedTermIndexCandidateRequest> candidates) {}

  public record GenerateTermIndexCandidatesRequest(
      String search, String extractionMethod, Long minOccurrences, Integer limit) {}

  public record ImportTermIndexCandidatesRequest(String format, String content) {}

  public record CandidateReviewRequest(
      String reviewStatus, String reviewReason, String reviewRationale, Integer reviewConfidence) {}

  public record CandidateReviewResponse(
      Long termIndexCandidateId,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence) {}

  public record SeedTermIndexCandidateRequest(
      String term,
      String sourceLocaleTag,
      String sourceType,
      String sourceName,
      String sourceExternalId,
      Integer confidence,
      String label,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      String reviewStatus,
      String reviewAuthority,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence,
      Map<String, Object> metadata) {}

  public record SeedTermIndexCandidatesResponse(
      int candidateCount,
      int createdCandidateCount,
      int updatedCandidateCount,
      List<SeededTermIndexCandidateResponse> candidates) {}

  public record SeededTermIndexCandidateResponse(
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      String term,
      String normalizedKey,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate,
      Integer confidence) {}

  public record GlossaryWorkspaceSummaryResponse(
      long totalTerms,
      long approvedTermCount,
      long candidateTermCount,
      long deprecatedTermCount,
      long rejectedTermCount,
      long doNotTranslateTermCount,
      long termsWithEvidenceCount,
      long termsMissingAnyTranslationCount,
      long missingTranslationCount,
      long fullyTranslatedTermCount,
      long publishReadyTermCount,
      boolean truncated) {}

  public record GlossaryTermResponse(
      Long metadataId,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      Long tmTextUnitId,
      String termKey,
      String source,
      String sourceComment,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      boolean caseSensitive,
      boolean doNotTranslate,
      Long termIndexCandidateId,
      Long termIndexExtractedTermId,
      List<GlossaryTermTranslationResponse> translations,
      List<GlossaryTermEvidenceResponse> evidence) {}

  public record GlossaryTermTranslationResponse(
      String localeTag, String target, String targetComment, String status) {}

  public record GlossaryTermEvidenceResponse(
      Long id,
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight,
      Integer sortOrder) {}

  public record UpsertGlossaryTermRequest(
      String termKey,
      String source,
      String sourceComment,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      Boolean caseSensitive,
      Boolean doNotTranslate,
      Boolean replaceTerm,
      Boolean copyTranslationsOnReplace,
      String copyTranslationStatus,
      List<UpsertGlossaryTermTranslationRequest> translations,
      List<UpsertGlossaryTermEvidenceRequest> evidence) {}

  public record UpsertGlossaryTermTranslationRequest(
      String localeTag, String target, String targetComment) {}

  public record UpsertGlossaryTermEvidenceRequest(
      String evidenceType,
      String caption,
      String imageKey,
      Long tmTextUnitId,
      Integer cropX,
      Integer cropY,
      Integer cropWidth,
      Integer cropHeight) {}

  public record AcceptTermIndexSuggestionRequest(
      String termKey,
      String source,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      Boolean caseSensitive,
      Boolean doNotTranslate,
      Integer confidence,
      String rationale,
      List<UpsertGlossaryTermEvidenceRequest> evidence) {}

  public record IgnoreTermIndexSuggestionRequest(String reason) {}

  public record BatchUpdateGlossaryTermsRequest(
      List<Long> tmTextUnitIds,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      Boolean caseSensitive,
      Boolean doNotTranslate) {}

  public record BatchUpdateGlossaryTermsResponse(int updatedTermCount) {}

  public record ExtractGlossaryTermsRequest(
      List<Long> repositoryIds, Integer limit, Integer minOccurrences, Integer scanLimit) {}

  public record ExtractGlossaryTermsResponse(List<ExtractedGlossaryCandidateResponse> candidates) {
    public record ExtractedGlossaryCandidateResponse(
        String term,
        int occurrenceCount,
        int repositoryCount,
        List<String> repositories,
        List<String> sampleSources,
        String suggestedTermType,
        String suggestedProvenance,
        boolean existingInGlossary,
        int confidence,
        String definition,
        String rationale,
        String suggestedPartOfSpeech,
        String suggestedEnforcement,
        boolean suggestedDoNotTranslate,
        String extractionMethod) {}
  }

  public record StartGlossaryExtractionResponse(PollableTask pollableTask) {}

  public record SubmitGlossaryTranslationProposalRequest(
      String localeTag, String target, String targetComment, String note) {}

  public record SearchGlossaryTranslationProposalsResponse(
      List<GlossaryTranslationProposalResponse> proposals, long totalCount) {}

  public record GlossaryTranslationProposalResponse(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      Long tmTextUnitId,
      String source,
      String localeTag,
      String proposedTarget,
      String proposedTargetComment,
      String note,
      String status,
      String reviewerNote) {}

  public record DecideGlossaryTranslationProposalRequest(String status, String reviewerNote) {}

  public record MatchGlossaryTermsResponse(List<MatchedGlossaryTermResponse> matchedTerms) {
    public record MatchedGlossaryTermResponse(
        Long glossaryId,
        String glossaryName,
        Long tmTextUnitId,
        String source,
        String comment,
        String definition,
        String partOfSpeech,
        String termType,
        String enforcement,
        String status,
        String provenance,
        String target,
        String targetComment,
        boolean doNotTranslate,
        boolean caseSensitive,
        String matchType,
        int startIndex,
        int endIndex,
        String matchedText,
        List<GlossaryTermEvidenceResponse> evidence) {}
  }

  @GetMapping
  public SearchGlossariesResponse searchGlossaries(
      @RequestParam(name = "search", required = false) String searchQuery,
      @RequestParam(name = "enabled", required = false) Boolean enabled,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      GlossaryManagementService.SearchGlossariesView view =
          glossaryManagementService.searchGlossaries(searchQuery, enabled, limit);
      return new SearchGlossariesResponse(
          view.glossaries().stream().map(this::toSummaryResponse).toList(), view.totalCount());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}")
  public GlossaryResponse getGlossary(@PathVariable Long glossaryId) {
    try {
      return toDetailResponse(glossaryManagementService.getGlossary(glossaryId));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public GlossaryResponse createGlossary(@RequestBody UpsertGlossaryRequest request) {
    try {
      return toDetailResponse(
          glossaryManagementService.createGlossary(
              request != null ? request.name() : null,
              request != null ? request.description() : null,
              request != null ? request.enabled() : null,
              request != null ? request.priority() : null,
              request != null ? request.scopeMode() : null,
              request != null ? request.localeTags() : List.of(),
              request != null ? request.repositoryIds() : List.of(),
              request != null ? request.excludedRepositoryIds() : List.of()));
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @PutMapping("/{glossaryId}")
  public GlossaryResponse updateGlossary(
      @PathVariable Long glossaryId, @RequestBody UpsertGlossaryRequest request) {
    try {
      return toDetailResponse(
          glossaryManagementService.updateGlossary(
              glossaryId,
              request != null ? request.name() : null,
              request != null ? request.description() : null,
              request != null ? request.enabled() : null,
              request != null ? request.priority() : null,
              request != null ? request.scopeMode() : null,
              request != null ? request.localeTags() : List.of(),
              request != null ? request.repositoryIds() : List.of(),
              request != null ? request.excludedRepositoryIds() : List.of(),
              request != null ? request.backingRepositoryName() : null));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @DeleteMapping("/{glossaryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGlossary(@PathVariable Long glossaryId) {
    try {
      glossaryManagementService.deleteGlossary(glossaryId);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
    }
  }

  @PostMapping("/match")
  public MatchGlossaryTermsResponse matchGlossaryTerms(
      @RequestBody MatchGlossaryTermsRequest request) {
    try {
      return new MatchGlossaryTermsResponse(
          glossaryService
              .findMatchesForRepositoryAndLocale(
                  request != null ? request.repositoryId() : null,
                  request != null ? request.repositoryName() : null,
                  request != null ? request.glossaryName() : null,
                  request != null ? request.localeTag() : null,
                  request != null ? request.sourceText() : null,
                  request != null ? request.excludeTmTextUnitId() : null)
              .stream()
              .map(this::toMatchedTermResponse)
              .toList());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}/export")
  public ResponseEntity<byte[]> exportGlossary(
      @PathVariable Long glossaryId,
      @RequestParam(name = "format", defaultValue = "json") String format) {
    try {
      GlossaryImportExportService.ExportPayload payload =
          glossaryImportExportService.exportGlossary(glossaryId, format);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\"" + payload.filename() + "\"")
          .body(payload.content().getBytes(StandardCharsets.UTF_8));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/import")
  public ImportGlossaryResponse importGlossary(
      @PathVariable Long glossaryId, @RequestBody ImportGlossaryRequest request) {
    try {
      GlossaryImportExportService.ImportResult result =
          glossaryImportExportService.importGlossary(
              glossaryId,
              request != null ? request.format() : null,
              request != null ? request.content() : null);
      return new ImportGlossaryResponse(
          result.createdTermCount(),
          result.updatedTermCount(),
          result.createdTranslationCount(),
          result.updatedTranslationCount());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}/term-index-suggestions")
  public SearchTermIndexSuggestionsResponse searchTermIndexSuggestions(
      @PathVariable Long glossaryId,
      @RequestParam(name = "search", required = false) String searchQuery,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "useAi", required = false) Boolean useAi,
      @RequestParam(name = "includeReviewed", required = false) Boolean includeReviewed,
      @RequestParam(name = "reviewStatusFilter", required = false) String reviewStatusFilter,
      @RequestParam(name = "glossaryPresenceFilter", required = false)
          String glossaryPresenceFilter) {
    try {
      GlossaryTermIndexCurationService.SuggestionSearchView view =
          glossaryTermIndexCurationService.searchSuggestions(
              glossaryId,
              new GlossaryTermIndexCurationService.SuggestionSearchCommand(
                  searchQuery,
                  limit,
                  useAi,
                  includeReviewed,
                  reviewStatusFilter,
                  glossaryPresenceFilter));
      return new SearchTermIndexSuggestionsResponse(
          view.suggestions().stream().map(this::toTermIndexSuggestionResponse).toList(),
          view.totalCount());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/term-index-suggestions/search-hybrid")
  public ResponseEntity<SearchTermIndexSuggestionsHybridResponse> searchTermIndexSuggestionsHybrid(
      @PathVariable Long glossaryId, @RequestBody SearchTermIndexSuggestionsRequest request) {
    requireTermManager();
    UUID requestId = UUID.randomUUID();
    AtomicBoolean forceAsyncPersistence = new AtomicBoolean(false);
    long searchStartedAtNanos = System.nanoTime();

    Future<SearchTermIndexSuggestionsResponse> searchFuture =
        termIndexEntriesHybridExecutor.submit(
            () -> {
              SearchTermIndexSuggestionsResponse results;
              try {
                results =
                    searchTermIndexSuggestions(
                        glossaryId,
                        request != null ? request.search() : null,
                        request != null ? request.limit() : null,
                        request != null ? request.useAi() : null,
                        request != null ? request.includeReviewed() : null,
                        request != null ? request.reviewStatusFilter() : null,
                        request != null ? request.glossaryPresenceFilter() : null);
              } catch (Exception e) {
                if (forceAsyncPersistence.get()) {
                  persistTermIndexSuggestionSearchHybridResponse(
                      glossaryId,
                      requestId,
                      new SearchTermIndexSuggestionsHybridResponse(
                          null,
                          null,
                          new SearchTermIndexSuggestionsHybridResponse.HybridSearchError(
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
                persistTermIndexSuggestionSearchHybridResponse(
                    glossaryId,
                    requestId,
                    new SearchTermIndexSuggestionsHybridResponse(results, null, null));
              }
              return results;
            });

    try {
      SearchTermIndexSuggestionsResponse results =
          searchFuture.get(
              termIndexEntriesHybridProperties.convertToAsyncAfter().toNanos(),
              TimeUnit.NANOSECONDS);
      return ResponseEntity.ok(new SearchTermIndexSuggestionsHybridResponse(results, null, null));
    } catch (TimeoutException e) {
      forceAsyncPersistence.set(true);
      return ResponseEntity.accepted()
          .body(
              new SearchTermIndexSuggestionsHybridResponse(
                  null, buildTermIndexSuggestionSearchPollingToken(requestId), null));
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

  @GetMapping("/{glossaryId}/term-index-suggestions/search-hybrid/results/{requestId}")
  public ResponseEntity<SearchTermIndexSuggestionsHybridResponse>
      getSearchTermIndexSuggestionsHybridResults(
          @PathVariable Long glossaryId, @PathVariable UUID requestId) {
    requireTermManager();
    Optional<String> storedResult =
        structuredBlobStorage.getString(
            StructuredBlobStorage.Prefix.GLOSSARY_TERM_INDEX_SUGGESTION_SEARCH_ASYNC,
            responseStorageNameForTermIndexSuggestionSearch(glossaryId, requestId));

    if (storedResult.isEmpty()) {
      return ResponseEntity.accepted()
          .body(
              new SearchTermIndexSuggestionsHybridResponse(
                  null, buildTermIndexSuggestionSearchPollingToken(requestId), null));
    }

    SearchTermIndexSuggestionsHybridResponse response =
        objectMapper.readValueUnchecked(
            storedResult.get(), SearchTermIndexSuggestionsHybridResponse.class);
    if (response.error() == null) {
      return ResponseEntity.ok(response);
    }
    if (response.error().expected()) {
      return ResponseEntity.badRequest().body(response);
    }
    return ResponseEntity.internalServerError().body(response);
  }

  @PostMapping("/{glossaryId}/term-index-suggestions/{termIndexCandidateId}/accept")
  public GlossaryTermResponse acceptTermIndexSuggestion(
      @PathVariable Long glossaryId,
      @PathVariable Long termIndexCandidateId,
      @RequestBody AcceptTermIndexSuggestionRequest request) {
    try {
      GlossaryTermService.TermView term =
          glossaryTermIndexCurationService.acceptSuggestion(
              glossaryId,
              termIndexCandidateId,
              new GlossaryTermIndexCurationService.AcceptSuggestionCommand(
                  request != null ? request.termKey() : null,
                  request != null ? request.source() : null,
                  request != null ? request.definition() : null,
                  request != null ? request.partOfSpeech() : null,
                  request != null ? request.termType() : null,
                  request != null ? request.enforcement() : null,
                  request != null ? request.status() : null,
                  request != null ? request.caseSensitive() : null,
                  request != null ? request.doNotTranslate() : null,
                  request != null ? request.confidence() : null,
                  request != null ? request.rationale() : null,
                  request != null ? toSuggestionEvidenceInputs(request.evidence()) : List.of()));
      return toGlossaryTermResponse(term);
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/term-index-suggestions/{termIndexCandidateId}/ignore")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void ignoreTermIndexSuggestion(
      @PathVariable Long glossaryId,
      @PathVariable Long termIndexCandidateId,
      @RequestBody IgnoreTermIndexSuggestionRequest request) {
    try {
      glossaryTermIndexCurationService.ignoreSuggestion(
          glossaryId,
          termIndexCandidateId,
          new GlossaryTermIndexCurationService.IgnoreSuggestionCommand(
              request != null ? request.reason() : null));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/term-index-suggestions/{termIndexCandidateId}/review")
  public CandidateReviewResponse updateTermIndexSuggestionReview(
      @PathVariable Long glossaryId,
      @PathVariable Long termIndexCandidateId,
      @RequestBody CandidateReviewRequest request) {
    try {
      GlossaryTermIndexCurationService.CandidateReviewView review =
          glossaryTermIndexCurationService.updateCandidateReview(
              termIndexCandidateId,
              new GlossaryTermIndexCurationService.CandidateReviewCommand(
                  request != null ? request.reviewStatus() : null,
                  request != null ? request.reviewReason() : null,
                  request != null ? request.reviewRationale() : null,
                  request != null ? request.reviewConfidence() : null));
      return new CandidateReviewResponse(
          review.termIndexCandidateId(),
          review.reviewStatus(),
          review.reviewAuthority(),
          review.reviewReason(),
          review.reviewRationale(),
          review.reviewConfidence());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @PostMapping("/{glossaryId}/term-index-candidates")
  public SeedTermIndexCandidatesResponse seedTermIndexCandidates(
      @PathVariable Long glossaryId, @RequestBody SeedTermIndexCandidatesRequest request) {
    try {
      GlossaryTermIndexCurationService.SeedResult result =
          glossaryTermIndexCurationService.seedTermsForGlossary(
              glossaryId,
              new GlossaryTermIndexCurationService.SeedCommand(
                  request == null || request.candidates() == null
                      ? null
                      : request.candidates().stream()
                          .map(candidate -> toSeedTermInput(glossaryId, candidate))
                          .toList()));
      return toSeedTermIndexCandidatesResponse(result);
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/term-index-candidates/generate")
  public SeedTermIndexCandidatesResponse generateTermIndexCandidates(
      @PathVariable Long glossaryId, @RequestBody GenerateTermIndexCandidatesRequest request) {
    try {
      GlossaryTermIndexCurationService.GenerateCandidatesResult result =
          glossaryTermIndexCurationService.generateCandidatesForGlossary(
              glossaryId,
              new GlossaryTermIndexCurationService.GenerateCandidatesCommand(
                  request == null ? null : request.search(),
                  request == null ? null : request.extractionMethod(),
                  request == null ? null : request.minOccurrences(),
                  request == null ? null : request.limit()));
      return new SeedTermIndexCandidatesResponse(
          result.candidateCount(),
          result.createdCandidateCount(),
          result.updatedCandidateCount(),
          result.candidates().stream().map(this::toSeededTermIndexCandidateResponse).toList());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/term-index-candidates/import")
  public SeedTermIndexCandidatesResponse importTermIndexCandidates(
      @PathVariable Long glossaryId, @RequestBody ImportTermIndexCandidatesRequest request) {
    try {
      GlossaryTermIndexCurationService.SeedResult result =
          glossaryTermIndexCurationService.importCandidatesForGlossary(
              glossaryId,
              new GlossaryTermIndexCurationService.CandidateImportCommand(
                  request == null ? null : request.format(),
                  request == null ? null : request.content()));
      return toSeedTermIndexCandidatesResponse(result);
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}/term-index-candidates/export")
  public ResponseEntity<byte[]> exportTermIndexCandidates(
      @PathVariable Long glossaryId,
      @RequestParam(name = "format", required = false) String format,
      @RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      GlossaryTermIndexCurationService.CandidateExportResult export =
          glossaryTermIndexCurationService.exportCandidatesForGlossary(
              glossaryId,
              new GlossaryTermIndexCurationService.CandidateExportCommand(search, limit, format));
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .header(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
          .body(export.content().getBytes(StandardCharsets.UTF_8));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}/terms")
  public SearchGlossaryTermsResponse searchGlossaryTerms(
      @PathVariable Long glossaryId,
      @RequestParam(name = "search", required = false) String searchQuery,
      @RequestParam(name = "locale", required = false) List<String> localeTags,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      GlossaryTermService.SearchTermsView view =
          glossaryTermService.searchTerms(glossaryId, searchQuery, localeTags, limit);
      return new SearchGlossaryTermsResponse(
          view.terms().stream().map(this::toGlossaryTermResponse).toList(),
          view.totalCount(),
          view.localeTags());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/terms")
  @ResponseStatus(HttpStatus.CREATED)
  public GlossaryTermResponse createGlossaryTerm(
      @PathVariable Long glossaryId, @RequestBody UpsertGlossaryTermRequest request) {
    return upsertGlossaryTerm(glossaryId, null, request);
  }

  @PutMapping("/{glossaryId}/terms/{tmTextUnitId}")
  public GlossaryTermResponse updateGlossaryTerm(
      @PathVariable Long glossaryId,
      @PathVariable Long tmTextUnitId,
      @RequestBody UpsertGlossaryTermRequest request) {
    return upsertGlossaryTerm(glossaryId, tmTextUnitId, request);
  }

  @DeleteMapping("/{glossaryId}/terms/{tmTextUnitId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteGlossaryTerm(@PathVariable Long glossaryId, @PathVariable Long tmTextUnitId) {
    try {
      glossaryTermService.deleteTerm(glossaryId, tmTextUnitId);
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/terms/batch")
  public BatchUpdateGlossaryTermsResponse batchUpdateGlossaryTerms(
      @PathVariable Long glossaryId, @RequestBody BatchUpdateGlossaryTermsRequest request) {
    try {
      GlossaryTermService.BatchUpdateResult result =
          glossaryTermService.batchUpdateTerms(
              glossaryId,
              new GlossaryTermService.BatchUpdateCommand(
                  request != null ? request.tmTextUnitIds() : List.of(),
                  request != null ? request.partOfSpeech() : null,
                  request != null ? request.termType() : null,
                  request != null ? request.enforcement() : null,
                  request != null ? request.status() : null,
                  request != null ? request.provenance() : null,
                  request != null ? request.caseSensitive() : null,
                  request != null ? request.doNotTranslate() : null));
      return new BatchUpdateGlossaryTermsResponse(result.updatedTermCount());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/extract")
  public StartGlossaryExtractionResponse extractGlossaryTerms(
      @PathVariable Long glossaryId, @RequestBody ExtractGlossaryTermsRequest request) {
    try {
      PollableFuture<Void> pollableFuture =
          glossaryTermService.extractCandidatesAsync(
              glossaryId,
              new GlossaryTermService.ExtractionCommand(
                  request != null ? request.repositoryIds() : List.of(),
                  request != null ? request.limit() : null,
                  request != null ? request.minOccurrences() : null,
                  request != null ? request.scanLimit() : null),
              PollableTask.INJECT_CURRENT_TASK);
      return new StartGlossaryExtractionResponse(pollableFuture.getPollableTask());
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/terms/{tmTextUnitId}/proposals")
  @ResponseStatus(HttpStatus.CREATED)
  public GlossaryTranslationProposalResponse submitGlossaryTranslationProposal(
      @PathVariable Long glossaryId,
      @PathVariable Long tmTextUnitId,
      @RequestBody SubmitGlossaryTranslationProposalRequest request) {
    try {
      return toGlossaryTranslationProposalResponse(
          glossaryTermService.submitTranslationProposal(
              glossaryId,
              tmTextUnitId,
              new GlossaryTermService.TranslationProposalCommand(
                  request != null ? request.localeTag() : null,
                  request != null ? request.target() : null,
                  request != null ? request.targetComment() : null,
                  request != null ? request.note() : null)));
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}/proposals")
  public SearchGlossaryTranslationProposalsResponse searchGlossaryTranslationProposals(
      @PathVariable Long glossaryId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      GlossaryTermService.ProposalSearchView view =
          glossaryTermService.searchTranslationProposals(glossaryId, status, limit);
      return new SearchGlossaryTranslationProposalsResponse(
          view.proposals().stream().map(this::toGlossaryTranslationProposalResponse).toList(),
          view.totalCount());
    } catch (IllegalArgumentException ex) {
      HttpStatus responseStatus =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(responseStatus, ex.getMessage());
    }
  }

  @GetMapping("/{glossaryId}/workspace-summary")
  public GlossaryWorkspaceSummaryResponse getGlossaryWorkspaceSummary(
      @PathVariable Long glossaryId) {
    try {
      GlossaryTermService.WorkspaceSummaryView summary =
          glossaryTermService.getWorkspaceSummary(glossaryId);
      return new GlossaryWorkspaceSummaryResponse(
          summary.totalTerms(),
          summary.approvedTermCount(),
          summary.candidateTermCount(),
          summary.deprecatedTermCount(),
          summary.rejectedTermCount(),
          summary.doNotTranslateTermCount(),
          summary.termsWithEvidenceCount(),
          summary.termsMissingAnyTranslationCount(),
          summary.missingTranslationCount(),
          summary.fullyTranslatedTermCount(),
          summary.publishReadyTermCount(),
          summary.truncated());
    } catch (IllegalArgumentException ex) {
      HttpStatus responseStatus =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(responseStatus, ex.getMessage());
    }
  }

  @PostMapping("/{glossaryId}/proposals/{proposalId}/decision")
  public GlossaryTranslationProposalResponse decideGlossaryTranslationProposal(
      @PathVariable Long glossaryId,
      @PathVariable Long proposalId,
      @RequestBody DecideGlossaryTranslationProposalRequest request) {
    try {
      return toGlossaryTranslationProposalResponse(
          glossaryTermService.decideTranslationProposal(
              glossaryId,
              proposalId,
              new GlossaryTermService.TranslationProposalDecisionCommand(
                  request != null ? request.status() : null,
                  request != null ? request.reviewerNote() : null)));
    } catch (IllegalArgumentException ex) {
      HttpStatus responseStatus =
          ex.getMessage() != null
                  && (ex.getMessage().startsWith("Glossary not found:")
                      || ex.getMessage().startsWith("Glossary translation proposal not found:"))
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(responseStatus, ex.getMessage());
    }
  }

  private SearchGlossariesResponse.GlossarySummary toSummaryResponse(
      GlossaryManagementService.GlossarySummary summary) {
    return new SearchGlossariesResponse.GlossarySummary(
        summary.id(),
        summary.createdDate(),
        summary.lastModifiedDate(),
        summary.name(),
        summary.description(),
        summary.enabled(),
        summary.priority(),
        summary.scopeMode(),
        summary.assetPath(),
        summary.repositoryCount(),
        new RepositoryRef(summary.backingRepository().id(), summary.backingRepository().name()));
  }

  private GlossaryResponse toDetailResponse(GlossaryManagementService.GlossaryDetail detail) {
    return new GlossaryResponse(
        detail.id(),
        detail.createdDate(),
        detail.lastModifiedDate(),
        detail.name(),
        detail.description(),
        detail.enabled(),
        detail.priority(),
        detail.scopeMode(),
        new RepositoryRef(detail.backingRepository().id(), detail.backingRepository().name()),
        detail.assetPath(),
        detail.localeTags(),
        detail.repositories().stream()
            .map(repository -> new RepositoryRef(repository.id(), repository.name()))
            .toList(),
        detail.excludedRepositories().stream()
            .map(repository -> new RepositoryRef(repository.id(), repository.name()))
            .toList());
  }

  private GlossaryTermResponse upsertGlossaryTerm(
      Long glossaryId, Long tmTextUnitId, UpsertGlossaryTermRequest request) {
    try {
      GlossaryTermService.TermView term =
          glossaryTermService.upsertTerm(
              glossaryId,
              tmTextUnitId,
              new GlossaryTermService.TermUpsertCommand(
                  request != null ? request.termKey() : null,
                  request != null ? request.source() : null,
                  request != null ? request.sourceComment() : null,
                  request != null ? request.definition() : null,
                  request != null ? request.partOfSpeech() : null,
                  request != null ? request.termType() : null,
                  request != null ? request.enforcement() : null,
                  request != null ? request.status() : null,
                  request != null ? request.provenance() : null,
                  request != null ? request.caseSensitive() : null,
                  request != null ? request.doNotTranslate() : null,
                  request != null ? request.replaceTerm() : null,
                  request != null ? request.copyTranslationsOnReplace() : null,
                  request != null ? request.copyTranslationStatus() : null,
                  request != null ? toTranslationInputs(request.translations()) : List.of(),
                  request != null ? toEvidenceInputs(request.evidence()) : List.of()));
      return toGlossaryTermResponse(term);
    } catch (IllegalArgumentException ex) {
      HttpStatus status =
          ex.getMessage() != null && ex.getMessage().startsWith("Glossary not found:")
              ? HttpStatus.NOT_FOUND
              : HttpStatus.BAD_REQUEST;
      throw new ResponseStatusException(status, ex.getMessage());
    }
  }

  private List<GlossaryTermService.TranslationInput> toTranslationInputs(
      List<UpsertGlossaryTermTranslationRequest> translations) {
    if (translations == null || translations.isEmpty()) {
      return List.of();
    }
    return translations.stream()
        .map(
            translation ->
                new GlossaryTermService.TranslationInput(
                    translation.localeTag(), translation.target(), translation.targetComment()))
        .toList();
  }

  private List<GlossaryTermService.EvidenceInput> toEvidenceInputs(
      List<UpsertGlossaryTermEvidenceRequest> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return List.of();
    }
    return evidence.stream()
        .map(
            item ->
                new GlossaryTermService.EvidenceInput(
                    item.evidenceType(),
                    item.caption(),
                    item.imageKey(),
                    item.tmTextUnitId(),
                    item.cropX(),
                    item.cropY(),
                    item.cropWidth(),
                    item.cropHeight()))
        .toList();
  }

  private List<GlossaryTermIndexCurationService.AcceptSuggestionEvidenceInput>
      toSuggestionEvidenceInputs(List<UpsertGlossaryTermEvidenceRequest> evidence) {
    if (evidence == null || evidence.isEmpty()) {
      return List.of();
    }
    return evidence.stream()
        .map(
            item ->
                new GlossaryTermIndexCurationService.AcceptSuggestionEvidenceInput(
                    item.evidenceType(),
                    item.caption(),
                    item.imageKey(),
                    item.tmTextUnitId(),
                    item.cropX(),
                    item.cropY(),
                    item.cropWidth(),
                    item.cropHeight()))
        .toList();
  }

  private TermIndexSuggestionResponse toTermIndexSuggestionResponse(
      GlossaryTermIndexCurationService.SuggestionView suggestion) {
    return new TermIndexSuggestionResponse(
        suggestion.termIndexCandidateId(),
        suggestion.termIndexExtractedTermId(),
        suggestion.normalizedKey(),
        suggestion.term(),
        suggestion.label(),
        suggestion.sourceLocaleTag(),
        suggestion.occurrenceCount(),
        suggestion.repositoryCount(),
        suggestion.sourceCount(),
        suggestion.extractedTermMatchCount(),
        suggestion.confidence(),
        suggestion.definition(),
        suggestion.rationale(),
        suggestion.suggestedTermType(),
        suggestion.suggestedProvenance(),
        suggestion.suggestedPartOfSpeech(),
        suggestion.suggestedEnforcement(),
        suggestion.suggestedDoNotTranslate(),
        suggestion.examples().stream().map(this::toTermIndexOccurrenceResponse).toList(),
        suggestion.sources().stream().map(this::toTermIndexCandidateSourceResponse).toList(),
        suggestion.lastSignalAt(),
        suggestion.candidateReviewStatus(),
        suggestion.candidateReviewAuthority(),
        suggestion.candidateReviewReason(),
        suggestion.candidateReviewRationale(),
        suggestion.candidateReviewConfidence(),
        suggestion.reviewStatus(),
        suggestion.glossaryPresence(),
        suggestion.selectionMethod());
  }

  private TermIndexOccurrenceResponse toTermIndexOccurrenceResponse(
      GlossaryTermIndexCurationService.OccurrenceView occurrence) {
    return new TermIndexOccurrenceResponse(
        occurrence.id(),
        occurrence.repositoryId(),
        occurrence.repositoryName(),
        occurrence.assetId(),
        occurrence.assetPath(),
        occurrence.tmTextUnitId(),
        occurrence.textUnitName(),
        occurrence.sourceText(),
        occurrence.matchedText(),
        occurrence.startIndex(),
        occurrence.endIndex(),
        occurrence.extractionMethod(),
        occurrence.confidence());
  }

  private TermIndexCandidateSourceResponse toTermIndexCandidateSourceResponse(
      GlossaryTermIndexCurationService.CandidateSourceView source) {
    return new TermIndexCandidateSourceResponse(
        source.id(),
        source.sourceType(),
        source.sourceName(),
        source.sourceExternalId(),
        source.confidence(),
        source.metadataJson(),
        source.createdDate());
  }

  private SeedTermIndexCandidatesResponse toSeedTermIndexCandidatesResponse(
      GlossaryTermIndexCurationService.SeedResult result) {
    return new SeedTermIndexCandidatesResponse(
        result.termCount(),
        result.createdCandidateCount(),
        result.updatedCandidateCount(),
        result.terms().stream().map(this::toSeededTermIndexCandidateResponse).toList());
  }

  private SeededTermIndexCandidateResponse toSeededTermIndexCandidateResponse(
      GlossaryTermIndexCurationService.SeededTermView term) {
    return new SeededTermIndexCandidateResponse(
        term.termIndexCandidateId(),
        term.termIndexExtractedTermId(),
        term.term(),
        term.normalizedKey(),
        term.definition(),
        term.rationale(),
        term.termType(),
        term.partOfSpeech(),
        term.enforcement(),
        term.doNotTranslate(),
        term.confidence());
  }

  private GlossaryTermIndexCurationService.SeedTermInput toSeedTermInput(
      Long glossaryId, SeedTermIndexCandidateRequest candidate) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (candidate != null && candidate.metadata() != null) {
      metadata.putAll(candidate.metadata());
    }
    metadata.putIfAbsent("glossaryId", glossaryId);
    metadata.putIfAbsent("submittedFrom", GLOSSARY_WORKSPACE_CANDIDATE_SOURCE_NAME);

    return new GlossaryTermIndexCurationService.SeedTermInput(
        candidate == null ? null : candidate.term(),
        candidate == null ? null : candidate.sourceLocaleTag(),
        candidate == null || candidate.sourceType() == null
            ? GLOSSARY_WORKSPACE_CANDIDATE_SOURCE_TYPE
            : candidate.sourceType(),
        candidate == null || candidate.sourceName() == null
            ? GLOSSARY_WORKSPACE_CANDIDATE_SOURCE_NAME
            : candidate.sourceName(),
        candidate == null ? null : candidate.sourceExternalId(),
        candidate == null ? null : candidate.confidence(),
        candidate == null ? null : candidate.label(),
        candidate == null ? null : candidate.definition(),
        candidate == null ? null : candidate.rationale(),
        candidate == null ? null : candidate.termType(),
        candidate == null ? null : candidate.partOfSpeech(),
        candidate == null ? null : candidate.enforcement(),
        candidate == null ? null : candidate.doNotTranslate(),
        candidate == null ? null : candidate.reviewStatus(),
        candidate == null ? null : candidate.reviewAuthority(),
        candidate == null ? null : candidate.reviewReason(),
        candidate == null ? null : candidate.reviewRationale(),
        candidate == null ? null : candidate.reviewConfidence(),
        metadata);
  }

  private GlossaryTermResponse toGlossaryTermResponse(GlossaryTermService.TermView term) {
    return new GlossaryTermResponse(
        term.metadataId(),
        term.createdDate(),
        term.lastModifiedDate(),
        term.tmTextUnitId(),
        term.termKey(),
        term.source(),
        term.sourceComment(),
        term.definition(),
        term.partOfSpeech(),
        term.termType(),
        term.enforcement(),
        term.status(),
        term.provenance(),
        term.caseSensitive(),
        term.doNotTranslate(),
        term.termIndexCandidateId(),
        term.termIndexExtractedTermId(),
        term.translations().stream().map(this::toGlossaryTermTranslationResponse).toList(),
        term.evidence().stream().map(this::toGlossaryTermEvidenceResponse).toList());
  }

  private GlossaryTermTranslationResponse toGlossaryTermTranslationResponse(
      GlossaryTermService.TermTranslationView translation) {
    return new GlossaryTermTranslationResponse(
        translation.localeTag(),
        translation.target(),
        translation.targetComment(),
        translation.status());
  }

  private GlossaryTermEvidenceResponse toGlossaryTermEvidenceResponse(
      GlossaryTermService.TermEvidenceView evidence) {
    return new GlossaryTermEvidenceResponse(
        evidence.id(),
        evidence.evidenceType(),
        evidence.caption(),
        evidence.imageKey(),
        evidence.tmTextUnitId(),
        evidence.cropX(),
        evidence.cropY(),
        evidence.cropWidth(),
        evidence.cropHeight(),
        evidence.sortOrder());
  }

  private GlossaryTranslationProposalResponse toGlossaryTranslationProposalResponse(
      GlossaryTermService.TranslationProposalView proposal) {
    return new GlossaryTranslationProposalResponse(
        proposal.id(),
        proposal.createdDate(),
        proposal.lastModifiedDate(),
        proposal.tmTextUnitId(),
        proposal.source(),
        proposal.localeTag(),
        proposal.proposedTarget(),
        proposal.proposedTargetComment(),
        proposal.note(),
        proposal.status(),
        proposal.reviewerNote());
  }

  private MatchGlossaryTermsResponse.MatchedGlossaryTermResponse toMatchedTermResponse(
      GlossaryService.MatchedGlossaryTerm matchedTerm) {
    GlossaryService.GlossaryTerm glossaryTerm = matchedTerm.glossaryTerm();
    return new MatchGlossaryTermsResponse.MatchedGlossaryTermResponse(
        glossaryTerm.glossaryId(),
        glossaryTerm.glossaryName(),
        glossaryTerm.tmTextUnitId(),
        glossaryTerm.source(),
        glossaryTerm.comment(),
        glossaryTerm.definition(),
        glossaryTerm.partOfSpeech(),
        glossaryTerm.termType(),
        glossaryTerm.enforcement(),
        glossaryTerm.status(),
        glossaryTerm.provenance(),
        glossaryTerm.target(),
        glossaryTerm.targetComment(),
        glossaryTerm.doNotTranslate(),
        glossaryTerm.caseSensitive(),
        matchedTerm.matchType().name(),
        matchedTerm.startIndex(),
        matchedTerm.endIndex(),
        matchedTerm.matchedText(),
        glossaryTerm.evidence().stream()
            .map(
                evidence ->
                    new GlossaryTermEvidenceResponse(
                        evidence.id(),
                        evidence.evidenceType(),
                        evidence.caption(),
                        evidence.imageKey(),
                        evidence.tmTextUnitId(),
                        evidence.cropX(),
                        evidence.cropY(),
                        evidence.cropWidth(),
                        evidence.cropHeight(),
                        evidence.sortOrder()))
            .toList());
  }

  private void persistTermIndexSuggestionSearchHybridResponse(
      Long glossaryId, UUID requestId, SearchTermIndexSuggestionsHybridResponse response) {
    String payloadJson = objectMapper.writeValueAsStringUnchecked(response);
    structuredBlobStorage.put(
        StructuredBlobStorage.Prefix.GLOSSARY_TERM_INDEX_SUGGESTION_SEARCH_ASYNC,
        responseStorageNameForTermIndexSuggestionSearch(glossaryId, requestId),
        payloadJson,
        Retention.MIN_1_DAY);
  }

  private SearchTermIndexSuggestionsHybridResponse.PollingToken
      buildTermIndexSuggestionSearchPollingToken(UUID requestId) {
    return new SearchTermIndexSuggestionsHybridResponse.PollingToken(
        requestId, termIndexEntriesHybridProperties.recommendedPollingDuration().toMillis());
  }

  private boolean isExpectedHybridSearchError(Exception e) {
    return e instanceof AccessDeniedException
        || e instanceof IllegalArgumentException
        || e instanceof ResponseStatusException responseStatusException
            && responseStatusException.getStatusCode().is4xxClientError();
  }

  private String responseStorageNameForTermIndexSuggestionSearch(Long glossaryId, UUID requestId) {
    return glossaryId + "/" + requestId;
  }

  private void requireTermManager() {
    if (!userService.isCurrentUserAdminOrPm()) {
      throw new AccessDeniedException("PM or admin access is required to curate glossary terms");
    }
  }
}
