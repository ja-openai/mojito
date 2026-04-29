package com.box.l10n.mojito.rest.glossary;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.glossary.GlossaryImportExportService;
import com.box.l10n.mojito.service.glossary.GlossaryManagementService;
import com.box.l10n.mojito.service.glossary.GlossaryTermService;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  private final GlossaryManagementService glossaryManagementService;
  private final GlossaryImportExportService glossaryImportExportService;
  private final GlossaryTermService glossaryTermService;
  private final GlossaryService glossaryService;

  public GlossaryWS(
      GlossaryManagementService glossaryManagementService,
      GlossaryImportExportService glossaryImportExportService,
      GlossaryTermService glossaryTermService,
      GlossaryService glossaryService) {
    this.glossaryManagementService = glossaryManagementService;
    this.glossaryImportExportService = glossaryImportExportService;
    this.glossaryTermService = glossaryTermService;
    this.glossaryService = glossaryService;
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
}
