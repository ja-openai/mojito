package com.box.l10n.mojito.rest.cms;

import com.box.l10n.mojito.service.cms.CmsContentConflictException;
import com.box.l10n.mojito.service.cms.CmsContentNotFoundException;
import com.box.l10n.mojito.service.cms.CmsContentService;
import com.box.l10n.mojito.service.cms.CmsSnapshotSigningService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/content-cms")
public class CmsContentWS {

  public static final String PUBLISH_REQUEST_KEY_HEADER = "Idempotency-Key";
  public static final String SNAPSHOT_SIGNING_KEY_ID_HEADER =
      "X-Mojito-Cms-Snapshot-Signing-Key-Id";
  public static final String SIGNATURE_ALGORITHM_HEADER = "X-Mojito-Cms-Signature-Algorithm";
  public static final String SNAPSHOT_SIGNATURE_VERSION_HEADER =
      "X-Mojito-Cms-Snapshot-Signature-Version";
  public static final String SNAPSHOT_SIGNATURE_HEADER = "X-Mojito-Cms-Snapshot-Signature";
  public static final String ARTIFACT_SIGNATURE_VERSION_HEADER =
      "X-Mojito-Cms-Artifact-Signature-Version";
  public static final String ARTIFACT_SIGNATURE_HEADER = "X-Mojito-Cms-Artifact-Signature";
  private static final String X_CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
  private static final String NO_SNIFF_HEADER_VALUE = "nosniff";
  private static final Pattern SNAPSHOT_VERSION_LOCATOR_PATTERN = Pattern.compile("[1-9][0-9]*");

  private final CmsContentService cmsContentService;

  public CmsContentWS(CmsContentService cmsContentService) {
    this.cmsContentService = cmsContentService;
  }

