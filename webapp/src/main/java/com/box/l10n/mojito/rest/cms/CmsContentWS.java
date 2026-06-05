package com.box.l10n.mojito.rest.cms;

import com.box.l10n.mojito.service.cms.CmsContentConflictException;
import com.box.l10n.mojito.service.cms.CmsContentService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
  public static final String SNAPSHOT_SIGNATURE_HEADER = "X-Mojito-Cms-Snapshot-Signature";
  public static final String ARTIFACT_SIGNATURE_HEADER = "X-Mojito-Cms-Artifact-Signature";

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
  public CmsContentService.ProjectDetail createProject(
      @RequestBody CmsContentService.ProjectCommand request) {
    try {
      return cmsContentService.createProject(request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/projects/{projectId}")
  public CmsContentService.ProjectDetail updateProject(
      @PathVariable Long projectId, @RequestBody CmsContentService.ProjectUpdateCommand request) {
    try {
      return cmsContentService.updateProject(projectId, request);
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
  public CmsContentService.ProjectDetail createContentType(
      @PathVariable Long projectId, @RequestBody CmsContentService.ContentTypeCommand request) {
    try {
      return cmsContentService.createContentType(projectId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/content-types/{contentTypeId}")
  public CmsContentService.ProjectDetail updateContentType(
      @PathVariable Long contentTypeId,
      @RequestBody CmsContentService.ContentTypeUpdateCommand request) {
    try {
      return cmsContentService.updateContentType(contentTypeId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/content-types/{contentTypeId}/fields")
  @ResponseStatus(HttpStatus.CREATED)
  public CmsContentService.ProjectDetail createContentTypeField(
      @PathVariable Long contentTypeId,
      @RequestBody CmsContentService.ContentTypeFieldCommand request) {
    try {
      return cmsContentService.createContentTypeField(contentTypeId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/content-type-fields/{fieldId}")
  public CmsContentService.ProjectDetail updateContentTypeField(
      @PathVariable Long fieldId,
      @RequestBody CmsContentService.ContentTypeFieldUpdateCommand request) {
    try {
      return cmsContentService.updateContentTypeField(fieldId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/projects/{projectId}/entries")
  @ResponseStatus(HttpStatus.CREATED)
  public CmsContentService.ProjectDetail createEntry(
      @PathVariable Long projectId, @RequestBody CmsContentService.EntryCommand request) {
    try {
      return cmsContentService.createEntry(projectId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/entries/{entryId}")
  public CmsContentService.ProjectDetail updateEntry(
      @PathVariable Long entryId, @RequestBody CmsContentService.EntryUpdateCommand request) {
    try {
      return cmsContentService.updateEntry(entryId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/entries/{entryId}/variants")
  @ResponseStatus(HttpStatus.CREATED)
  public CmsContentService.ProjectDetail createVariant(
      @PathVariable Long entryId, @RequestBody CmsContentService.VariantCommand request) {
    try {
      return cmsContentService.createVariant(entryId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PatchMapping("/variants/{variantId}")
  public CmsContentService.ProjectDetail updateVariant(
      @PathVariable Long variantId, @RequestBody CmsContentService.VariantUpdateCommand request) {
    try {
      return cmsContentService.updateVariant(variantId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/variants/{variantId}/field-mappings")
  public CmsContentService.ProjectDetail upsertFieldMapping(
      @PathVariable Long variantId, @RequestBody CmsContentService.FieldMappingCommand request) {
    try {
      return cmsContentService.upsertFieldMapping(variantId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @DeleteMapping("/field-mappings/{mappingId}")
  public CmsContentService.ProjectDetail unmapFieldMapping(
      @PathVariable Long mappingId,
      @RequestBody CmsContentService.FieldMappingDeleteCommand request) {
    try {
      return cmsContentService.unmapFieldMapping(mappingId, request);
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

  @PostMapping("/projects/{projectId}/publish-snapshots")
  public ResponseEntity<CmsContentService.PublishSnapshotView> publishProject(
      @PathVariable Long projectId,
      @RequestHeader(name = PUBLISH_REQUEST_KEY_HEADER) String publishRequestKey,
      @RequestBody CmsContentService.PublishCommand request) {
    try {
      CmsContentService.PublishSnapshotView snapshot =
          cmsContentService.publishProject(projectId, request, publishRequestKey);
      return ResponseEntity.created(URI.create(snapshot.artifactExportPath())).body(snapshot);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectKey}/publish-snapshots/latest")
  public ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> getLatestPublishedSnapshot(
      @PathVariable String projectKey,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    try {
      return latestSnapshotDescriptorResponse(
          cmsContentService.getLatestPublishedSnapshotDescriptor(projectKey), ifNoneMatch);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/projects/{projectKey}/publish-snapshots/{snapshotVersion}/artifact")
  public ResponseEntity<byte[]> getVersionedSnapshotArtifact(
      @PathVariable String projectKey,
      @PathVariable Integer snapshotVersion,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    try {
      return artifactResponse(
          cmsContentService.getSnapshotArtifact(projectKey, snapshotVersion), ifNoneMatch);
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

  private HttpHeaders artifactHeaders(CmsContentService.SnapshotArtifact artifact) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
    headers.setCacheControl("private, max-age=31536000, immutable");
    headers.setContentDisposition(
        ContentDisposition.attachment().filename(artifact.filename()).build());
    headers.setETag(sha256Etag(artifact.artifactSha256()));
    headers.set(SNAPSHOT_SIGNING_KEY_ID_HEADER, artifact.snapshotSigningKeyId());
    headers.set(SNAPSHOT_SIGNATURE_HEADER, artifact.snapshotSignature());
    headers.set(ARTIFACT_SIGNATURE_HEADER, artifact.artifactSignature());
    return headers;
  }

  private <T> ResponseEntity<T> mutableReadResponse(T body) {
    return ResponseEntity.ok().headers(noStoreHeaders()).body(body);
  }

  private ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor>
      latestSnapshotDescriptorResponse(
          CmsContentService.SnapshotDeliveryDescriptor descriptor, String ifNoneMatch) {
    HttpHeaders headers = noStoreHeaders();
    headers.setETag(snapshotSignatureEtag(descriptor.snapshotSignature()));
    if (matchesEtag(ifNoneMatch, headers.getETag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
    }
    return ResponseEntity.ok().headers(headers).body(descriptor);
  }

  private HttpHeaders noStoreHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore());
    return headers;
  }

  private ResponseEntity<byte[]> artifactResponse(
      CmsContentService.SnapshotArtifact artifact, String ifNoneMatch) {
    HttpHeaders headers = artifactHeaders(artifact);
    if (matchesEtag(ifNoneMatch, headers.getETag())) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
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
    return Arrays.stream(ifNoneMatch.split(","))
        .map(String::trim)
        .map(this::normalizeWeakEtag)
        .anyMatch(candidate -> "*".equals(candidate) || etag.equals(candidate));
  }

  private String normalizeWeakEtag(String etag) {
    return etag.startsWith("W/") ? etag.substring(2).trim() : etag;
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    HttpStatus status =
        ex instanceof CmsContentConflictException
            ? HttpStatus.CONFLICT
            : ex.getMessage() != null && ex.getMessage().toLowerCase().contains("not found")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
    return new ResponseStatusException(status, ex.getMessage(), ex);
  }
}
