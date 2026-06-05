package com.box.l10n.mojito.rest.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.service.cms.CmsContentConflictException;
import com.box.l10n.mojito.service.cms.CmsContentService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

public class CmsContentWSTest {

  private static final String SNAPSHOT_SIGNATURE = "f".repeat(64);
  private static final String ARTIFACT_SIGNATURE = "e".repeat(64);
  private static final String AUTHORING_SHA256 = "a".repeat(64);
  private static final String PACKAGE_SHA256 = "b".repeat(64);

  @Test
  public void updateEntryReturnsConflictForStaleExpectedVersion() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.EntryUpdateCommand command =
        new CmsContentService.EntryUpdateCommand(null, null, null, null, 3L);
    when(cmsContentService.updateEntry(12L, command))
        .thenThrow(new CmsContentConflictException("Content entry changed since it was loaded"));

    assertThatThrownBy(() -> cmsContentWS.updateEntry(12L, command))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  public void unmapFieldMappingReturnsConflictForStaleExpectedVersion() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.FieldMappingDeleteCommand command =
        new CmsContentService.FieldMappingDeleteCommand(3L);
    when(cmsContentService.unmapFieldMapping(12L, command))
        .thenThrow(new CmsContentConflictException("Field mapping changed since it was loaded"));

    assertThatThrownBy(() -> cmsContentWS.unmapFieldMapping(12L, command))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  public void unmapFieldMappingRejectsMissingExpectedVersionBodyBeforeServiceCall()
      throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(delete("/api/content-cms/field-mappings/12"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).unmapFieldMapping(anyLong(), any());
  }

  @Test
  public void unmapFieldMappingAcceptsExpectedVersionBody() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();
    CmsContentService.FieldMappingDeleteCommand command =
        new CmsContentService.FieldMappingDeleteCommand(3L);
    when(cmsContentService.unmapFieldMapping(12L, command)).thenReturn(null);