  @GetMapping("/projects")
  public ResponseEntity<CmsContentService.SearchProjectsView> searchProjects(
      @RequestParam(name = "search", required = false) String searchQuery,
      @RequestParam(name = "enabled", required = false) Boolean enabled,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      return mutableReadResponse(cmsContentService.searchProjects(searchQuery, enabled, limit));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CmsContentService.ProjectDetail> createProject(
      @RequestBody CmsContentService.ProjectCommand request) {
    try {
      return mutableWriteResponse(HttpStatus.CREATED, cmsContentService.createProject(request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/projects/{projectId}")
  public ResponseEntity<CmsContentService.ProjectDetail> updateProject(
      @PathVariable Long projectId, @RequestBody CmsContentService.ProjectUpdateCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.updateProject(projectId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/projects/{projectId}/target-locales")
  public ResponseEntity<CmsContentService.ProjectDetail> addTargetLocales(
      @PathVariable Long projectId, @RequestBody CmsContentService.TargetLocalesCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.addTargetLocales(projectId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectId}")
  public ResponseEntity<CmsContentService.ProjectDetail> getProject(@PathVariable Long projectId) {
    try {
      return mutableReadResponse(cmsContentService.getProject(projectId));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/projects/{projectId}/content-types")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CmsContentService.ProjectDetail> createContentType(
      @PathVariable Long projectId, @RequestBody CmsContentService.ContentTypeCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.CREATED, cmsContentService.createContentType(projectId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/projects/{projectId}/first-copy-block")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CmsContentService.ProjectDetail> createFirstCopyBlock(
      @PathVariable Long projectId, @RequestBody CmsContentService.FirstCopyBlockCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.CREATED, cmsContentService.createFirstCopyBlock(projectId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/content-types/{contentTypeId}")
  public ResponseEntity<CmsContentService.ProjectDetail> updateContentType(
      @PathVariable Long contentTypeId,
      @RequestBody CmsContentService.ContentTypeUpdateCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.updateContentType(contentTypeId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/content-types/{contentTypeId}/fields")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CmsContentService.ProjectDetail> createContentTypeField(
      @PathVariable Long contentTypeId,
      @RequestBody CmsContentService.ContentTypeFieldCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.CREATED, cmsContentService.createContentTypeField(contentTypeId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/content-type-fields/{fieldId}")
  public ResponseEntity<CmsContentService.ProjectDetail> updateContentTypeField(
      @PathVariable Long fieldId,
      @RequestBody CmsContentService.ContentTypeFieldUpdateCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.updateContentTypeField(fieldId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/projects/{projectId}/entries")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CmsContentService.ProjectDetail> createEntry(
      @PathVariable Long projectId, @RequestBody CmsContentService.EntryCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.CREATED, cmsContentService.createEntry(projectId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/entries/{entryId}")
  public ResponseEntity<CmsContentService.ProjectDetail> updateEntry(
      @PathVariable Long entryId, @RequestBody CmsContentService.EntryUpdateCommand request) {
    try {
      return mutableWriteResponse(HttpStatus.OK, cmsContentService.updateEntry(entryId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/entries/{entryId}/private-copy-pieces")
  public ResponseEntity<CmsContentService.ProjectDetail> makeEntryCopyPiecesPrivate(
      @PathVariable Long entryId,
      @RequestBody CmsContentService.EntryCopyPiecesPrivateCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.makeEntryCopyPiecesPrivate(entryId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/entries/{entryId}/variants")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<CmsContentService.ProjectDetail> createVariant(
      @PathVariable Long entryId, @RequestBody CmsContentService.VariantCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.CREATED, cmsContentService.createVariant(entryId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/variants/{variantId}")
  public ResponseEntity<CmsContentService.ProjectDetail> updateVariant(
      @PathVariable Long variantId, @RequestBody CmsContentService.VariantUpdateCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.updateVariant(variantId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/variants/{variantId}/field-mappings")
  public ResponseEntity<CmsContentService.ProjectDetail> upsertFieldMapping(
      @PathVariable Long variantId, @RequestBody CmsContentService.FieldMappingCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.upsertFieldMapping(variantId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @DeleteMapping("/field-mappings/{mappingId}")
  public ResponseEntity<CmsContentService.ProjectDetail> unmapFieldMapping(
      @PathVariable Long mappingId,
      @RequestBody CmsContentService.FieldMappingDeleteCommand request) {
    try {
      return mutableWriteResponse(
          HttpStatus.OK, cmsContentService.unmapFieldMapping(mappingId, request));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/field-mappings/{mappingId}/translations/{localeTag}")
  public ResponseEntity<TextUnitDTO> getFieldTranslation(
      @PathVariable Long mappingId, @PathVariable String localeTag) {
    try {
      return mutableReadResponse(cmsContentService.getFieldTranslation(mappingId, localeTag));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/entries/{entryId}/completeness")
  public ResponseEntity<CmsContentService.EntryCompletenessView> getEntryCompleteness(
      @PathVariable Long entryId,
      @RequestParam(name = "locales", required = false) String localeTags) {
    try {
      return mutableReadResponse(
          cmsContentService.getEntryCompleteness(entryId, parseLocaleTags(localeTags)));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectId}/completeness")
  public ResponseEntity<CmsContentService.ProjectCompletenessView> getProjectCompleteness(
      @PathVariable Long projectId,
      @RequestParam(name = "locales", required = false) String localeTags) {
    try {
      return mutableReadResponse(
          cmsContentService.getProjectCompleteness(projectId, parseLocaleTags(localeTags)));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectId}/release-changes")
  public ResponseEntity<CmsContentService.ReleaseChangeSummaryView> getProjectReleaseChanges(
      @PathVariable Long projectId,
      @RequestParam(name = "locales", required = false) String localeTags) {
    try {
      return mutableReadResponse(
          cmsContentService.getProjectReleaseChanges(projectId, parseLocaleTags(localeTags)));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/projects/{projectId}/publish-snapshots")
  public ResponseEntity<CmsContentService.PublishSnapshotView> publishProject(
      @PathVariable Long projectId,
      @RequestHeader(name = PUBLISH_REQUEST_KEY_HEADER, required = false)
          List<String> publishRequestKeys,
      @RequestBody CmsContentService.PublishCommand request) {
    try {
      CmsContentService.PublishSnapshotView snapshot =
          cmsContentService.publishProject(
              projectId, request, requireSinglePublishRequestKey(publishRequestKeys));
      return ResponseEntity.created(URI.create(snapshot.artifactExportPath()))
          .headers(noStoreHeaders())
          .body(snapshot);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectId}/publish-snapshots")
  public ResponseEntity<CmsContentService.PublishSnapshotHistoryView> getProjectPublishSnapshots(
      @PathVariable Long projectId,
      @RequestParam(name = "beforeVersion", required = false) Integer beforeSnapshotVersion,
      @RequestParam(name = "limit", required = false) Integer limit) {
    try {
      return mutableReadResponse(
          cmsContentService.getProjectPublishSnapshots(projectId, beforeSnapshotVersion, limit));
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectKey}/publish-snapshots/latest")
  public ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> getLatestPublishedSnapshot(
      @PathVariable String projectKey,
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    try {
      requireCanonicalDeliveryRequest(
          request, snapshotDescriptorPath(projectKey), "Latest snapshot locator");
      return latestSnapshotDescriptorResponse(
          cmsContentService.getLatestPublishedSnapshotDescriptor(projectKey), ifNoneMatch, true);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @RequestMapping(
      value = "/projects/{projectKey}/publish-snapshots/latest",
      method = RequestMethod.HEAD)
  public ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> headLatestPublishedSnapshot(
      @PathVariable String projectKey,
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    try {
      requireCanonicalDeliveryRequest(
          request, snapshotDescriptorPath(projectKey), "Latest snapshot locator");
      return latestSnapshotDescriptorResponse(
          cmsContentService.getLatestPublishedSnapshotDescriptor(projectKey), ifNoneMatch, false);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectKey}/publish-snapshots/{snapshotVersion}/artifact")
  public ResponseEntity<byte[]> getVersionedSnapshotArtifact(
      @PathVariable String projectKey,
      @PathVariable String snapshotVersion,
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    try {
      int parsedSnapshotVersion = parseExactSnapshotVersionLocator(snapshotVersion);
      requireCanonicalDeliveryRequest(
          request, snapshotArtifactPath(projectKey, parsedSnapshotVersion), "Artifact locator");
      return artifactResponse(
          cmsContentService.getSnapshotArtifact(projectKey, parsedSnapshotVersion),
          ifNoneMatch,
          true);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @RequestMapping(
      value = "/projects/{projectKey}/publish-snapshots/{snapshotVersion}/artifact",
      method = RequestMethod.HEAD)
  public ResponseEntity<byte[]> headVersionedSnapshotArtifact(
      @PathVariable String projectKey,
      @PathVariable String snapshotVersion,
      HttpServletRequest request,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    try {
      int parsedSnapshotVersion = parseExactSnapshotVersionLocator(snapshotVersion);
      requireCanonicalDeliveryRequest(
          request, snapshotArtifactPath(projectKey, parsedSnapshotVersion), "Artifact locator");
      return artifactResponse(
          cmsContentService.getSnapshotArtifact(projectKey, parsedSnapshotVersion),
          ifNoneMatch,
          false);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public void handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
    throw new ResponseStatusException(
        HttpStatus.CONFLICT, "Content changed since it was loaded; refresh and retry", ex);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public void handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Content write conflicts with current CMS state; refresh and retry",
        ex);
  }

  private List<String> parseLocaleTags(String localeTags) {
    if (localeTags == null || localeTags.isBlank()) {
      return List.of();
    }
    return Arrays.stream(localeTags.split(",", -1)).map(String::trim).toList();
  }

  private String requireSinglePublishRequestKey(List<String> publishRequestKeys) {
    if (publishRequestKeys == null || publishRequestKeys.size() != 1) {
      throw new IllegalArgumentException("Idempotency-Key header must be supplied exactly once");
    }
    return publishRequestKeys.getFirst();
  }

  private int parseExactSnapshotVersionLocator(String snapshotVersion) {
    if (snapshotVersion == null
        || !SNAPSHOT_VERSION_LOCATOR_PATTERN.matcher(snapshotVersion).matches()) {
      throw new IllegalArgumentException(
          "Snapshot version locator must use canonical positive decimal digits");
    }
    try {
      return Integer.parseInt(snapshotVersion);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "Snapshot version locator must use canonical positive decimal digits", ex);
    }
  }

  private void requireCanonicalDeliveryRequest(
      HttpServletRequest request, String canonicalPath, String locatorLabel) {
    if (!canonicalPath.equals(requestPath(request)) || request.getQueryString() != null) {
      throw new IllegalArgumentException(
          locatorLabel + " must use canonical path segments without query parameters");
    }
  }

  private String requestPath(HttpServletRequest request) {
    String requestUri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (!contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
      return requestUri.substring(contextPath.length());
    }
    return requestUri;
  }

  private String snapshotDescriptorPath(String projectKey) {
    return "/api/content-cms/projects/" + projectKey + "/publish-snapshots/latest";
  }

  private String snapshotArtifactPath(String projectKey, int snapshotVersion) {
    return "/api/content-cms/projects/"
        + projectKey
        + "/publish-snapshots/"
        + snapshotVersion
        + "/artifact";
  }

  private HttpHeaders artifactHeaders(CmsContentService.SnapshotArtifact artifact) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
    headers.setCacheControl("private, max-age=31536000, immutable");
    headers.set(X_CONTENT_TYPE_OPTIONS_HEADER, NO_SNIFF_HEADER_VALUE);
    headers.setContentDisposition(
        ContentDisposition.attachment().filename(artifact.filename()).build());
    headers.setETag(sha256Etag(artifact.artifactSha256()));
    headers.set(SNAPSHOT_SIGNING_KEY_ID_HEADER, artifact.snapshotSigningKeyId());
    headers.set(SIGNATURE_ALGORITHM_HEADER, CmsSnapshotSigningService.SIGNATURE_ALGORITHM);
    headers.set(
        SNAPSHOT_SIGNATURE_VERSION_HEADER, CmsSnapshotSigningService.SNAPSHOT_SIGNATURE_VERSION);
    headers.set(SNAPSHOT_SIGNATURE_HEADER, artifact.snapshotSignature());
    headers.set(
        ARTIFACT_SIGNATURE_VERSION_HEADER, CmsSnapshotSigningService.ARTIFACT_SIGNATURE_VERSION);
    headers.set(ARTIFACT_SIGNATURE_HEADER, artifact.artifactSignature());
    return headers;
  }

  private <T> ResponseEntity<T> mutableReadResponse(T body) {
    return ResponseEntity.ok().headers(noStoreHeaders()).body(body);
  }

  private <T> ResponseEntity<T> mutableWriteResponse(HttpStatus status, T body) {
    return ResponseEntity.status(status).headers(noStoreHeaders()).body(body);
  }

  private ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor>
      latestSnapshotDescriptorResponse(
          CmsContentService.SnapshotDeliveryDescriptor descriptor,
          String ifNoneMatch,
          boolean includeBody) {
    HttpHeaders headers = noStoreHeaders();
    headers.setETag(snapshotSignatureEtag(descriptor.snapshotSignature()));
    if (matchesEtag(ifNoneMatch, headers.getETag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
    }
    if (!includeBody) {
      return ResponseEntity.ok().headers(headers).build();
    }
    return ResponseEntity.ok().headers(headers).body(descriptor);
  }

  private HttpHeaders noStoreHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    return headers;
  }

  private ResponseEntity<byte[]> artifactResponse(
      CmsContentService.SnapshotArtifact artifact, String ifNoneMatch, boolean includeBody) {
    HttpHeaders headers = artifactHeaders(artifact);
    if (matchesEtag(ifNoneMatch, headers.getETag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
    }
    if (!includeBody) {
      return ResponseEntity.ok()
          .headers(headers)
          .contentLength(artifact.artifactByteSize())
          .build();
    }
    byte[] artifactBytes = artifact.artifactJson().getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .headers(headers)
        .contentLength(artifact.artifactByteSize())
        .body(artifactBytes);
  }

  private String sha256Etag(String sha256) {
    return "\"sha256-" + sha256 + "\"";
  }

  private String snapshotSignatureEtag(String snapshotSignature) {
    return "\"snapshot-signature-" + snapshotSignature + "\"";
  }

  private boolean matchesEtag(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
      return false;
    }
    String normalizedIfNoneMatch = trimOptionalWhitespace(ifNoneMatch);
    if ("*".equals(normalizedIfNoneMatch)) {
      return true;
    }
    List<String> candidates = parseEntityTagList(normalizedIfNoneMatch);
    return candidates.stream().anyMatch(candidate -> etag.equals(normalizeWeakEtag(candidate)));
  }

  private List<String> parseEntityTagList(String ifNoneMatch) {
    List<String> candidates = new ArrayList<>();
    int index = 0;
    while (index < ifNoneMatch.length()) {
      index = skipOptionalWhitespace(ifNoneMatch, index);
      if (index == ifNoneMatch.length()) {
        return candidates;
      }
      if (ifNoneMatch.charAt(index) == ',') {
        index++;
        continue;
      }
      int entityTagEnd = findEntityTagEnd(ifNoneMatch, index);
      if (entityTagEnd < 0) {
        return List.of();
      }
      candidates.add(ifNoneMatch.substring(index, entityTagEnd));
      index = skipOptionalWhitespace(ifNoneMatch, entityTagEnd);
      if (index == ifNoneMatch.length()) {
        return candidates;
      }
      if (ifNoneMatch.charAt(index) != ',') {
        return List.of();
      }
      index++;
    }
    return candidates;
  }

  private int findEntityTagEnd(String value, int startIndex) {
    int index = value.startsWith("W/", startIndex) ? startIndex + 2 : startIndex;
    if (index >= value.length() || value.charAt(index) != '"') {
      return -1;
    }
    index++;
    while (index < value.length()) {
      char current = value.charAt(index);
      if (current == '"') {
        return index + 1;
      }
      if (!isEntityTagCharacter(current)) {
        return -1;
      }
      index++;
    }
    return -1;
  }

  private String trimOptionalWhitespace(String value) {
    int startIndex = skipOptionalWhitespace(value, 0);
    int endIndex = value.length();
    while (endIndex > startIndex && isOptionalWhitespace(value.charAt(endIndex - 1))) {
      endIndex--;
    }
    return value.substring(startIndex, endIndex);
  }

  private int skipOptionalWhitespace(String value, int startIndex) {
    int index = startIndex;
    while (index < value.length() && isOptionalWhitespace(value.charAt(index))) {
      index++;
    }
    return index;
  }

  private boolean isOptionalWhitespace(char value) {
    return value == ' ' || value == '\t';
  }

  private boolean isEntityTagCharacter(char value) {
    return value == '!'
        || (value >= '#' && value <= '~')
        || (value >= '\u0080' && value <= '\u00FF');
  }

  private String normalizeWeakEtag(String etag) {
    return etag.startsWith("W/\"") ? etag.substring(2) : etag;
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    HttpStatus status =
        ex instanceof CmsContentConflictException
            ? HttpStatus.CONFLICT
            : ex instanceof CmsContentNotFoundException
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
    return new ResponseStatusException(status, ex.getMessage(), ex);
  }
}
