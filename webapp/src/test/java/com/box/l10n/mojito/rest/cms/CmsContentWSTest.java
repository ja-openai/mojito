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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.service.cms.CmsContentConflictException;
import com.box.l10n.mojito.service.cms.CmsContentNotFoundException;
import com.box.l10n.mojito.service.cms.CmsContentService;
import com.box.l10n.mojito.service.cms.CmsSnapshotSigningService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
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
  public void createFirstCopyBlockPassesAtomicAuthoringCommand() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.FirstCopyBlockCommand command =
        new CmsContentService.FirstCopyBlockCommand(
            "welcome-email", "Welcome email", "Signup email", "copy", "Welcome", "Headline");
    when(cmsContentService.createFirstCopyBlock(12L, command)).thenReturn(null);

    ResponseEntity<CmsContentService.ProjectDetail> response =
        cmsContentWS.createFirstCopyBlock(12L, command);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verify(cmsContentService).createFirstCopyBlock(12L, command);
  }

  @Test
  public void addTargetLocalesPassesAuthoringCommand() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.TargetLocalesCommand command =
        new CmsContentService.TargetLocalesCommand(List.of("fr-FR", "ja-JP"));
    when(cmsContentService.addTargetLocales(12L, command)).thenReturn(null);

    ResponseEntity<CmsContentService.ProjectDetail> response =
        cmsContentWS.addTargetLocales(12L, command);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verify(cmsContentService).addTargetLocales(12L, command);
  }

  @Test
  public void makeEntryCopyPiecesPrivatePassesAuthoringCommand() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.EntryCopyPiecesPrivateCommand command =
        new CmsContentService.EntryCopyPiecesPrivateCommand(3L);
    when(cmsContentService.makeEntryCopyPiecesPrivate(12L, command)).thenReturn(null);

    ResponseEntity<CmsContentService.ProjectDetail> response =
        cmsContentWS.makeEntryCopyPiecesPrivate(12L, command);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verify(cmsContentService).makeEntryCopyPiecesPrivate(12L, command);
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
  public void getFieldTranslationPassesAuthoringLookup() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    TextUnitDTO translation = new TextUnitDTO();
    when(cmsContentService.getFieldTranslation(12L, "fr-FR")).thenReturn(translation);

    ResponseEntity<TextUnitDTO> response = cmsContentWS.getFieldTranslation(12L, "fr-FR");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody()).isSameAs(translation);
    verify(cmsContentService).getFieldTranslation(12L, "fr-FR");
  }

  @Test
  public void getProjectReturnsNotFoundForTypedMissingCmsResource() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    when(cmsContentService.getProject(12L))
        .thenThrow(new CmsContentNotFoundException("Content project not found: 12"));

    assertThatThrownBy(() -> cmsContentWS.getProject(12L))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  public void getProjectDoesNotInferNotFoundFromValidationMessageText() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    when(cmsContentService.getProject(12L))
        .thenThrow(
            new IllegalArgumentException("Metadata schema path not found in configured schema"));

    assertThatThrownBy(() -> cmsContentWS.getProject(12L))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
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
  public void addTargetLocalesRejectsNullLocaleTagsBeforeServiceCall() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(
            patch("/api/content-cms/projects/12/target-locales")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"localeTags\":null}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).addTargetLocales(anyLong(), any());
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
        cmsContentWS.publishProject(12L, List.of("publish-request"), command);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation())
        .hasToString("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact");
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
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
  public void publishProjectRejectsDuplicatePublishRequestKeyBeforeServiceCall() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(
            post("/api/content-cms/projects/12/publish-snapshots")
                .header(CmsContentWS.PUBLISH_REQUEST_KEY_HEADER, "publish-request", "other-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedAuthoringSha256\":\""
                        + AUTHORING_SHA256
                        + "\",\"expectedPackageSha256\":\""
                        + PACKAGE_SHA256
                        + "\"}"))
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
        .andExpect(jsonPath("$.publishRequestLocaleTags[0]").value("fr-FR"))
        .andExpect(jsonPath("$.publishRequestAuthoringSha256").value(AUTHORING_SHA256))
        .andExpect(jsonPath("$.publishRequestPackageSha256").value(PACKAGE_SHA256))
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
        cmsContentWS.getVersionedSnapshotArtifact(
            "growth-email", "2", artifactRequest("growth-email", "2"), null);

    assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
        .isEqualTo("private, max-age=31536000, immutable");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .isEqualTo("attachment; filename=\"growth-email.v2.json\"");
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getHeaders().getFirst(CmsContentWS.SNAPSHOT_SIGNING_KEY_ID_HEADER))
        .isEqualTo("test-v1");
    assertThat(response.getHeaders().getFirst(CmsContentWS.SIGNATURE_ALGORITHM_HEADER))
        .isEqualTo(CmsSnapshotSigningService.SIGNATURE_ALGORITHM);
    assertThat(response.getHeaders().getFirst(CmsContentWS.SNAPSHOT_SIGNATURE_VERSION_HEADER))
        .isEqualTo(CmsSnapshotSigningService.SNAPSHOT_SIGNATURE_VERSION);
    assertThat(response.getHeaders().getFirst(CmsContentWS.SNAPSHOT_SIGNATURE_HEADER))
        .isEqualTo(SNAPSHOT_SIGNATURE);
    assertThat(response.getHeaders().getFirst(CmsContentWS.ARTIFACT_SIGNATURE_VERSION_HEADER))
        .isEqualTo(CmsSnapshotSigningService.ARTIFACT_SIGNATURE_VERSION);
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
            "growth-email",
            "2",
            artifactRequest("growth-email", "2"),
            "W/\"sha256-stale\", \"sha256-abc123\"");

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
  public void getVersionedSnapshotArtifactReturnsNotModifiedForWildcardIfNoneMatch() {
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
            "growth-email", "2", artifactRequest("growth-email", "2"), "*");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getBody()).isNull();
  }

  @Test
  public void getVersionedSnapshotArtifactIgnoresMalformedWeakWildcardIfNoneMatch() {
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
        cmsContentWS.getVersionedSnapshotArtifact(
            "growth-email", "2", artifactRequest("growth-email", "2"), "W/*");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getBody()).containsExactly(artifactBytes);
  }

  @Test
  public void getVersionedSnapshotArtifactIgnoresMalformedWildcardListIfNoneMatch() {
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
        cmsContentWS.getVersionedSnapshotArtifact(
            "growth-email", "2", artifactRequest("growth-email", "2"), "*, \"sha256-abc123\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getBody()).containsExactly(artifactBytes);
  }

  @Test
  public void getVersionedSnapshotArtifactIgnoresMalformedEntityTagListIfNoneMatch() {
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
        cmsContentWS.getVersionedSnapshotArtifact(
            "growth-email", "2", artifactRequest("growth-email", "2"), "bogus, \"sha256-abc123\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getFirst(HttpHeaders.ETAG)).isEqualTo("\"sha256-abc123\"");
    assertThat(response.getBody()).containsExactly(artifactBytes);
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
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string(HttpHeaders.ETAG, "\"sha256-abc123\""))
        .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, artifactBytes.length))
        .andExpect(
            header()
                .string(
                    CmsContentWS.SIGNATURE_ALGORITHM_HEADER,
                    CmsSnapshotSigningService.SIGNATURE_ALGORITHM))
        .andExpect(
            header()
                .string(
                    CmsContentWS.SNAPSHOT_SIGNATURE_VERSION_HEADER,
                    CmsSnapshotSigningService.SNAPSHOT_SIGNATURE_VERSION))
        .andExpect(header().string(CmsContentWS.SNAPSHOT_SIGNATURE_HEADER, SNAPSHOT_SIGNATURE))
        .andExpect(
            header()
                .string(
                    CmsContentWS.ARTIFACT_SIGNATURE_VERSION_HEADER,
                    CmsSnapshotSigningService.ARTIFACT_SIGNATURE_VERSION))
        .andExpect(header().string(CmsContentWS.ARTIFACT_SIGNATURE_HEADER, ARTIFACT_SIGNATURE));
  }

  @Test
  public void getVersionedSnapshotArtifactHeadDoesNotMaterializeResponseBody() {
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
        cmsContentWS.headVersionedSnapshotArtifact(
            "growth-email", "2", artifactRequest("growth-email", "2"), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentLength()).isEqualTo((long) artifactBytes.length);
    assertThat(response.getBody()).isNull();
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
  public void getVersionedSnapshotArtifactRejectsNonCanonicalSnapshotVersionLocator()
      throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/02/artifact"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).getSnapshotArtifact(any(), any());
  }

  @Test
  public void getVersionedSnapshotArtifactRejectsPercentEncodedSnapshotVersionLocator()
      throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/%32/artifact"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).getSnapshotArtifact(any(), any());
  }

  @Test
  public void getVersionedSnapshotArtifactRejectsQueryStringAlias() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact?x=1"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).getSnapshotArtifact(any(), any());
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
        cmsContentWS.getVersionedSnapshotArtifact(
            "growth-email", "2", artifactRequest("growth-email", "2"), null);

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
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email", latestSnapshotRequest("growth-email"), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotRejectsPercentEncodedProjectKeyLocator() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/%67rowth-email/publish-snapshots/latest"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).getLatestPublishedSnapshotDescriptor(any());
  }

  @Test
  public void getLatestPublishedSnapshotRejectsQueryStringAlias() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/latest?x=1"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).getLatestPublishedSnapshotDescriptor(any());
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
            latestSnapshotRequest("growth-email"),
            "W/\"snapshot-signature-stale\", \"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isNull();
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotReturnsNotModifiedForWildcardIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email", latestSnapshotRequest("growth-email"), "*");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isNull();
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotIgnoresMalformedWeakWildcardIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email", latestSnapshotRequest("growth-email"), "W/*");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotIgnoresMalformedWildcardListIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email",
            latestSnapshotRequest("growth-email"),
            "*, \"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotIgnoresMalformedEntityTagListIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email",
            latestSnapshotRequest("growth-email"),
            "bogus, \"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotIgnoresMalformedWeakEtagWhitespaceIfNoneMatch() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SnapshotDeliveryDescriptor descriptor = snapshotDeliveryDescriptor();
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(descriptor);

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email",
            latestSnapshotRequest("growth-email"),
            "W/ \"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isEqualTo(descriptor);
    verify(cmsContentService).getLatestPublishedSnapshotDescriptor("growth-email");
  }

  @Test
  public void cmsErrorResponsesUseNoStoreCacheControl() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenThrow(new CmsContentNotFoundException("Published snapshot not found: growth-email"));
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService))
            .addFilters(new CmsContentCacheControlFilter())
            .build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/latest"))
        .andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  public void cmsNamespaceMissUsesNoStoreCacheControl() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(mock(CmsContentService.class)))
            .addFilters(new CmsContentCacheControlFilter())
            .build();

    mockMvc
        .perform(get("/api/content-cms"))
        .andExpect(status().isNotFound())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
  }

  @Test
  public void exactArtifactResponseOverridesDefaultCmsNoStoreCacheControl() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getSnapshotArtifact("growth-email", 2)).thenReturn(snapshotArtifact());
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService))
            .addFilters(new CmsContentCacheControlFilter())
            .build();

    mockMvc
        .perform(get("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact"))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable"));
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
  public void getLatestPublishedSnapshotHeadDoesNotExposeResponseBody() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    when(cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email"))
        .thenReturn(snapshotDeliveryDescriptor());

    ResponseEntity<CmsContentService.SnapshotDeliveryDescriptor> response =
        cmsContentWS.headLatestPublishedSnapshot(
            "growth-email", latestSnapshotRequest("growth-email"), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getHeaders().getETag())
        .isEqualTo("\"snapshot-signature-" + SNAPSHOT_SIGNATURE + "\"");
    assertThat(response.getBody()).isNull();
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
        cmsContentWS.getLatestPublishedSnapshot(
            "growth-email", latestSnapshotRequest("growth-email"), "\"sha256-abc123\"");

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
        .andExpect(jsonPath("$.artifactSha256").value("abc123"))
        .andExpect(jsonPath("$.artifactByteSize").value(20))
        .andExpect(
            jsonPath("$.signatureAlgorithm").value(CmsSnapshotSigningService.SIGNATURE_ALGORITHM))
        .andExpect(
            jsonPath("$.snapshotSignatureVersion")
                .value(CmsSnapshotSigningService.SNAPSHOT_SIGNATURE_VERSION))
        .andExpect(
            jsonPath("$.artifactSignatureVersion")
                .value(CmsSnapshotSigningService.ARTIFACT_SIGNATURE_VERSION))
        .andExpect(jsonPath("$.snapshotSigningKeyId").value("test-v1"))
        .andExpect(jsonPath("$.snapshotSignature").value(SNAPSHOT_SIGNATURE))
        .andExpect(jsonPath("$.artifactSignature").value(ARTIFACT_SIGNATURE))
        .andExpect(jsonPath("$.artifactFilename").value("growth-email.v2.json"))
        .andExpect(
            jsonPath("$.artifactExportPath")
                .value("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact"))
        .andExpect(jsonPath("$.publishedAt").value("2026-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.projectId").doesNotExist())
        .andExpect(jsonPath("$.createdByUsername").doesNotExist())
        .andExpect(jsonPath("$.snapshotSigningKeys").doesNotExist())
        .andExpect(jsonPath("$.snapshotSigningKey").doesNotExist());

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
        .andExpect(jsonPath("$.publishPackageByteSize").value(512))
        .andExpect(jsonPath("$.releaseChangeSummary.changes").isArray())
        .andExpect(jsonPath("$.releaseChangeSummary.changes[0].kind").value("TRANSLATION_CHANGED"))
        .andExpect(jsonPath("$.releaseChangeSummary.changes[0].entryId").value(12))
        .andExpect(jsonPath("$.releaseChangeSummary.changes[0].fieldId").value(21))
        .andExpect(
            jsonPath("$.releaseChangeSummary.changes[0].lastReleasedSourceContent").isEmpty())
        .andExpect(
            jsonPath("$.releaseChangeSummary.changes[0].lastReleasedTranslationContent")
                .value("Bonjour before release"))
        .andExpect(jsonPath("$.releaseChangeSummary.hasMore").value(false))
        .andExpect(jsonPath("$.releaseChangeSummary.actionNeededCount").value(0));
  }

  @Test
  public void getProjectReleaseChangesSerializesExactDiff() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentService.ReleaseChangeSummaryView releaseChangeSummary =
        new CmsContentService.ReleaseChangeSummaryView(
            List.of(
                new CmsContentService.ReleaseChangeView(
                    CmsContentService.ReleaseChangeKind.SOURCE_COPY_CHANGED,
                    12L,
                    "Welcome",
                    21L,
                    "Header",
                    null,
                    "Hello before release",
                    null)),
            false,
            0);
    when(cmsContentService.getProjectReleaseChanges(12L, List.of("fr-FR")))
        .thenReturn(releaseChangeSummary);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/12/release-changes?locales=fr-FR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes").isArray())
        .andExpect(jsonPath("$.changes[0].kind").value("SOURCE_COPY_CHANGED"))
        .andExpect(jsonPath("$.changes[0].entryId").value(12))
        .andExpect(jsonPath("$.changes[0].fieldId").value(21))
        .andExpect(jsonPath("$.changes[0].lastReleasedSourceContent").value("Hello before release"))
        .andExpect(jsonPath("$.changes[0].lastReleasedTranslationContent").isEmpty())
        .andExpect(jsonPath("$.hasMore").value(false))
        .andExpect(jsonPath("$.actionNeededCount").value(0));
  }

  @Test
  public void getEntryCompletenessSerializesFieldReadiness() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentService.LocaleCompleteness french =
        new CmsContentService.LocaleCompleteness("fr-FR", 1, 0, 0, 0, 1, false);
    when(cmsContentService.getEntryCompleteness(12L, List.of()))
        .thenReturn(
            new CmsContentService.EntryCompletenessView(
                12L,
                "welcome",
                List.of(french),
                List.of(new CmsContentService.FieldCompleteness(21L, "header", List.of(french)))));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/entries/12/completeness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fields[0].fieldId").value(21))
        .andExpect(jsonPath("$.fields[0].fieldKey").value("header"))
        .andExpect(jsonPath("$.fields[0].locales[0].translationNeededFields").value(1));
  }

  @Test
  public void getProjectPublishSnapshotsReturnsMetadataHistoryPage() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getProjectPublishSnapshots(12L, 4, 10))
        .thenReturn(
            new CmsContentService.PublishSnapshotHistoryView(
                List.of(publishSnapshotView()), true, 2));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/12/publish-snapshots?beforeVersion=4&limit=10"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.snapshots[0].snapshotVersion").value(2))
        .andExpect(jsonPath("$.snapshots[0].publishRequestLocaleTags[0]").value("fr-FR"))
        .andExpect(jsonPath("$.snapshots[0].publishRequestAuthoringSha256").value(AUTHORING_SHA256))
        .andExpect(jsonPath("$.snapshots[0].publishRequestPackageSha256").value(PACKAGE_SHA256))
        .andExpect(jsonPath("$.hasMore").value(true))
        .andExpect(jsonPath("$.nextBeforeSnapshotVersion").value(2));
  }

  @Test
  public void getProjectReturnsFirstPublishSnapshotHistoryCursor() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    when(cmsContentService.getProject(12L))
        .thenReturn(
            new CmsContentService.ProjectDetail(
                null, AUTHORING_SHA256, List.of(), List.of(), List.of(), true, 2));
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService)).build();

    mockMvc
        .perform(get("/api/content-cms/projects/12"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
        .andExpect(jsonPath("$.hasMorePublishSnapshots").value(true))
        .andExpect(jsonPath("$.nextBeforePublishSnapshotVersion").value(2));
  }

  @Test
  public void mutableAdminReadsUseNoStoreCacheControl() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.SearchProjectsView projects =
        new CmsContentService.SearchProjectsView(List.of(), 0);
    CmsContentService.ProjectDetail project =
        new CmsContentService.ProjectDetail(
            null, AUTHORING_SHA256, List.of(), List.of(), List.of(), false, null);
    CmsContentService.EntryCompletenessView completeness =
        new CmsContentService.EntryCompletenessView(12L, "welcome", List.of(), List.of());
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
  public void mutableAdminWritesUseNoStoreCacheControl() {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    CmsContentWS cmsContentWS = new CmsContentWS(cmsContentService);
    CmsContentService.ProjectDetail project =
        new CmsContentService.ProjectDetail(
            null, AUTHORING_SHA256, List.of(), List.of(), List.of(), false, null);
    when(cmsContentService.createProject(null)).thenReturn(project);
    when(cmsContentService.updateProject(12L, null)).thenReturn(project);
    when(cmsContentService.createContentType(12L, null)).thenReturn(project);
    when(cmsContentService.updateContentType(12L, null)).thenReturn(project);
    when(cmsContentService.createContentTypeField(12L, null)).thenReturn(project);
    when(cmsContentService.updateContentTypeField(12L, null)).thenReturn(project);
    when(cmsContentService.createEntry(12L, null)).thenReturn(project);
    when(cmsContentService.updateEntry(12L, null)).thenReturn(project);
    when(cmsContentService.createVariant(12L, null)).thenReturn(project);
    when(cmsContentService.updateVariant(12L, null)).thenReturn(project);
    when(cmsContentService.upsertFieldMapping(12L, null)).thenReturn(project);
    when(cmsContentService.unmapFieldMapping(12L, null)).thenReturn(project);

    assertNoStore(cmsContentWS.createProject(null), HttpStatus.CREATED, project);
    assertNoStore(cmsContentWS.updateProject(12L, null), project);
    assertNoStore(cmsContentWS.createContentType(12L, null), HttpStatus.CREATED, project);
    assertNoStore(cmsContentWS.updateContentType(12L, null), project);
    assertNoStore(cmsContentWS.createContentTypeField(12L, null), HttpStatus.CREATED, project);
    assertNoStore(cmsContentWS.updateContentTypeField(12L, null), project);
    assertNoStore(cmsContentWS.createEntry(12L, null), HttpStatus.CREATED, project);
    assertNoStore(cmsContentWS.updateEntry(12L, null), project);
    assertNoStore(cmsContentWS.createVariant(12L, null), HttpStatus.CREATED, project);
    assertNoStore(cmsContentWS.updateVariant(12L, null), project);
    assertNoStore(cmsContentWS.upsertFieldMapping(12L, null), project);
    assertNoStore(cmsContentWS.unmapFieldMapping(12L, null), project);
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
    assertNoStore(response, HttpStatus.OK, body);
  }

  private <T> void assertNoStore(ResponseEntity<T> response, HttpStatus status, T body) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    assertThat(response.getBody()).isEqualTo(body);
  }

  private CmsContentService.SnapshotArtifact snapshotArtifact() {
    String artifactJson = "{\"project\":\"growth\"}";
    return new CmsContentService.SnapshotArtifact(
        artifactJson,
        "abc123",
        (long) artifactJson.getBytes(StandardCharsets.UTF_8).length,
        "test-v1",
        SNAPSHOT_SIGNATURE,
        ARTIFACT_SIGNATURE,
        "growth-email.v2.json");
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
        CmsSnapshotSigningService.SIGNATURE_ALGORITHM,
        CmsSnapshotSigningService.SNAPSHOT_SIGNATURE_VERSION,
        CmsSnapshotSigningService.ARTIFACT_SIGNATURE_VERSION,
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
        true,
        new CmsContentService.ReleaseChangeSummaryView(
            List.of(
                new CmsContentService.ReleaseChangeView(
                    CmsContentService.ReleaseChangeKind.TRANSLATION_CHANGED,
                    12L,
                    "Welcome",
                    21L,
                    "Header",
                    "fr-FR",
                    null,
                    "Bonjour before release")),
            false,
            0));
  }

  private CmsContentService.PublishSnapshotView publishSnapshotView() {
    return new CmsContentService.PublishSnapshotView(
        12L,
        1L,
        2,
        com.box.l10n.mojito.entity.cms.CmsPublishSnapshot.Status.PUBLISHED,
        List.of("en", "fr-FR"),
        List.of("fr-FR"),
        AUTHORING_SHA256,
        PACKAGE_SHA256,
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

  private MockHttpServletRequest latestSnapshotRequest(String projectKey) {
    return request("/api/content-cms/projects/" + projectKey + "/publish-snapshots/latest");
  }

  private MockHttpServletRequest artifactRequest(String projectKey, String snapshotVersion) {
    return request(
        "/api/content-cms/projects/"
            + projectKey
            + "/publish-snapshots/"
            + snapshotVersion
            + "/artifact");
  }

  private MockHttpServletRequest request(String requestUri) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI(requestUri);
    return request;
  }
}