    mockMvc
        .perform(
            delete("/api/content-cms/field-mappings/12")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":3}"))
        .andExpect(status().isOk());

    verify(cmsContentService).unmapFieldMapping(12L, command);
  }

  @Test
  public void publishProjectPassesRequiredPublishRequestKey() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.PublishCommand command =
        new CmsContentService.PublishCommand(List.of("fr-FR"), AUTHORING_SHA256, PACKAGE_SHA256);
    CmsContentService.PublishSnapshotView snapshot = publishSnapshotView();
    when(cmsContentService.publishProject(12L, command, "publish-request")).thenReturn(snapshot);

    ResponseEntity<CmsContentService.PublishSnapshotView> response =
        cmsContentWS.publishProject(12L, "publish-request", command);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation())
        .hasToString("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact");
    assertThat(response.getBody()).isEqualTo(snapshot);
    verify(cmsContentService).publishProject(12L, command, "publish-request");
  }

  @Test
  public void publishProjectRejectsMissingPublishRequestKeyBeforeServiceCall() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(
            post("/api/content-cms/projects/12/publish-snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).publishProject(anyLong(), any(), any());
  }

  @Test
  public void publishProjectResponseUsesSignedPublishedAtField() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();
    when(cmsContentService.publishProject(eq(12L), any(), eq("publish-request")))
        .thenReturn(publishSnapshotView());

    mockMvc
        .perform(
            post("/api/content-cms/projects/12/publish-snapshots")
                .header(CmsContentWS.PUBLISH_REQUEST_KEY_HEADER, "publish-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedAuthoringSha256\":\""
                        + AUTHORING_SHA256
                        + "\",\"expectedPackageSha256\":\""
                        + PACKAGE_SHA256
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(
            header()
                .string(
                    HttpHeaders.LOCATION,
                    "/api/content-cms/projects/growth-email/publish-snapshots/2/artifact"))
        .andExpect(jsonPath("$.publishedAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.snapshotSignature").value(SNAPSHOT_SIGNATURE))
        .andExpect(jsonPath("$.artifactSignature").value(ARTIFACT_SIGNATURE))
        .andExpect(jsonPath("$.createdDate").doesNotExist());
  }

  @Test
  public void getVersionedSnapshotArtifactReturnsSha256Etag() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    String artifactJson = "{\"project\":\"grówth\"}";
    byte[] artifactBytes = artifactJson.getBytes(StandardCharsets.UTF_8);
    when(cmsContentService.getSnapshotArtifact("growth-email", 2))
        .thenReturn(
            new CmsContentService.SnapshotArtifact(
                artifactJson,
                "abc123",
                (long) artifactBytes.length,
                "test-v1",
                SNAPSHOT_SIGNATURE,
                ARTIFACT_SIGNATURE,
                "growth-email.v2.json"));

    ResponseEntity<byte[]> response =
        cmsContentWS.getVersionedSnapshotArtifact("growth-email", 2, null);

    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .isEqualTo("private, max-age=31536000, immutable");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"growth-email.v2.json\"");
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getHeaders().getFirst(CmsContentWS.SNAPSHOT_SIGNING_KEY_ID_HEADER))
        .isEqualTo("test-v1");
    assertThat(response.getHeaders().getFirst(CmsContentWS.SNAPSHOT_SIGNATURE_HEADER))
        .isEqualTo(SNAPSHOT_SIGNATURE);
    assertThat(response.getHeaders().getFirst(CmsContentWS.ARTIFACT_SIGNATURE_HEADER))
        .isEqualTo(ARTIFACT_SIGNATURE);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(new MediaType("application", "json", StandardCharsets.UTF_8));
    assertThat(response.getHeaders().getContentLength()).isEqualTo((long) artifactBytes.length);
    assertThat(response.getBody()).containsExactly(artifactBytes);
  }

  @Test
  public void getVersionedSnapshotArtifactReturnsNotModifiedForMatchingIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    when(cmsContentService.getSnapshotArtifact("growth-email", 2))
        .thenReturn(
            new CmsContentService.SnapshotArtifact(
                "{\"project\":\"growth\"}",
                "abc123",
                20L,
                "test-v1",
                SNAPSHOT_SIGNATURE,
                ARTIFACT_SIGNATURE,
                "growth-email.v2.json"));

    ResponseEntity<byte[]> response =
        cmsContentWS.getVersionedSnapshotArtifact(
            "growth-email", 2, "W/\"sha256-stale\", \"sha256-abc123\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .isEqualTo("private, max-age=31536000, immutable");
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getHeaders().getFirst(CmsContentWS.SNAPSHOT_SIGNATURE_HEADER))
        .isEqualTo(SNAPSHOT_SIGNATURE);
    assertThat(response.getHeaders().getContentLength()).isEqualTo(-1L);
    assertThat(response.getBody()).isNull();
  }

  @Test
  public void getVersionedSnapshotArtifactHeadReturnsDeliveryHeaders() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    String artifactJson = "{\"project\":\"grówth\"}";
    byte[] artifactBytes = artifactJson.getBytes(StandardCharsets.UTF_8);
    when(cmsContentService.getSnapshotArtifact("growth-email", 2))
        .thenReturn(
            new CmsContentService.SnapshotArtifact(
                artifactJson,
                "abc123",
                (long) artifactBytes.length,
                "test-v1",
                SNAPSHOT_SIGNATURE,
                ARTIFACT_SIGNATURE,
                "growth-email.v2.json"));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(head("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable"))
        .andExpect(header().string(HttpHeaders.ETAG, "\"sha256-abc123\""))
        .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, artifactBytes.length))
        .andExpect(header().string(CmsContentWS.SNAPSHOT_SIGNATURE_HEADER, SNAPSHOT_SIGNATURE))
        .andExpect(header().string(CmsContentWS.ARTIFACT_SIGNATURE_HEADER, ARTIFACT_SIGNATURE));
  }

  @Test
  public void getVersionedSnapshotArtifactHeadReturnsNotModifiedForMatchingIfNoneMatch()
      throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getSnapshotArtifact("growth-email", 2))
        .thenReturn(
            new CmsContentService.SnapshotArtifact(
                "{\"project\":\"growth\"}",
                "abc123",
                20L,
                "test-v1",
                SNAPSHOT_SIGNATURE,
                ARTIFACT_SIGNATURE,
                "growth-email.v2.json"));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(
            head("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact")
                .header(HttpHeaders.IF_NONE_MATCH, "W/\"sha256-stale\", \"sha256-abc123\""))
        .andExpect(status().isNotModified())
        .andExpect(
            header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable"))
        .andExpect(header().string(HttpHeaders.ETAG, "\"sha256-abc123\""))
        .andExpect(header().string(CmsContentWS.SNAPSHOT_SIGNATURE_HEADER, SNAPSHOT_SIGNATURE))
        .andExpect(header().string(CmsContentWS.ARTIFACT_SIGNATURE_HEADER, ARTIFACT_SIGNATURE));
  }

  @Test
  public void getSnapshotArtifactDoesNotExposeRowIdRoute() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/publish-snapshots/12/artifact"))
        .andExpect(status().isNotFound());
  }

  @Test
  public void getVersionedSnapshotArtifactUsesCanonicalProjectVersionLookup() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    String artifactJson = "{\"project\":\"growth\"}";
    byte[] artifactBytes = artifactJson.getBytes(StandardCharsets.UTF_8);
    when(cmsContentService.getSnapshotArtifact("growth-email", 2))
        .thenReturn(
            new CmsContentService.SnapshotArtifact(
                artifactJson,
                "abc123",
                (long) artifactBytes.length,
                "test-v1",
                SNAPSHOT_SIGNATURE,
                ARTIFACT_SIGNATURE,
                "growth-email.v2.json"));

    ResponseEntity<byte[]> response =
        cmsContentWS.getVersionedSnapshotArtifact("growth-email", 2, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getBody()).containsExactly(artifactBytes);
  }

  @Test
  public void getLatestPublishedSnapshotUsesStableProjectKeyLookup() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot("growth-email", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotReturnsNotModifiedForMatchingIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email",
            "W/\"snapshot-signature-stale\", \"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isNull();
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotHeadReturnsDescriptorHeaders() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(snapshotDeliveryDescriptor());
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(head("/api/content-cms/projects/growth-email/publish-snapshots/latest"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            header().string(HttpHeaders.ETAG, "\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\""));

    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotHeadReturnsNotModifiedForMatchingIfNoneMatch()
      throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(snapshotDeliveryDescriptor());
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(
            head("/api/content-cms/projects/growth-email/publish-snapshots/latest")
                .header(
                    HttpHeaders.IF_NONE_MATCH,
                    "W/\"snapshot-signature-stale\", \"snapshot-signature-"
                        + SNAPSHOT_SIGNATURE
                        + "\""))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(
            header().string(HttpHeaders.ETAG, "\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\""));

    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotDoesNotTreatArtifactEtagAsDescriptorEtag() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot("growth-email", "\"sha256-abc123\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotSerializesProviderNeutralDescriptor() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(snapshotDeliveryDescriptor());
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/latest"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.formatVersion").value("mojito.microCms.snapshot-delivery-descriptor.v1"))
        .andExpect(jsonPath("$.projectHint").value("BLOB_CDN"))
        .andExpect(jsonPath("$.snapshotSigningKeyId").value("test-v1"))
        .andExpect(jsonPath("$.snapshotSignature").value(SNAPSHOT_SIGNATURE))
        .andExpect(jsonPath("$.artifactSignature").value(ARTIFACT_SIGNATURE))
        .andExpect(jsonPath("$.publishedAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.projectId").doesNotExist())
        .andExpect(jsonPath("$.createdByUsername").doesNotExist());

    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getProjectCompletenessSerializesPublishPackageByteSize() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getProjectCompleteness(12L, List.of()))
        .thenReturn(projectCompletenessView());
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/12/completeness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishPackageByteSize").value(512));
  }

  @Test
  public void mutableAdminReadsUseNoStoreCacheControl() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SearchProjectsView projects =
        new CmsContentService.SearchProjectsView(List.of(), 0);
    CmsContentService.ProjectDetail project =
        new CmsContentService.ProjectDetail(
            null, AUTHORING_SHA256, List.of(), List.of(), List.of());
    CmsContentService.EntryCompletenessView completeness =
        new CmsContentService.EntryCompletenessView(12L, "welcome", List.of());
    CmsContentService.ProjectCompletenessView projectCompleteness = projectCompletenessView();
    when(cmsContentService.searchProjects(null, null, null)).thenReturn(projects);
    when(cmsContentService.getProject(12L)).thenReturn(project);
    when(cmsContentService.getEntryCompleteness(12L, List.of("fr-FR", "ja-JP")))
        .thenReturn(completeness);
    when(cmsContentService.getProjectCompleteness(12L, List.of("fr-FR", "ja-JP")))
        .thenReturn(projectCompleteness);

    assertNoStore(cmsContentWS.searchProjects(null, null, null), projects);
    assertNoStore(cmsContentWS.getProject(12L), project);
    assertNoStore(cmsContentWS.getEntryCompleteness(12L, "fr-FR, ja-JP"), completeness);
    assertNoStore(cmsContentWS.getProjectCompleteness(12L, "fr-FR, ja-JP"), projectCompleteness);
  }

  @Test
  public void getEntryCompletenessPreservesBlankLocaleCsvTokenForValidation() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    when(cmsContentService.getEntryCompleteness(12L, List.of("fr-FR", "", "ja-JP")))
        .thenThrow(new IllegalArgumentException("Locale tags must not contain blank values"));

    assertThatThrownBy(() -> cmsContentWS.getEntryCompleteness(12L, "fr-FR,,ja-JP"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(cmsContentService).getEntryCompleteness(12L, List.of("fr-FR", "", "ja-JP"));
  }

  @Test
  public void getProjectCompletenessPreservesBlankLocaleCsvTokenForValidation() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    when(cmsContentService.getProjectCompleteness(12L, List.of("fr-FR", "", "ja-JP")))
        .thenThrow(new IllegalArgumentException("Locale tags must not contain blank values"));

    assertThatThrownBy(() -> cmsContentWS.getProjectCompleteness(12L, "fr-FR,,ja-JP"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(cmsContentService).getProjectCompleteness(12L, List.of("fr-FR", "", "ja-JP"));
  }

  @Test
  public void dataIntegrityViolationReturnsConflict() {
    CmsContentWS cmsContentWS = new CmsContentWS(mock(CmsContentService.class));

    assertThatThrownBy(
            () ->
                cmsContentWS.handleDataIntegrityViolation(
                    new DataIntegrityViolationException("duplicate CMS key")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  private <T> void assertNoStore(ResponseEntity<T> response, T body) {
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody()).isEqualTo(body);
  }

  private CmsContentService.SnapshotDeliveryDescriptor snapshotDeliveryDescriptor() {
    return new CmsContentService.SnapshotDeliveryDescriptor(
        "mojito.microCms.snapshot-delivery-descriptor.v1",
        "growth-email",
        2,
        com.box.l10n.mojito.entity.cms.CmsPublishSnapshot.Status.PUBLISHED,
        List.of("en", "fr-FR"),
        "BLOB_CDN",
        "abc123",
        20L,
        "test-v1",
        SNAPSHOT_SIGNATURE,
        ARTIFACT_SIGNATURE,
        "growth-email.v2.json",
        "/api/content-cms/projects/growth-email/publish-snapshots/2/artifact",
        "2026-01-01T00:00:00Z");
  }

  private CmsContentService.ProjectCompletenessView projectCompletenessView() {
    return new CmsContentService.ProjectCompletenessView(
        12L,
        "growth-email",
        AUTHORING_SHA256,
        PACKAGE_SHA256,
        512L,
        List.of(),
        List.of(),
        List.of(),
        true);
  }

  private CmsContentService.PublishSnapshotView publishSnapshotView() {
    return new CmsContentService.PublishSnapshotView(
        12L,
        1L,
        2,
        com.box.l10n.mojito.entity.cms.CmsPublishSnapshot.Status.PUBLISHED,
        List.of("en", "fr-FR"),
        "abc123",
        20L,
        "test-v1",
        SNAPSHOT_SIGNATURE,
        ARTIFACT_SIGNATURE,
        "growth-email.v2.json",
        "/api/content-cms/projects/growth-email/publish-snapshots/2/artifact",
        "admin",
        "2026-01-01T00:00:00Z");
  }
}
