package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TranslationIncidentService {

  public record CreateIncidentRequest(
      String stringId,
      String observedLocale,
      String repository,
      String reason,
      String sourceReference) {}

  public record RejectIncidentRequest(String comment) {}

  public record UpdateStatusRequest(TranslationIncidentStatus status) {}

  public record IncidentPage(
      List<IncidentSummary> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      boolean hasNext,
      boolean hasPrevious) {}

  public record IncidentSummary(
      Long id,
      String status,
      String resolution,
      String repositoryName,
      String stringId,
      String observedLocale,
      String resolvedLocale,
      String reason,
      String sourceReference,
      int lookupCandidateCount,
      boolean canReject,
      String reviewProjectName,
      String reviewProjectConfidence,
      String selectedTranslationStatus,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      ZonedDateTime rejectedAt,
      ZonedDateTime closedAt,
      String closedByUsername,
      String incidentLink) {}

  public record LookupCandidateSnapshot(
      String repositoryName,
      Long tmTextUnitId,
      String textUnitLink,
      Long tmTextUnitCurrentVariantId,
      Long tmTextUnitVariantId,
      String assetPath,
      String source,
      String target,
      String targetComment,
      String status,
      boolean includedInLocalizedFile,
      boolean canReject) {}

  public record ReviewProjectCandidateSnapshot(
      Long reviewProjectId,
      String reviewProjectName,
      String reviewProjectLink,
      Long reviewProjectRequestId,
      String reviewProjectRequestLink,
      String confidence,
      int confidenceScore,
      List<String> confidenceReasons,
      String reviewerUsername,
      String ownerUsername) {}

  public record IncidentDetail(
      Long id,
      String status,
      String resolution,
      String repositoryName,
      String stringId,
      String observedLocale,
      String resolvedLocale,
      String lookupResolutionStatus,
      String localeResolutionStrategy,
      boolean localeUsedFallback,
      String reason,
      String sourceReference,
      int lookupCandidateCount,
      String incidentLink,
      Long selectedTmTextUnitId,
      String selectedTextUnitLink,
      Long selectedTmTextUnitCurrentVariantId,
      Long selectedTmTextUnitVariantId,
      String selectedAssetPath,
      String selectedSource,
      String selectedTarget,
      String selectedTargetComment,
      String selectedTranslationStatus,
      Boolean selectedIncludedInLocalizedFile,
      boolean canReject,
      Long reviewProjectId,
      Long reviewProjectRequestId,
      String reviewProjectName,
      String reviewProjectLink,
      String reviewProjectConfidence,
      Integer reviewProjectConfidenceScore,
      String translationAuthorUsername,
      String reviewerUsername,
      String ownerUsername,
      String translationAuthorSlackMention,
      String reviewerSlackMention,
      String ownerSlackMention,
      String slackDestinationSource,
      String slackChannelId,
      String slackThreadTs,
      Boolean slackCanSend,
      String slackNote,
      String slackDraft,
      List<LookupCandidateSnapshot> lookupCandidates,
      List<ReviewProjectCandidateSnapshot> reviewProjectCandidates,
      String rejectAuditComment,
      Long rejectAuditCommentId,
      String rejectedByUsername,
      ZonedDateTime closedAt,
      String closedByUsername,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      ZonedDateTime rejectedAt) {}

  private static final TypeReference<List<LookupCandidateSnapshot>> LOOKUP_CANDIDATE_LIST_TYPE =
      new TypeReference<>() {};

  private static final TypeReference<List<ReviewProjectCandidateSnapshot>>
      REVIEW_PROJECT_CANDIDATE_LIST_TYPE = new TypeReference<>() {};

  private final TranslationIncidentRepository translationIncidentRepository;
  private final BadTranslationLookupService badTranslationLookupService;
  private final BadTranslationReviewProjectService badTranslationReviewProjectService;
  private final BadTranslationSlackService badTranslationSlackService;
  private final BadTranslationSlackMessageComposer badTranslationSlackMessageComposer;
  private final BadTranslationMutationService badTranslationMutationService;
  private final TMTextUnitVariantRepository tmTextUnitVariantRepository;
  private final UserService userService;
  private final AuditorAwareImpl auditorAwareImpl;
  private final ServerConfig serverConfig;
  private final ObjectMapper objectMapper;

  public TranslationIncidentService(
      TranslationIncidentRepository translationIncidentRepository,
      BadTranslationLookupService badTranslationLookupService,
      BadTranslationReviewProjectService badTranslationReviewProjectService,
      BadTranslationSlackService badTranslationSlackService,
      BadTranslationSlackMessageComposer badTranslationSlackMessageComposer,
      BadTranslationMutationService badTranslationMutationService,
      TMTextUnitVariantRepository tmTextUnitVariantRepository,
      UserService userService,
      AuditorAwareImpl auditorAwareImpl,
      ServerConfig serverConfig,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper) {
    this.translationIncidentRepository = Objects.requireNonNull(translationIncidentRepository);
    this.badTranslationLookupService = Objects.requireNonNull(badTranslationLookupService);
    this.badTranslationReviewProjectService =
        Objects.requireNonNull(badTranslationReviewProjectService);
    this.badTranslationSlackService = Objects.requireNonNull(badTranslationSlackService);
    this.badTranslationSlackMessageComposer =
        Objects.requireNonNull(badTranslationSlackMessageComposer);
    this.badTranslationMutationService = Objects.requireNonNull(badTranslationMutationService);
    this.tmTextUnitVariantRepository = Objects.requireNonNull(tmTextUnitVariantRepository);
    this.userService = Objects.requireNonNull(userService);
    this.auditorAwareImpl = Objects.requireNonNull(auditorAwareImpl);
    this.serverConfig = Objects.requireNonNull(serverConfig);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Transactional(readOnly = true)
  public IncidentPage getIncidents(
      TranslationIncidentStatus status,
      String query,
      LocalDate createdAfter,
      LocalDate createdBefore,
      int page,
      int size) {
    assertCurrentUserCanManageIncidents();
    int validatedPage = Math.max(0, page);
    int validatedSize = Math.max(1, Math.min(size, 200));
    Page<TranslationIncident> result =
        translationIncidentRepository.findAll(
            buildIncidentSpecification(status, query, createdAfter, createdBefore),
            PageRequest.of(
                validatedPage, validatedSize, Sort.by(Sort.Direction.DESC, "createdDate")));
    return new IncidentPage(
        result.stream().map(this::toSummary).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages(),
        result.hasNext(),
        result.hasPrevious());
  }

  @Transactional(readOnly = true)
  public IncidentDetail getIncident(Long incidentId) {
    assertCurrentUserCanManageIncidents();
    return toDetail(getIncidentEntity(incidentId));
  }

  @Transactional
  public IncidentDetail createIncident(CreateIncidentRequest request) {
    assertCurrentUserCanManageIncidents();
    CreateIncidentRequest validatedRequest = validateCreateRequest(request);
    BadTranslationLookupService.FindTranslationResult lookupResult =
        badTranslationLookupService.findTranslation(
            new BadTranslationLookupService.FindTranslationInput(
                validatedRequest.stringId(),
                validatedRequest.observedLocale(),
                validatedRequest.repository()));

    TranslationIncident incident = new TranslationIncident();
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setResolution(TranslationIncidentResolution.PENDING_REVIEW);
    incident.setLookupResolutionStatus(lookupResult.resolutionStatus().name());
    incident.setLocaleResolutionStrategy(lookupResult.localeResolution().strategy().name());
    incident.setLocaleUsedFallback(lookupResult.localeResolution().usedFallback());
    incident.setRepositoryName(
        lookupResult.requestedRepository() == null
            ? validatedRequest.repository()
            : lookupResult.requestedRepository().name());
    incident.setStringId(validatedRequest.stringId());
    incident.setObservedLocale(validatedRequest.observedLocale());
    incident.setResolvedLocale(lookupResult.localeResolution().resolvedLocale().bcp47Tag());
    incident.setResolvedLocaleId(lookupResult.localeResolution().resolvedLocale().id());
    incident.setReason(validatedRequest.reason());
    incident.setSourceReference(normalizeOptional(validatedRequest.sourceReference()));
    incident.setLookupCandidateCount(lookupResult.matchCount());
    incident.setLookupCandidatesJson(
        writeJson(
            lookupResult.candidates().stream().map(this::toLookupCandidateSnapshot).toList()));

    if (lookupResult.resolutionStatus() == BadTranslationLookupService.ResolutionStatus.UNIQUE_MATCH
        && !lookupResult.candidates().isEmpty()) {
      populateSelectedCandidate(
          incident,
          lookupResult.localeResolution().resolvedLocale(),
          lookupResult.candidates().getFirst(),
          validatedRequest.reason(),
          validatedRequest.sourceReference());
    }

    translationIncidentRepository.save(incident);
    return toDetail(incident);
  }

  @Transactional
  public IncidentDetail rejectIncident(Long incidentId, RejectIncidentRequest request) {
    assertCurrentUserCanManageIncidents();
    TranslationIncident incident = getIncidentEntity(incidentId);
    if (incident.getStatus() == TranslationIncidentStatus.CLOSED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Reopen this incident before attempting rejection");
    }
    if (!Boolean.TRUE.equals(incident.getSelectedCanReject())
        || incident.getSelectedTmTextUnitId() == null
        || incident.getSelectedTmTextUnitCurrentVariantId() == null
        || incident.getSelectedTmTextUnitVariantId() == null
        || incident.getResolvedLocaleId() == null
        || incident.getResolvedLocale() == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This incident does not have a rejectable translation candidate");
    }
    if (incident.getResolution() == TranslationIncidentResolution.REJECTED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This incident is already rejected");
    }

    String rejectAuditComment =
        buildRejectAuditComment(incident, request == null ? null : request.comment());

    try {
      BadTranslationMutationService.RejectMutationResult result =
          badTranslationMutationService.rejectTranslation(
              new BadTranslationLookupService.TranslationCandidate(
                  new BadTranslationLookupService.RepositoryRef(null, incident.getRepositoryName()),
                  incident.getSelectedTmTextUnitId(),
                  buildTextUnitLink(
                      incident.getSelectedTmTextUnitId(), incident.getResolvedLocale()),
                  incident.getSelectedTmTextUnitCurrentVariantId(),
                  incident.getSelectedTmTextUnitVariantId(),
                  incident.getStringId(),
                  incident.getSelectedAssetPath(),
                  null,
                  incident.getSelectedSource(),
                  incident.getSelectedTarget(),
                  incident.getSelectedTargetComment(),
                  incident.getSelectedTranslationStatus(),
                  Boolean.TRUE.equals(incident.getSelectedIncludedInLocalizedFile()),
                  incident.getCreatedDate(),
                  Boolean.TRUE.equals(incident.getSelectedCanReject())),
              new BadTranslationLookupService.LocaleRef(
                  incident.getResolvedLocaleId(), incident.getResolvedLocale()),
              rejectAuditComment);

      ZonedDateTime now = ZonedDateTime.now();
      incident.setResolution(TranslationIncidentResolution.REJECTED);
      incident.setSelectedTmTextUnitCurrentVariantId(result.currentTmTextUnitCurrentVariantId());
      incident.setSelectedTmTextUnitVariantId(result.currentTmTextUnitVariantId());
      incident.setSelectedTranslationStatus(result.statusAfter());
      incident.setSelectedIncludedInLocalizedFile(result.includedInLocalizedFileAfter());
      incident.setRejectAuditComment(rejectAuditComment);
      incident.setRejectAuditCommentId(result.auditCommentId());
      incident.setRejectedAt(now);
      incident.setRejectedByUsername(getCurrentUsername());
      incident.setStatus(TranslationIncidentStatus.CLOSED);
      incident.setClosedAt(now);
      incident.setClosedByUsername(getCurrentUsername());
      translationIncidentRepository.save(incident);
    } catch (RuntimeException exception) {
      incident.setResolution(TranslationIncidentResolution.REJECT_FAILED);
      incident.setRejectAuditComment(rejectAuditComment);
      translationIncidentRepository.save(incident);
      throw exception;
    }

    return toDetail(incident);
  }

  @Transactional
  public IncidentDetail sendSlackDraft(Long incidentId) {
    assertCurrentUserCanManageIncidents();
    TranslationIncident incident = getIncidentEntity(incidentId);
    String slackDraft = buildSlackDraftFromIncident(incident);
    if (slackDraft == null || slackDraft.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This incident does not have a Slack draft");
    }
    incident.setSlackDraft(slackDraft);

    BadTranslationSlackDispatch dispatch =
        badTranslationSlackService.sendMessage(
            buildStoredSlackContext(incident), slackDraft.trim());

    if (!dispatch.sent()) {
      incident.setSlackNote(dispatch.note());
      translationIncidentRepository.save(incident);
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          dispatch.note() == null || dispatch.note().isBlank()
              ? "Failed to send Slack message"
              : dispatch.note());
    }

    incident.setSlackThreadTs(normalizeOptional(dispatch.threadTs()));
    incident.setSlackNote(normalizeOptional(dispatch.note()));
    translationIncidentRepository.save(incident);
    return toDetail(incident);
  }

  @Transactional
  public IncidentDetail updateStatus(Long incidentId, UpdateStatusRequest request) {
    assertCurrentUserCanManageIncidents();
    TranslationIncident incident = getIncidentEntity(incidentId);
    TranslationIncidentStatus targetStatus = request == null ? null : request.status();
    if (targetStatus == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
    }
    if (targetStatus == incident.getStatus()) {
      return toDetail(incident);
    }
    incident.setStatus(targetStatus);
    if (targetStatus == TranslationIncidentStatus.CLOSED) {
      incident.setClosedAt(ZonedDateTime.now());
      incident.setClosedByUsername(getCurrentUsername());
    } else {
      incident.setClosedAt(null);
      incident.setClosedByUsername(null);
    }
    translationIncidentRepository.save(incident);
    return toDetail(incident);
  }

  private void populateSelectedCandidate(
      TranslationIncident incident,
      BadTranslationLookupService.LocaleRef locale,
      BadTranslationLookupService.TranslationCandidate candidate,
      String reason,
      String sourceReference) {
    incident.setSelectedTmTextUnitId(candidate.tmTextUnitId());
    incident.setSelectedTmTextUnitCurrentVariantId(candidate.tmTextUnitCurrentVariantId());
    incident.setSelectedTmTextUnitVariantId(candidate.tmTextUnitVariantId());
    incident.setSelectedAssetPath(candidate.assetPath());
    incident.setSelectedSource(candidate.source());
    incident.setSelectedTarget(candidate.target());
    incident.setSelectedTargetComment(candidate.targetComment());
    incident.setSelectedTranslationStatus(candidate.status());
    incident.setSelectedIncludedInLocalizedFile(candidate.includedInLocalizedFile());
    incident.setSelectedCanReject(candidate.canReject());

    BadTranslationPersonRef translationAuthor =
        getTranslationAuthor(candidate.tmTextUnitVariantId());
    List<BadTranslationReviewProjectCandidate> reviewProjectCandidates =
        badTranslationReviewProjectService.findReviewProjectCandidates(candidate, locale);
    incident.setReviewProjectCandidatesJson(
        writeJson(reviewProjectCandidates.stream().map(this::toReviewProjectSnapshot).toList()));

    BadTranslationReviewProjectCandidate selectedReviewProject =
        reviewProjectCandidates.isEmpty() ? null : reviewProjectCandidates.getFirst();
    if (selectedReviewProject != null) {
      incident.setReviewProjectId(selectedReviewProject.reviewProjectId());
      incident.setReviewProjectRequestId(selectedReviewProject.reviewProjectRequestId());
      incident.setReviewProjectName(selectedReviewProject.reviewProjectRequestName());
      incident.setReviewProjectLink(selectedReviewProject.reviewProjectLink());
      incident.setReviewProjectConfidence(selectedReviewProject.confidence());
      incident.setReviewProjectConfidenceScore(selectedReviewProject.confidenceScore());
    }

    BadTranslationSlackService.SlackContext slackContext =
        badTranslationSlackService.buildSlackContext(translationAuthor, selectedReviewProject);
    incident.setTranslationAuthorUsername(renderUsername(translationAuthor));
    incident.setReviewerUsername(renderUsername(slackContext.reviewer()));
    incident.setOwnerUsername(renderUsername(slackContext.owner()));
    incident.setTranslationAuthorSlackMention(renderSlackMention(translationAuthor));
    incident.setReviewerSlackMention(renderSlackMention(slackContext.reviewer()));
    incident.setOwnerSlackMention(renderSlackMention(slackContext.owner()));
    incident.setSlackDestinationSource(
        slackContext.destination() == null ? null : slackContext.destination().source());
    incident.setSlackClientId(
        slackContext.destination() == null ? null : slackContext.destination().slackClientId());
    incident.setSlackChannelId(
        slackContext.destination() == null ? null : slackContext.destination().slackChannelId());
    incident.setSlackThreadTs(
        slackContext.destination() == null ? null : slackContext.destination().threadTs());
    incident.setSlackCanSend(
        slackContext.destination() == null ? null : slackContext.destination().canSend());
    incident.setSlackNote(
        slackContext.destination() == null ? null : slackContext.destination().note());
    incident.setSlackDraft(
        badTranslationSlackMessageComposer.compose(
            reason,
            sourceReference,
            locale,
            candidate,
            translationAuthor,
            selectedReviewProject,
            slackContext,
            false));
    incident.setResolution(
        candidate.canReject()
            ? TranslationIncidentResolution.READY_TO_REJECT
            : TranslationIncidentResolution.PENDING_REVIEW);
  }

  private BadTranslationPersonRef getTranslationAuthor(Long tmTextUnitVariantId) {
    if (tmTextUnitVariantId == null) {
      return null;
    }
    TMTextUnitVariant variant =
        tmTextUnitVariantRepository.findById(tmTextUnitVariantId).orElse(null);
    if (variant == null || variant.getCreatedByUser() == null) {
      return null;
    }
    return new BadTranslationPersonRef(
        variant.getCreatedByUser().getId(), variant.getCreatedByUser().getUsername(), null, null);
  }

  private String buildRejectAuditComment(TranslationIncident incident, String extraComment) {
    StringBuilder builder = new StringBuilder();
    builder
        .append("Bad translation incident #")
        .append(incident.getId())
        .append(": ")
        .append(incident.getReason());
    if (incident.getSourceReference() != null && !incident.getSourceReference().isBlank()) {
      builder.append("\nSource reference: ").append(incident.getSourceReference().trim());
    }
    builder
        .append("\nString ID: ")
        .append(incident.getStringId())
        .append("\nObserved locale: ")
        .append(incident.getObservedLocale())
        .append("\nResolved locale: ")
        .append(incident.getResolvedLocale());
    if (extraComment != null && !extraComment.isBlank()) {
      builder.append("\nOperator note: ").append(extraComment.trim());
    }
    return builder.toString();
  }

  private TranslationIncident getIncidentEntity(Long incidentId) {
    return translationIncidentRepository
        .findById(incidentId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Translation incident not found"));
  }

  private IncidentSummary toSummary(TranslationIncident incident) {
    return new IncidentSummary(
        incident.getId(),
        incident.getStatus().name(),
        incident.getResolution().name(),
        incident.getRepositoryName(),
        incident.getStringId(),
        incident.getObservedLocale(),
        incident.getResolvedLocale(),
        incident.getReason(),
        incident.getSourceReference(),
        incident.getLookupCandidateCount(),
        Boolean.TRUE.equals(incident.getSelectedCanReject()),
        incident.getReviewProjectName(),
        incident.getReviewProjectConfidence(),
        incident.getSelectedTranslationStatus(),
        incident.getCreatedDate(),
        incident.getLastModifiedDate(),
        incident.getRejectedAt(),
        incident.getClosedAt(),
        incident.getClosedByUsername(),
        buildIncidentLink(incident.getId()));
  }

  private IncidentDetail toDetail(TranslationIncident incident) {
    String slackDraft = buildSlackDraftFromIncident(incident);
    return new IncidentDetail(
        incident.getId(),
        incident.getStatus().name(),
        incident.getResolution().name(),
        incident.getRepositoryName(),
        incident.getStringId(),
        incident.getObservedLocale(),
        incident.getResolvedLocale(),
        incident.getLookupResolutionStatus(),
        incident.getLocaleResolutionStrategy(),
        incident.isLocaleUsedFallback(),
        incident.getReason(),
        incident.getSourceReference(),
        incident.getLookupCandidateCount(),
        buildIncidentLink(incident.getId()),
        incident.getSelectedTmTextUnitId(),
        buildTextUnitLink(incident.getSelectedTmTextUnitId(), incident.getResolvedLocale()),
        incident.getSelectedTmTextUnitCurrentVariantId(),
        incident.getSelectedTmTextUnitVariantId(),
        incident.getSelectedAssetPath(),
        incident.getSelectedSource(),
        incident.getSelectedTarget(),
        incident.getSelectedTargetComment(),
        incident.getSelectedTranslationStatus(),
        incident.getSelectedIncludedInLocalizedFile(),
        Boolean.TRUE.equals(incident.getSelectedCanReject()),
        incident.getReviewProjectId(),
        incident.getReviewProjectRequestId(),
        incident.getReviewProjectName(),
        incident.getReviewProjectLink(),
        incident.getReviewProjectConfidence(),
        incident.getReviewProjectConfidenceScore(),
        incident.getTranslationAuthorUsername(),
        incident.getReviewerUsername(),
        incident.getOwnerUsername(),
        incident.getTranslationAuthorSlackMention(),
        incident.getReviewerSlackMention(),
        incident.getOwnerSlackMention(),
        incident.getSlackDestinationSource(),
        incident.getSlackChannelId(),
        incident.getSlackThreadTs(),
        incident.getSlackCanSend(),
        incident.getSlackNote(),
        slackDraft,
        readLookupCandidates(incident.getLookupCandidatesJson()),
        readReviewProjectCandidates(incident.getReviewProjectCandidatesJson()),
        incident.getRejectAuditComment(),
        incident.getRejectAuditCommentId(),
        incident.getRejectedByUsername(),
        incident.getClosedAt(),
        incident.getClosedByUsername(),
        incident.getCreatedDate(),
        incident.getLastModifiedDate(),
        incident.getRejectedAt());
  }

  private BadTranslationSlackService.SlackContext buildStoredSlackContext(
      TranslationIncident incident) {
    return new BadTranslationSlackService.SlackContext(
        new BadTranslationSlackDestination(
            normalizeOptional(incident.getSlackDestinationSource()),
            normalizeOptional(incident.getSlackClientId()),
            normalizeOptional(incident.getSlackChannelId()),
            normalizeOptional(incident.getSlackThreadTs()),
            Boolean.TRUE.equals(incident.getSlackCanSend()),
            normalizeOptional(incident.getSlackNote())),
        buildStoredPersonRef(
            incident.getTranslationAuthorUsername(), incident.getTranslationAuthorSlackMention()),
        buildStoredPersonRef(incident.getReviewerUsername(), incident.getReviewerSlackMention()),
        buildStoredPersonRef(incident.getOwnerUsername(), incident.getOwnerSlackMention()),
        incident.getReviewProjectRequestId());
  }

  private String buildSlackDraftFromIncident(TranslationIncident incident) {
    String repositoryName = resolveIncidentRepositoryName(incident);
    if (incident == null
        || incident.getStringId() == null
        || incident.getObservedLocale() == null
        || repositoryName == null) {
      return incident == null ? null : incident.getSlackDraft();
    }

    BadTranslationLookupService.LocaleRef locale =
        new BadTranslationLookupService.LocaleRef(
            incident.getResolvedLocaleId(),
            incident.getResolvedLocale() == null
                ? incident.getObservedLocale()
                : incident.getResolvedLocale());

    BadTranslationLookupService.TranslationCandidate candidate =
        new BadTranslationLookupService.TranslationCandidate(
            new BadTranslationLookupService.RepositoryRef(null, repositoryName),
            incident.getSelectedTmTextUnitId(),
            buildTextUnitLink(incident.getSelectedTmTextUnitId(), incident.getResolvedLocale()),
            incident.getSelectedTmTextUnitCurrentVariantId(),
            incident.getSelectedTmTextUnitVariantId(),
            incident.getStringId(),
            incident.getSelectedAssetPath(),
            null,
            incident.getSelectedSource(),
            incident.getSelectedTarget(),
            incident.getSelectedTargetComment(),
            incident.getSelectedTranslationStatus(),
            Boolean.TRUE.equals(incident.getSelectedIncludedInLocalizedFile()),
            incident.getCreatedDate(),
            Boolean.TRUE.equals(incident.getSelectedCanReject()));

    BadTranslationReviewProjectCandidate reviewProjectCandidate =
        incident.getReviewProjectId() == null
            ? null
            : new BadTranslationReviewProjectCandidate(
                incident.getReviewProjectId(),
                null,
                null,
                null,
                null,
                incident.getReviewProjectLink(),
                incident.getReviewProjectRequestId(),
                incident.getReviewProjectName(),
                incident.getReviewProjectLink(),
                null,
                null,
                null,
                buildStoredPersonRef(incident.getOwnerUsername(), incident.getOwnerSlackMention()),
                null,
                buildStoredPersonRef(
                    incident.getReviewerUsername(), incident.getReviewerSlackMention()),
                incident.getReviewProjectConfidence(),
                incident.getReviewProjectConfidenceScore() == null
                    ? 0
                    : incident.getReviewProjectConfidenceScore(),
                List.of());

    return badTranslationSlackMessageComposer.compose(
        incident.getReason(),
        incident.getSourceReference(),
        locale,
        candidate,
        buildStoredPersonRef(
            incident.getTranslationAuthorUsername(), incident.getTranslationAuthorSlackMention()),
        reviewProjectCandidate,
        buildStoredSlackContext(incident),
        incident.getResolution() == TranslationIncidentResolution.REJECTED);
  }

  private String resolveIncidentRepositoryName(TranslationIncident incident) {
    if (incident == null) {
      return null;
    }
    String repositoryName = normalizeOptional(incident.getRepositoryName());
    if (repositoryName != null) {
      return repositoryName;
    }

    List<LookupCandidateSnapshot> lookupCandidates =
        readLookupCandidates(incident.getLookupCandidatesJson());
    if (incident.getSelectedTmTextUnitId() != null) {
      for (LookupCandidateSnapshot candidate : lookupCandidates) {
        if (candidate != null
            && candidate.tmTextUnitId() != null
            && candidate.tmTextUnitId().equals(incident.getSelectedTmTextUnitId())) {
          String candidateRepositoryName = normalizeOptional(candidate.repositoryName());
          if (candidateRepositoryName != null) {
            return candidateRepositoryName;
          }
        }
      }
    }

    if (lookupCandidates.size() == 1) {
      return normalizeOptional(lookupCandidates.getFirst().repositoryName());
    }

    return null;
  }

  private BadTranslationPersonRef buildStoredPersonRef(String username, String slackMention) {
    String normalizedUsername = normalizeOptional(username);
    String normalizedSlackMention = normalizeOptional(slackMention);
    if (normalizedUsername == null && normalizedSlackMention == null) {
      return null;
    }
    return new BadTranslationPersonRef(null, normalizedUsername, null, normalizedSlackMention);
  }

  private LookupCandidateSnapshot toLookupCandidateSnapshot(
      BadTranslationLookupService.TranslationCandidate candidate) {
    return new LookupCandidateSnapshot(
        candidate.repository() == null ? null : candidate.repository().name(),
        candidate.tmTextUnitId(),
        candidate.textUnitLink(),
        candidate.tmTextUnitCurrentVariantId(),
        candidate.tmTextUnitVariantId(),
        candidate.assetPath(),
        candidate.source(),
        candidate.target(),
        candidate.targetComment(),
        candidate.status(),
        candidate.includedInLocalizedFile(),
        candidate.canReject());
  }

  private ReviewProjectCandidateSnapshot toReviewProjectSnapshot(
      BadTranslationReviewProjectCandidate candidate) {
    return new ReviewProjectCandidateSnapshot(
        candidate.reviewProjectId(),
        candidate.reviewProjectRequestName(),
        candidate.reviewProjectLink(),
        candidate.reviewProjectRequestId(),
        candidate.reviewProjectRequestLink(),
        candidate.confidence(),
        candidate.confidenceScore(),
        candidate.confidenceReasons(),
        renderUsername(candidate.reviewer()),
        renderUsername(
            candidate.assignedPm() != null ? candidate.assignedPm() : candidate.requestCreator()));
  }

  private Specification<TranslationIncident> buildIncidentSpecification(
      TranslationIncidentStatus status,
      String query,
      LocalDate createdAfter,
      LocalDate createdBefore) {
    return (root, criteriaQuery, criteriaBuilder) -> {
      List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
      if (status != null) {
        predicates.add(criteriaBuilder.equal(root.get("status"), status));
      }

      String normalizedQuery = normalizeOptional(query);
      if (normalizedQuery != null) {
        String likeValue = "%" + normalizedQuery.toLowerCase() + "%";
        List<jakarta.persistence.criteria.Predicate> searchPredicates = new ArrayList<>();
        Long numericId = parseLongOrNull(normalizedQuery);
        if (numericId != null) {
          searchPredicates.add(criteriaBuilder.equal(root.get("id"), numericId));
        }
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("stringId")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("repositoryName")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("observedLocale")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("resolvedLocale")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("reason")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("sourceReference")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("reviewProjectName")), likeValue));
        searchPredicates.add(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("selectedAssetPath")), likeValue));
        predicates.add(
            criteriaBuilder.or(
                searchPredicates.toArray(jakarta.persistence.criteria.Predicate[]::new)));
      }

      if (createdAfter != null) {
        predicates.add(
            criteriaBuilder.greaterThanOrEqualTo(
                root.get("createdDate"), createdAfter.atStartOfDay(ZoneOffset.UTC)));
      }
      if (createdBefore != null) {
        predicates.add(
            criteriaBuilder.lessThan(
                root.get("createdDate"), createdBefore.plusDays(1).atStartOfDay(ZoneOffset.UTC)));
      }

      return criteriaBuilder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
  }

  private List<LookupCandidateSnapshot> readLookupCandidates(String json) {
    return readJsonList(json, LOOKUP_CANDIDATE_LIST_TYPE);
  }

  private List<ReviewProjectCandidateSnapshot> readReviewProjectCandidates(String json) {
    return readJsonList(json, REVIEW_PROJECT_CANDIDATE_LIST_TYPE);
  }

  private <T> List<T> readJsonList(String json, TypeReference<List<T>> typeReference) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    return objectMapper.readValueUnchecked(json, typeReference);
  }

  private String writeJson(Object value) {
    return objectMapper.writeValueAsStringUnchecked(value);
  }

  private String buildIncidentLink(Long incidentId) {
    String baseUrl = normalizeServerBaseUrl();
    if (incidentId == null || baseUrl == null) {
      return null;
    }
    return baseUrl + "/translation-incidents?incidentId=" + incidentId;
  }

  private String buildTextUnitLink(Long tmTextUnitId, String localeTag) {
    String baseUrl = normalizeServerBaseUrl();
    if (tmTextUnitId == null || baseUrl == null) {
      return null;
    }

    String textUnitLink = baseUrl + "/text-units/" + tmTextUnitId;
    if (localeTag == null || localeTag.isBlank()) {
      return textUnitLink;
    }
    return textUnitLink + "?locale=" + URLEncoder.encode(localeTag.trim(), StandardCharsets.UTF_8);
  }

  private String normalizeServerBaseUrl() {
    String configured = serverConfig.getUrl();
    if (configured == null || configured.isBlank()) {
      return null;
    }

    String trimmed = configured.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isBlank() ? null : trimmed;
  }

  private String renderUsername(BadTranslationPersonRef person) {
    return person == null ? null : person.username();
  }

  private String renderSlackMention(BadTranslationPersonRef person) {
    return person == null ? null : person.slackMention();
  }

  private CreateIncidentRequest validateCreateRequest(CreateIncidentRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
    }
    return new CreateIncidentRequest(
        requireNonBlank(request.stringId(), "stringId"),
        requireNonBlank(request.observedLocale(), "observedLocale"),
        normalizeOptional(request.repository()),
        requireNonBlank(request.reason(), "reason"),
        normalizeOptional(request.sourceReference()));
  }

  private String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
    }
    return value.trim();
  }

  private String normalizeOptional(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private Long parseLongOrNull(String value) {
    try {
      return value == null ? null : Long.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String getCurrentUsername() {
    return auditorAwareImpl.getCurrentAuditor().map(user -> user.getUsername()).orElse("unknown");
  }

  private void assertCurrentUserCanManageIncidents() {
    if (!userService.isCurrentUserAdmin() && !userService.isCurrentUserPm()) {
      throw new AccessDeniedException("PM or admin role required");
    }
  }
}
