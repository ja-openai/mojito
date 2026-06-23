package com.box.l10n.mojito.service.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.cms.CmsAuditableEntity;
import com.box.l10n.mojito.entity.cms.CmsContentEntry;
import com.box.l10n.mojito.entity.cms.CmsContentEntryVariant;
import com.box.l10n.mojito.entity.cms.CmsContentFieldMapping;
import com.box.l10n.mojito.entity.cms.CmsContentProject;
import com.box.l10n.mojito.entity.cms.CmsContentType;
import com.box.l10n.mojito.entity.cms.CmsContentTypeField;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshotSeal;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.okapi.TextUnitUtils;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.assetExtraction.AssetTextUnitToTMTextUnitRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantMutationLockService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.data.domain.PageRequest;

public class CmsContentServiceTest {

  private static final String VALID_PACKAGE_SHA256 = "b".repeat(64);

  @Rule public OutputCaptureRule outputCapture = new OutputCaptureRule();

  private final CmsContentProjectRepository projectRepository =
      mock(CmsContentProjectRepository.class);
  private final CmsContentTypeRepository contentTypeRepository =
      mock(CmsContentTypeRepository.class);
  private final CmsContentTypeFieldRepository fieldRepository =
      mock(CmsContentTypeFieldRepository.class);
  private final CmsContentEntryRepository entryRepository = mock(CmsContentEntryRepository.class);
  private final CmsContentEntryVariantRepository variantRepository =
      mock(CmsContentEntryVariantRepository.class);
  private final CmsContentFieldMappingRepository mappingRepository =
      mock(CmsContentFieldMappingRepository.class);
  private final CmsPublishSnapshotRepository snapshotRepository =
      mock(CmsPublishSnapshotRepository.class);
  private final CmsPublishSnapshotSealRepository snapshotSealRepository =
      mock(CmsPublishSnapshotSealRepository.class);
  private final RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
  private final AssetRepository assetRepository = mock(AssetRepository.class);
  private final VirtualAssetService virtualAssetService = mock(VirtualAssetService.class);
  private final VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService =
      mock(VirtualTextUnitBatchUpdaterService.class);
  private final AssetTextUnitToTMTextUnitRepository assetTextUnitToTMTextUnitRepository =
      mock(AssetTextUnitToTMTextUnitRepository.class);
  private final TMTextUnitRepository tmTextUnitRepository = mock(TMTextUnitRepository.class);
  private final TMTextUnitCurrentVariantMutationLockService
      tmTextUnitCurrentVariantMutationLockService =
          mock(TMTextUnitCurrentVariantMutationLockService.class);
  private final TextUnitUtils textUnitUtils = mock(TextUnitUtils.class);
  private final UserService userService = mock(UserService.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final CmsContentConfigurationProperties configurationProperties =
      contentCmsConfigurationProperties();
  private final CmsSnapshotSigningService snapshotSigningService =
      new CmsSnapshotSigningService(configurationProperties);

  private final CmsContentService service =
      new CmsContentService(
          projectRepository,
          contentTypeRepository,
          fieldRepository,
          entryRepository,
          variantRepository,
          mappingRepository,
          snapshotRepository,
          snapshotSealRepository,
          repositoryRepository,
          assetRepository,
          virtualAssetService,
          virtualTextUnitBatchUpdaterService,
          assetTextUnitToTMTextUnitRepository,
          tmTextUnitRepository,
          tmTextUnitCurrentVariantMutationLockService,
          textUnitUtils,
          userService,
          objectMapper,
          snapshotSigningService,
          configurationProperties);

  @Test
  public void searchProjectsRejectsNonAdminBeforeReadingCmsData() {
    when(userService.isCurrentUserAdmin()).thenReturn(false);

    assertThatThrownBy(() -> service.searchProjects(null, null, null))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
        .hasMessageContaining("Admin role required");
    verify(projectRepository, never()).search(any(), any(), any());
  }

  @Test
  public void snapshotDeliveryReaderCanExportExactSnapshotWithoutAdminRole() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(userService.isCurrentUserAdmin()).thenReturn(false);
    when(userService.isCurrentUserCmsDelivery()).thenReturn(true);
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotArtifact artifact = service.getSnapshotArtifact("growth-email", 2);

    assertThat(artifact.filename()).isEqualTo("growth-email.v2.json");
  }

  @Test
  public void snapshotDeliveryReaderCannotUseAuthoringSearchWithoutAdminRole() {
    setupFixture();
    when(userService.isCurrentUserAdmin()).thenReturn(false);
    when(userService.isCurrentUserCmsDelivery()).thenReturn(true);

    assertThatThrownBy(() -> service.searchProjects(null, null, null))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
        .hasMessageContaining("Admin role required");
    verify(projectRepository, never()).search(any(), any(), any());
  }

  @Test
  public void publishProjectBuildsProviderNeutralArtifactWhenLocalesAreComplete() throws Exception {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(0);
    when(snapshotRepository.save(any(CmsPublishSnapshot.class)))
        .thenAnswer(
            invocation -> {
              CmsPublishSnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(99L);
              User publisher = new User();
              publisher.setId(100L);
              publisher.setUsername("admin");
              snapshot.setCreatedByUser(publisher);
              snapshot.setCreatedDate(ZonedDateTime.parse("2026-01-01T00:00:00Z"));
              fixture.savedSnapshot = snapshot;
              return snapshot;
            });
    when(snapshotSealRepository.save(any(CmsPublishSnapshotSeal.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    CmsContentService.PublishSnapshotView view =
        publishProject(fixture.project.getId(), validatedPublishCommand(fixture, List.of("fr-FR")));

    verify(projectRepository, times(2))
        .findByIdWithRepositoryAndAssetForUpdate(fixture.project.getId());
    verify(snapshotRepository, times(2)).findCurrentVariantRows(any(), any());
    verify(tmTextUnitCurrentVariantMutationLockService)
        .lockTextUnits(Set.of(fixture.tmTextUnit.getId()));
    verify(snapshotRepository)
        .lockCurrentVariantRows(Set.of(fixture.tmTextUnit.getId()), List.of("en", "fr-FR"));
    assertThat(view.snapshotVersion()).isEqualTo(1);
    assertThat(view.localeTags()).containsExactly("en", "fr-FR");
    assertThat(view.artifactSha256()).isEqualTo(fixture.savedSnapshot.getArtifactSha256());
    assertThat(view.artifactByteSize()).isEqualTo(fixture.savedSnapshot.getArtifactByteSize());
    assertThat(view.snapshotSigningKeyId()).isEqualTo("test-v1");
    assertThat(view.snapshotSignature()).isEqualTo(fixture.savedSnapshot.getSnapshotSignature());
    assertThat(view.artifactSignature()).isEqualTo(fixture.savedSnapshot.getArtifactSignature());
    assertThat(view.artifactFilename()).isEqualTo("growth-email.v1.json");
    assertThat(view.artifactExportPath())
        .isEqualTo("/api/content-cms/projects/growth-email/publish-snapshots/1/artifact");
    assertThat(view.createdByUsername()).isEqualTo("admin");
    assertThat(fixture.savedSnapshot.getArtifactSha256())
        .isEqualTo(DigestUtils.sha256Hex(fixture.savedSnapshot.getArtifactJson()));
    assertThat(fixture.savedSnapshot.getArtifactByteSize())
        .isEqualTo(
            (long) fixture.savedSnapshot.getArtifactJson().getBytes(StandardCharsets.UTF_8).length);
    assertThat(fixture.savedSnapshot.getSnapshotSignature()).hasSize(64);
    assertThat(fixture.savedSnapshot.getArtifactSignature()).hasSize(64);
    assertThat(fixture.savedSnapshot.getPublishRequestKey()).isNotBlank();
    assertThat(fixture.savedSnapshot.getPublishRequestLocaleTags()).isEqualTo("fr-FR");
    assertThat(fixture.savedSnapshot.getPublishRequestAuthoringSha256()).hasSize(64);
    assertThat(fixture.savedSnapshot.getPublishRequestPackageSha256()).hasSize(64);

    String artifactJson = fixture.savedSnapshot.getArtifactJson();
    assertThat(artifactJson)
        .contains(
            "\"delivery\":{\"runtimeDependency\":\"none\",\"projectHint\":\"BLOB_CDN\",\"supportedTargets\":[");
    assertThat(artifactJson)
        .contains(
            "\"project\":{\"key\":\"growth-email\",\"name\":\"Growth email\",\"sourceLocale\":\"en\"}");
    assertThat(artifactJson)
        .contains(
            "\"fields\":[{\"key\":\"header\",\"name\":\"Header\",\"type\":\"TEXT\",\"localizable\":true,\"required\":true}]");

    JsonNode artifact = objectMapper.readTree(artifactJson);
    assertThat(artifact.path("formatVersion").asText()).isEqualTo("mojito.microCms.v1");
    assertThat(Instant.parse(artifact.path("generatedAt").asText())).isNotNull();
    assertThat(artifact.path("generatedAt").asText()).endsWith("Z");
    assertThat(artifact.path("generatedAt").asText())
        .isEqualTo(fixture.savedSnapshot.getPublishedAt());
    assertThat(artifact.path("delivery").path("runtimeDependency").asText()).isEqualTo("none");
    List<String> supportedTargets = new ArrayList<>();
    artifact
        .path("delivery")
        .path("supportedTargets")
        .forEach(node -> supportedTargets.add(node.asText()));
    assertThat(supportedTargets)
        .contains("statsig-dynamic-config", "blob-cdn", "experience-framework");
    assertThat(artifact.path("project").path("key").asText()).isEqualTo("growth-email");
    assertThat(artifact.path("project").has("id")).isFalse();
    assertThat(artifact.path("project").has("repository")).isFalse();
    assertThat(artifact.path("project").has("asset")).isFalse();
    assertThat(
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .has("tmTextUnitId"))
        .isFalse();
    assertThat(
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .path("values")
                .path("en")
                .asText())
        .isEqualTo("Hello");
    assertThat(
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .path("values")
                .path("fr-FR")
                .asText())
        .isEqualTo("Bonjour");
  }

  @Test
  public void publishProjectLogsBoundedOperationalMetadataOnly() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    CmsContentService.PublishSnapshotView view =
        publishProject(
            fixture.project.getId(),
            validatedPublishCommand(fixture, List.of("fr-FR")),
            "secret-publish-request-key");

    assertThat(outputCapture.toString())
        .contains(
            "Published CMS snapshot: projectKey=growth-email, snapshotVersion=1, localeTags=[en, fr-FR], artifactSha256="
                + view.artifactSha256())
        .contains("artifactBytes=" + view.artifactByteSize())
        .contains("publisher=admin")
        .doesNotContain("secret-publish-request-key")
        .doesNotContain(view.snapshotSignature())
        .doesNotContain(view.artifactSignature())
        .doesNotContain("test-content-cms-snapshot-signing-key-0001")
        .doesNotContain("\"source\":\"Hello\"")
        .doesNotContain("Bonjour");
  }

  @Test
  public void publishProjectRejectsSnapshotWatermarkAdvanceConflict() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    doReturn(0)
        .when(projectRepository)
        .advanceLastPublishedSnapshotVersion(fixture.project.getId(), 0, 1);

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(), validatedPublishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("publish snapshot history changed; retry publish");
    verify(snapshotRepository).save(any(CmsPublishSnapshot.class));
    verify(snapshotSealRepository).save(any(CmsPublishSnapshotSeal.class));
  }

  @Test
  public void getProjectCompletenessRejectsIncompleteSnapshotHistoryBeforeReturningPackage() {
    CmsFixture fixture = setupFixture();
    fixture.project.setLastPublishedSnapshotVersion(1);

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "CMS publish snapshot history is incomplete for content project: growth-email");

    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void getProjectCompletenessRejectsInvalidRecentSnapshotBeforeReturningPackage() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setArtifactSignature("bad");
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of(snapshot));

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish artifact signature is invalid: 99");

    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsInvalidRecentSnapshotBeforeCreatingSnapshot() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setArtifactSignature("bad");
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of(snapshot));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish artifact signature is invalid: 99");

    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
    verify(snapshotSealRepository, never()).save(any(CmsPublishSnapshotSeal.class));
  }

  @Test
  public void publishProjectReturnsStoredSnapshotForMatchingPublishRequestKey() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "growth-email", List.of("en", "fr-FR"));
    snapshot.setPublishRequestKey("publish-fr");
    snapshot.setPublishRequestLocaleTags("fr-FR");
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256(fixture));
    resignSnapshot(snapshot);
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(
            fixture.project.getId(), "publish-fr"))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.PublishSnapshotView view =
        publishProject(
            fixture.project.getId(), publishCommand(fixture, List.of("fr-FR")), "publish-fr");

    assertThat(view.snapshotVersion()).isEqualTo(snapshot.getSnapshotVersion());
    assertThat(view.localeTags()).containsExactly("en", "fr-FR");
    verify(snapshotRepository).findMaxSnapshotVersionByProjectId(fixture.project.getId());
    verify(snapshotRepository).countByProjectId(fixture.project.getId());
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
  }

  @Test
  public void
      publishProjectReturnsStoredSnapshotForMatchingPublishRequestKeyAfterProjectDisabled() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "growth-email", List.of("en", "fr-FR"));
    snapshot.setPublishRequestKey("publish-fr");
    snapshot.setPublishRequestLocaleTags("fr-FR");
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256(fixture));
    resignSnapshot(snapshot);
    fixture.project.setEnabled(false);
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(
            fixture.project.getId(), "publish-fr"))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.PublishSnapshotView view =
        publishProject(
            fixture.project.getId(), publishCommand(fixture, List.of("fr-FR")), "publish-fr");

    assertThat(view.snapshotVersion()).isEqualTo(snapshot.getSnapshotVersion());
    assertThat(view.localeTags()).containsExactly("en", "fr-FR");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
  }

  @Test
  public void publishProjectRejectsReusedPublishRequestKeyForAnotherLocaleScope() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "growth-email", List.of("en", "fr-FR"));
    snapshot.setPublishRequestKey("publish-fr");
    snapshot.setPublishRequestLocaleTags("fr-FR");
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256(fixture));
    resignSnapshot(snapshot);
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(
            fixture.project.getId(), "publish-fr"))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    publishCommand(fixture, List.of("de-DE")),
                    "publish-fr"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("already used for another locale scope");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectReturnsReusedDefaultPublishRequestKeyAfterLocaleConfigChanges() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "growth-email", List.of("en", "fr-FR"));
    snapshot.setPublishRequestKey("publish-default");
    snapshot.setPublishRequestLocaleTags("");
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256(fixture));
    resignSnapshot(snapshot);
    CmsContentService.PublishCommand publishCommand = publishCommand(fixture, List.of());
    clearInvocations(
        contentTypeRepository,
        fieldRepository,
        entryRepository,
        variantRepository,
        mappingRepository);
    fixture
        .project
        .getRepository()
        .getRepositoryLocales()
        .add(
            new RepositoryLocale(fixture.project.getRepository(), locale(3L, "de-DE"), true, null));
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(
            fixture.project.getId(), "publish-default"))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.PublishSnapshotView view =
        publishProject(fixture.project.getId(), publishCommand, "publish-default");

    assertThat(view.snapshotVersion()).isEqualTo(snapshot.getSnapshotVersion());
    assertThat(view.localeTags()).containsExactly("en", "fr-FR");
    verify(contentTypeRepository, never())
        .findByProjectIdOrderByNameAscIdAsc(fixture.project.getId());
    verify(fieldRepository, never())
        .findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
            List.of(fixture.contentType.getId()));
    verify(entryRepository, never()).findByProjectIdOrderByNameAscIdAsc(fixture.project.getId());
    verify(variantRepository, never())
        .findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(List.of(fixture.entry.getId()));
    verify(mappingRepository, never()).findMappingsByProjectId(fixture.project.getId());
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsReusedPublishRequestKeyForAnotherAuthoringRevision() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "growth-email", List.of("en", "fr-FR"));
    snapshot.setPublishRequestKey("publish-fr");
    snapshot.setPublishRequestLocaleTags("fr-FR");
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256(fixture));
    resignSnapshot(snapshot);
    fixture.entry.setEntityVersion(fixture.entry.getEntityVersion() + 1);
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(
            fixture.project.getId(), "publish-fr"))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    publishCommand(fixture, List.of("fr-FR")),
                    "publish-fr"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("already used for another authoring revision");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsReusedPublishRequestKeyForAnotherValidatedPackage() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "growth-email", List.of("en", "fr-FR"));
    snapshot.setPublishRequestKey("publish-fr");
    snapshot.setPublishRequestLocaleTags("fr-FR");
    snapshot.setPublishRequestAuthoringSha256(publishRequestAuthoringSha256(fixture));
    resignSnapshot(snapshot);
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(
            fixture.project.getId(), "publish-fr"))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), publishRequestAuthoringSha256(fixture), "c".repeat(64)),
                    "publish-fr"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("already used for another validated package");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsBlankPublishRequestKey() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(), publishCommand(fixture, List.of("fr-FR")), " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Publish request key is required");
    verify(projectRepository, never())
        .findByIdWithRepositoryAndAssetForUpdate(fixture.project.getId());
  }

  @Test
  public void publishProjectRejectsPublishRequestKeyThatWouldBeRewritten() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    publishCommand(fixture, List.of("fr-FR")),
                    "Publish Request"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publish request key must use lowercase letters, numbers, underscores, or hyphens");
    verify(projectRepository, never())
        .findByIdWithRepositoryAndAssetForUpdate(fixture.project.getId());
  }

  @Test
  public void publishProjectRejectsMissingExpectedAuthoringSha256() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), null, VALID_PACKAGE_SHA256),
                    "publish-fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected authoring SHA-256 is required");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsInvalidExpectedAuthoringSha256() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), "bad", VALID_PACKAGE_SHA256),
                    "publish-fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected authoring SHA-256 is invalid");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsMissingExpectedPackageSha256() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), publishRequestAuthoringSha256(fixture), null),
                    "publish-fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected package SHA-256 is required");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsInvalidExpectedPackageSha256() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), publishRequestAuthoringSha256(fixture), "bad"),
                    "publish-fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected package SHA-256 is invalid");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsStaleExpectedAuthoringSha256BeforePreflight() {
    CmsFixture fixture = setupFixture();
    String staleAuthoringSha256 = publishRequestAuthoringSha256(fixture);
    fixture.entry.setEntityVersion(fixture.entry.getEntityVersion() + 1);

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), staleAuthoringSha256, VALID_PACKAGE_SHA256),
                    "publish-fr"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("authoring revision changed");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsStaleExpectedAuthoringSha256AfterMappedSourceChanges() {
    CmsFixture fixture = setupFixture();
    String staleAuthoringSha256 = publishRequestAuthoringSha256(fixture);
    fixture.tmTextUnit.setContent("Updated hello");

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"), staleAuthoringSha256, VALID_PACKAGE_SHA256),
                    "publish-fr"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("authoring revision changed");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsStaleExpectedPackageSha256AfterApprovedTranslationChanges() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Salut",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    CmsContentService.ProjectCompletenessView validation =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR"));

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"),
                        validation.authoringSha256(),
                        validation.publishPackageSha256()),
                    "publish-fr"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("publish package changed");
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
  }

  @Test
  public void publishProjectUsesApprovedParentLocaleTranslationForInheritedLocale()
      throws Exception {
    CmsFixture fixture = setupFixture();
    Locale inheritedLocale = locale(3L, "fr-CA");
    RepositoryLocale frenchRepositoryLocale =
        fixture.project.getRepository().getRepositoryLocales().stream()
            .filter(repositoryLocale -> "fr-FR".equals(repositoryLocale.getLocale().getBcp47Tag()))
            .findFirst()
            .orElseThrow();
    fixture
        .project
        .getRepository()
        .getRepositoryLocales()
        .add(
            new RepositoryLocale(
                fixture.project.getRepository(), inheritedLocale, false, frenchRepositoryLocale));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(0);

    CmsContentService.PublishSnapshotView view =
        publishProject(fixture.project.getId(), validatedPublishCommand(fixture, List.of("fr-CA")));

    verify(snapshotRepository, times(2))
        .findCurrentVariantRows(
            any(), argThat(localeTags -> localeTags.containsAll(List.of("en", "fr-FR", "fr-CA"))));
    assertThat(view.localeTags()).containsExactly("en", "fr-CA");
    JsonNode artifact = objectMapper.readTree(fixture.savedSnapshot.getArtifactJson());
    assertThat(
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .path("values")
                .path("fr-CA")
                .asText())
        .isEqualTo("Bonjour");
  }

  @Test
  public void publishProjectDefaultsToConfiguredRepositoryLocales() throws Exception {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(0);

    CmsContentService.PublishSnapshotView view =
        publishProject(fixture.project.getId(), validatedPublishCommand(fixture, null));

    verify(snapshotRepository, times(2))
        .findCurrentVariantRows(
            any(), argThat(localeTags -> localeTags.containsAll(List.of("en", "fr-FR"))));
    assertThat(view.localeTags()).containsExactly("en", "fr-FR");
  }

  @Test
  public void publishProjectCanonicalizesExplicitLocaleSelectionOrder() throws Exception {
    CmsFixture fixture = setupFixture();
    Locale germanLocale = locale(3L, "de-DE");
    fixture
        .project
        .getRepository()
        .getRepositoryLocales()
        .add(new RepositoryLocale(fixture.project.getRepository(), germanLocale, true, null));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true),
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "de-DE",
                    "Hallo",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(0);

    CmsContentService.PublishSnapshotView view =
        publishProject(
            fixture.project.getId(), validatedPublishCommand(fixture, List.of("fr-FR", "de-DE")));

    assertThat(view.localeTags()).containsExactly("en", "de-DE", "fr-FR");
    JsonNode artifact = objectMapper.readTree(fixture.savedSnapshot.getArtifactJson());
    assertThat(artifact.path("locales"))
        .isEqualTo(objectMapper.readTree("[\"en\",\"de-DE\",\"fr-FR\"]"));
  }

  @Test
  public void getProjectCompletenessCanonicalizesExplicitLocaleSelectionOrderForPackageIdentity() {
    CmsFixture fixture = setupFixture();
    Locale germanLocale = locale(3L, "de-DE");
    fixture
        .project
        .getRepository()
        .getRepositoryLocales()
        .add(new RepositoryLocale(fixture.project.getRepository(), germanLocale, true, null));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true),
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "de-DE",
                    "Hallo",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    CmsContentService.ProjectCompletenessView first =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR", "de-DE"));
    CmsContentService.ProjectCompletenessView second =
        service.getProjectCompleteness(fixture.project.getId(), List.of("de-DE", "fr-FR"));

    assertThat(first.localeTags()).containsExactly("en", "de-DE", "fr-FR");
    assertThat(second.localeTags()).isEqualTo(first.localeTags());
    assertThat(second.authoringSha256()).isEqualTo(first.authoringSha256());
    assertThat(second.publishPackageSha256()).isEqualTo(first.publishPackageSha256());
    assertThat(second.publishPackageByteSize()).isEqualTo(first.publishPackageByteSize());
  }

  @Test
  public void publishProjectRejectsBlankExplicitLocaleSelection() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(), publishCommand(fixture, List.of("fr-FR", " "))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Locale tags must not contain blank values");
  }

  @Test
  public void publishProjectRejectsDuplicateExplicitLocaleSelection() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(), publishCommand(fixture, List.of("fr-FR", "fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Locale tags must not contain duplicate values: fr-FR");
  }

  @Test
  public void publishProjectRejectsParentFallbackForFullyTranslatedChildLocale() {
    CmsFixture fixture = setupFixture();
    Locale inheritedLocale = locale(3L, "fr-CA");
    RepositoryLocale frenchRepositoryLocale =
        fixture.project.getRepository().getRepositoryLocales().stream()
            .filter(repositoryLocale -> "fr-FR".equals(repositoryLocale.getLocale().getBcp47Tag()))
            .findFirst()
            .orElseThrow();
    fixture
        .project
        .getRepository()
        .getRepositoryLocales()
        .add(
            new RepositoryLocale(
                fixture.project.getRepository(), inheritedLocale, true, frenchRepositoryLocale));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-CA"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-CA");
  }

  @Test
  public void publishProjectRejectsRepositoryLocaleInheritanceCycle() {
    CmsFixture fixture = setupFixture();
    Locale inheritedLocale = locale(3L, "fr-CA");
    RepositoryLocale frenchRepositoryLocale =
        fixture.project.getRepository().getRepositoryLocales().stream()
            .filter(repositoryLocale -> "fr-FR".equals(repositoryLocale.getLocale().getBcp47Tag()))
            .findFirst()
            .orElseThrow();
    RepositoryLocale inheritedRepositoryLocale =
        new RepositoryLocale(
            fixture.project.getRepository(), inheritedLocale, false, frenchRepositoryLocale);
    fixture.project.getRepository().getRepositoryLocales().add(inheritedRepositoryLocale);
    frenchRepositoryLocale.setParentLocale(inheritedRepositoryLocale);

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-CA"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS repository locale inheritance cycle from: fr-CA");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsStoredEntryMetadataThatViolatesSchema() {
    CmsFixture fixture = setupFixture();
    fixture.contentType.setMetadataSchemaJson(
        "{\"type\":\"object\",\"required\":[\"surface\"],\"properties\":{\"surface\":{\"type\":\"string\"}}}");
    fixture.entry.setMetadataJson("{}");

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry metadata for welcome is missing required property: surface");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectRejectsStoredVariantMetadataWithDuplicateObjectKeys() {
    CmsFixture fixture = setupFixture();
    fixture.variant.setMetadataJson("{\"surface\":\"email\",\"surface\":\"banner\"}");

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Variant metadata for welcome.default must be valid JSON");
    verify(snapshotRepository, never()).findCurrentVariantRows(any(), any());
  }

  @Test
  public void publishProjectOrdersEntriesByStableKeyInsteadOfDisplayName() throws Exception {
    CmsFixture fixture = setupFixture();
    CmsContentEntry alphaEntry = new CmsContentEntry();
    alphaEntry.setId(61L);
    alphaEntry.setProject(fixture.project);
    alphaEntry.setContentType(fixture.contentType);
    alphaEntry.setEntryKey("alpha");
    alphaEntry.setName("Zulu");
    alphaEntry.setStatus(CmsContentEntry.Status.READY);

    CmsContentEntryVariant alphaVariant = new CmsContentEntryVariant();
    alphaVariant.setId(71L);
    alphaVariant.setEntry(alphaEntry);
    alphaVariant.setContentType(fixture.contentType);
    alphaVariant.setVariantKey("default");
    alphaVariant.setName("Default");
    alphaVariant.setStatus(CmsContentEntryVariant.Status.CONTROL);
    alphaVariant.setControlEntryId(alphaEntry.getId());
    alphaVariant.setSortOrder(0);

    TMTextUnit alphaTextUnit = new TMTextUnit();
    alphaTextUnit.setId(81L);
    alphaTextUnit.setAsset(fixture.project.getAsset());
    alphaTextUnit.setName("cms.growth-email.alpha.default.header");
    alphaTextUnit.setContent("Alpha");
    alphaTextUnit.setComment("Shown in alpha header");

    CmsContentFieldMapping alphaMapping = new CmsContentFieldMapping();
    alphaMapping.setId(91L);
    alphaMapping.setVariant(alphaVariant);
    alphaMapping.setField(fixture.field);
    alphaMapping.setContentType(fixture.contentType);
    alphaMapping.setTmTextUnit(alphaTextUnit);

    when(entryRepository.findByProjectIdOrderByNameAscIdAsc(fixture.project.getId()))
        .thenReturn(List.of(fixture.entry, alphaEntry));
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(any()))
        .thenReturn(List.of(alphaVariant, fixture.variant));
    when(mappingRepository.findMappingsByProjectId(fixture.project.getId()))
        .thenReturn(List.of(fixture.mapping, alphaMapping));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    alphaTextUnit.getId(),
                    "fr-FR",
                    "Alpha FR",
                    TMTextUnitVariant.Status.APPROVED,
                    true),
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(0);

    publishProject(fixture.project.getId(), validatedPublishCommand(fixture, List.of("fr-FR")));

    JsonNode entries =
        objectMapper.readTree(fixture.savedSnapshot.getArtifactJson()).path("entries");
    assertThat(List.of(entries.get(0).path("key").asText(), entries.get(1).path("key").asText()))
        .containsExactly("alpha", "welcome");
  }

  @Test
  public void publishProjectCanonicalizesStoredMetadataObjectOrderInArtifact() throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.contentType.setMetadataSchemaJson(
        "{\"type\":\"object\",\"required\":[\"zeta\",\"alpha\"],\"properties\":{\"zeta\":{\"type\":\"number\"},\"alpha\":{\"type\":\"number\"}}}");
    fixture.entry.setMetadataJson("{\"zeta\":2,\"alpha\":1}");
    fixture.variant.setMetadataJson("{\"zeta\":2,\"alpha\":1}");
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(0);

    publishProject(fixture.project.getId(), validatedPublishCommand(fixture, List.of("fr-FR")));

    assertThat(fixture.savedSnapshot.getArtifactJson())
        .contains(
            "\"metadataSchema\":{\"properties\":{\"alpha\":{\"type\":\"number\"},\"zeta\":{\"type\":\"number\"}},\"required\":[\"alpha\",\"zeta\"],\"type\":\"object\"}");
    assertThat(fixture.savedSnapshot.getArtifactJson())
        .contains("\"metadata\":{\"alpha\":1,\"zeta\":2}");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactDigestMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = publishSnapshot(fixture.project, "{\"project\":\"growth\"}");
    snapshot.setArtifactSha256("bad");
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact SHA-256 mismatch: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotInvalidDeliveryEnvelope() throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode) artifact.path("delivery")).put("runtimeDependency", "mojito");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid delivery: 99");
  }

  @Test
  public void getSnapshotArtifactLoadsVersionAddressableSnapshotByCanonicalProjectKey() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotArtifact artifact = service.getSnapshotArtifact("growth-email", 2);

    assertThat(artifact.filename()).isEqualTo("growth-email.v2.json");
    assertThat(artifact.snapshotSigningKeyId()).isEqualTo("test-v1");
    assertThat(artifact.snapshotSignature()).isEqualTo(snapshot.getSnapshotSignature());
    assertThat(artifact.artifactSignature()).isEqualTo(snapshot.getArtifactSignature());
    verify(projectRepository).findByProjectKeyWithRepositoryAndAssetForUpdate("growth-email");
    verify(snapshotRepository).findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2);
  }

  @Test
  public void getSnapshotArtifactAllowsDisabledProjectForRollback() {
    CmsFixture fixture = setupFixture();
    fixture.project.setEnabled(false);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotArtifact artifact = service.getSnapshotArtifact("growth-email", 2);

    assertThat(artifact.filename()).isEqualTo("growth-email.v2.json");
    verify(snapshotRepository).findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2);
  }

  @Test
  public void getSnapshotArtifactRejectsStableProjectKeyThatWouldBeRewritten() {
    setupFixture();

    assertThatThrownBy(() -> service.getSnapshotArtifact("Growth Email", 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Stable project key must use lowercase letters, numbers, underscores, or hyphens");
    verify(projectRepository, never()).findByProjectKeyWithRepositoryAndAssetForUpdate(any());
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorLoadsVerifiedSnapshotByCanonicalProjectKey() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotDeliveryDescriptor latest =
        service.getLatestPublishedSnapshotDescriptor("growth-email");

    assertThat(latest.formatVersion()).isEqualTo("mojito.microCms.snapshot-delivery-descriptor.v1");
    assertThat(latest.projectKey()).isEqualTo("growth-email");
    assertThat(latest.snapshotVersion()).isEqualTo(2);
    assertThat(latest.projectHint()).isEqualTo("BLOB_CDN");
    assertThat(latest.artifactSha256()).isEqualTo(snapshot.getArtifactSha256());
    assertThat(latest.artifactByteSize()).isEqualTo(snapshot.getArtifactByteSize());
    assertThat(latest.signatureAlgorithm())
        .isEqualTo(CmsSnapshotSigningService.SIGNATURE_ALGORITHM);
    assertThat(latest.snapshotSignatureVersion())
        .isEqualTo(CmsSnapshotSigningService.SNAPSHOT_SIGNATURE_VERSION);
    assertThat(latest.artifactSignatureVersion())
        .isEqualTo(CmsSnapshotSigningService.ARTIFACT_SIGNATURE_VERSION);
    assertThat(latest.snapshotSigningKeyId()).isEqualTo("test-v1");
    assertThat(latest.snapshotSignature()).isEqualTo(snapshot.getSnapshotSignature());
    assertThat(latest.artifactSignature()).isEqualTo(snapshot.getArtifactSignature());
    assertThat(latest.artifactFilename()).isEqualTo("growth-email.v2.json");
    assertThat(latest.artifactExportPath())
        .isEqualTo("/api/content-cms/projects/growth-email/publish-snapshots/2/artifact");
    assertThat(latest.publishedAt()).isEqualTo("2026-01-01T00:00:00Z");
    verify(projectRepository).findByProjectKeyWithRepositoryAndAssetForUpdate("growth-email");
    verify(snapshotRepository)
        .findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId());
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsStoredSnapshotInvalidDeliveryEnvelope()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode) artifact.path("delivery")).put("runtimeDependency", "mojito");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid delivery: 99");
  }

  @Test
  public void
      getLatestPublishedSnapshotDescriptorRejectsStoredSnapshotCompletenessMetadataMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setCompletenessJson(
        "[{\"localeTag\":\"en\",\"totalFields\":2,\"approvedFields\":2,\"missingFields\":0,\"reviewNeededFields\":0,\"translationNeededFields\":0,\"complete\":true}]");
    resignSnapshot(snapshot);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot completeness mismatch: 99");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsStableProjectKeyThatWouldBeRewritten() {
    setupFixture();

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("Growth Email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Stable project key must use lowercase letters, numbers, underscores, or hyphens");
    verify(projectRepository, never()).findByProjectKeyWithRepositoryAndAssetForUpdate(any());
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsMissingStoredSnapshotHistory() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(fixture.project.getId()))
        .thenReturn(1);
    when(snapshotRepository.countByProjectId(fixture.project.getId())).thenReturn(1L);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "CMS publish snapshot history is incomplete for content project: growth-email");
    verify(snapshotRepository, never())
        .findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId());
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsProjectWithoutSnapshots() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Published snapshot not found: growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsDisabledProject() {
    CmsFixture fixture = setupFixture();
    fixture.project.setEnabled(false);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project is disabled: growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsDeletedBackingRepository() {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setDeleted(true);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project repository is deleted: growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsHiddenBackingRepository() {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setHidden(true);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project repository is hidden: growth-email");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsDeletedVirtualAsset() {
    CmsFixture fixture = setupFixture();
    fixture.project.getAsset().setDeleted(true);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project virtual asset is deleted");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorRejectsAssetWithoutCmsManagedMarker() {
    CmsFixture fixture = setupFixture();
    fixture.project.getAsset().setCmsManaged(false);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    assertThatThrownBy(() -> service.getLatestPublishedSnapshotDescriptor("growth-email"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project asset must remain CMS-managed");
  }

  @Test
  public void getLatestPublishedSnapshotDescriptorUsesSignedSnapshotProjectHint() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    fixture.project.setDeliveryHint("STATSIG_DYNAMIC_CONFIG");
    when(snapshotRepository.findFirstByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId()))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotDeliveryDescriptor latest =
        service.getLatestPublishedSnapshotDescriptor("growth-email");

    assertThat(latest.projectHint()).isEqualTo("BLOB_CDN");
  }

  @Test
  public void getSnapshotArtifactStillExportsExactVersionForDisabledProject() {
    CmsFixture fixture = setupFixture();
    fixture.project.setEnabled(false);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotArtifact artifact = service.getSnapshotArtifact("growth-email", 2);

    assertThat(artifact.filename()).isEqualTo("growth-email.v2.json");
  }

  @Test
  public void getSnapshotArtifactStillExportsExactVersionForOrphanedProject() {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setDeleted(true);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotArtifact artifact = service.getSnapshotArtifact("growth-email", 2);

    assertThat(artifact.filename()).isEqualTo("growth-email.v2.json");
  }

  @Test
  public void getSnapshotArtifactStillExportsExactVersionForHiddenProject() {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setHidden(true);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(fixture.project.getId(), 2))
        .thenReturn(Optional.of(snapshot));

    CmsContentService.SnapshotArtifact artifact = service.getSnapshotArtifact("growth-email", 2);

    assertThat(artifact.filename()).isEqualTo("growth-email.v2.json");
  }

  @Test
  public void getSnapshotArtifactRejectsInvalidVersionAddressableSnapshotVersion() {
    setupFixture();

    assertThatThrownBy(() -> service.getSnapshotArtifact("growth-email", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Snapshot version must be at least 1");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutPublishedStatus() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setStatus(null);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot status is invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactByteSizeMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = publishSnapshot(fixture.project, "{\"project\":\"growth\"}");
    snapshot.setArtifactByteSize(snapshot.getArtifactByteSize() + 1);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact byte size mismatch: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactRecomputedDigestWithoutSignature()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode field =
        (ObjectNode)
            artifact.path("entries").get(0).path("variants").get(0).path("fields").path("header");
    field.put("source", "Tampered");
    ((ObjectNode) field.path("values")).put("en", "Tampered");
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    updateSnapshotArtifact(snapshot, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot signature mismatch: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactDuplicateJsonKeys() {
    CmsFixture fixture = setupFixture();
    String artifactJson =
        validArtifactJson(fixture.project.getProjectKey(), List.of("en"))
            .replace("\"snapshotVersion\":2", "\"snapshotVersion\":2,\"snapshotVersion\":2");
    CmsPublishSnapshot snapshot = publishSnapshot(fixture.project, artifactJson);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact JSON is invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactTrailingJsonDocument() {
    CmsFixture fixture = setupFixture();
    String artifactJson = validArtifactJson(fixture.project.getProjectKey(), List.of("en")) + " {}";
    CmsPublishSnapshot snapshot = publishSnapshot(fixture.project, artifactJson);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact JSON is invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactNonCanonicalGeneratedAt() throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    artifact.put("generatedAt", "2026-01-01T01:00:00+01:00");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid generatedAt: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactGeneratedAtDifferentFromPublishedAt()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    artifact.put("generatedAt", "2026-01-01T00:00:01Z");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid generatedAt: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactUnknownEnvelopeFields() throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    artifact.put("runtimeOverride", true);
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid envelope: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactAuthoringRoutingFields() throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode project = (ObjectNode) artifact.path("project");
    project.set("repository", objectMapper.createObjectNode().put("name", "Product copy"));
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid project: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotInvalidContentTypeSchema() throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode) artifact.path("contentTypes").get(0).path("metadataSchema"))
        .put("runtimeOverride", true);
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    updateSnapshotArtifact(snapshot, objectMapper.writeValueAsStringUnchecked(artifact));
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid content types: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotDuplicateRequiredMetadataSchemaProperty()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode metadataSchema =
        (ObjectNode) artifact.path("contentTypes").get(0).path("metadataSchema");
    metadataSchema.set("properties", objectMapper.readTree("{\"surface\":{\"type\":\"string\"}}"));
    metadataSchema.putArray("required").add("surface").add("surface");
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    updateSnapshotArtifact(snapshot, objectMapper.writeValueAsStringUnchecked(artifact));
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid content types: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotEntryMetadataSchemaMismatch()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode metadataSchema =
        (ObjectNode) artifact.path("contentTypes").get(0).path("metadataSchema");
    metadataSchema.set("properties", objectMapper.readTree("{\"surface\":{\"type\":\"string\"}}"));
    metadataSchema.putArray("required").add("surface");
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    updateSnapshotArtifact(snapshot, objectMapper.writeValueAsStringUnchecked(artifact));
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid entry metadata: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotLocaleMetadataMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setLocaleTags("en,fr-FR");
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot locale tags mismatch: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotLocaleMetadataWhitespaceDrift() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(
            fixture.project, fixture.project.getProjectKey(), List.of("en", "fr-FR"));
    snapshot.setLocaleTags("en, fr-FR");
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot locale tags are invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotInvalidLocaleTagDrift() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(
            fixture.project, fixture.project.getProjectKey(), List.of("en", "not_a_locale"));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot locale tags are invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotUnsortedLocaleTagDrift() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(
            fixture.project, fixture.project.getProjectKey(), List.of("en", "fr-FR", "de-DE"));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot locale tags are invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredArtifactUnsortedLocaleTagDrift() throws Exception {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(
            fixture.project, fixture.project.getProjectKey(), List.of("en", "de-DE", "fr-FR"));
    ObjectNode artifact = (ObjectNode) objectMapper.readTree(snapshot.getArtifactJson());
    artifact.putArray("locales").add("en").add("fr-FR").add("de-DE");
    updateSnapshotArtifact(snapshot, objectMapper.writeValueAsStringUnchecked(artifact));
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact locales are invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotArtifactProjectMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "other-project", List.of("en"));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact project mismatch: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotArtifactInvalidProjectKey() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot =
        validPublishSnapshot(fixture.project, "Growth Email", List.of("en"));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid key: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithOverlongRuntimeFieldKey()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    String overlongFieldKey = "a".repeat(CmsContentProject.KEY_MAX_LENGTH + 1);
    ((ObjectNode) artifact.path("contentTypes").get(0).path("fields").get(0))
        .put("key", overlongFieldKey);
    ObjectNode runtimeFields =
        (ObjectNode) artifact.path("entries").get(0).path("variants").get(0).path("fields");
    JsonNode runtimeField = runtimeFields.remove("header");
    runtimeFields.set(overlongFieldKey, runtimeField);
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid key: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutRuntimeEntries() throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    artifact.putArray("entries");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid entries: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithUndeclaredRuntimeField()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode runtimeFields =
        (ObjectNode) artifact.path("entries").get(0).path("variants").get(0).path("fields");
    runtimeFields.set("extra", runtimeFields.path("header"));
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid fields: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithUnpublishedRuntimeFieldLocaleValue()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode)
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .path("values"))
        .put("fr-FR", "Bonjour");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid field values: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutRuntimeFieldLocaleValue()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode)
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .path("values"))
        .remove("en");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid field values: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutRequiredRuntimeField()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJsonWithOptionalField(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode) artifact.path("entries").get(0).path("variants").get(0).path("fields"))
        .remove("header");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid required fields: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutSingleControlVariant()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode variant = (ObjectNode) artifact.path("entries").get(0).path("variants").get(0);
    variant.put("status", "CANDIDATE");
    variant.put("candidateGroupKey", "candidate-a");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid control variants: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithMultipleCandidateGroupKeys()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode controlVariant =
        (ObjectNode) artifact.path("entries").get(0).path("variants").get(0);
    ObjectNode candidateVariant = controlVariant.deepCopy();
    candidateVariant.put("key", "candidate-a");
    candidateVariant.put("name", "Candidate A");
    candidateVariant.put("status", "CANDIDATE");
    candidateVariant.put("candidateGroupKey", "candidate-a");
    ((ObjectNode) candidateVariant.path("fields").path("header"))
        .put(
            "stringId",
            "cms.%s.welcome.candidate-a.header".formatted(fixture.project.getProjectKey()));
    ObjectNode otherCandidate = candidateVariant.deepCopy();
    otherCandidate.put("key", "candidate-b");
    otherCandidate.put("name", "Candidate B");
    otherCandidate.put("candidateGroupKey", "candidate-b");
    ((ObjectNode) otherCandidate.path("fields").path("header"))
        .put(
            "stringId",
            "cms.%s.welcome.candidate-b.header".formatted(fixture.project.getProjectKey()));
    ((ArrayNode) artifact.path("entries").get(0).path("variants"))
        .add(candidateVariant)
        .add(otherCandidate);
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid candidate groups: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidRuntimeStringId()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode)
            artifact.path("entries").get(0).path("variants").get(0).path("fields").path("header"))
        .put("stringId", "x".repeat(4001));
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid fields: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithDuplicateRuntimeStringIds()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJsonWithOptionalField(fixture.project.getProjectKey(), List.of("en")));
    ObjectNode runtimeFields =
        (ObjectNode) artifact.path("entries").get(0).path("variants").get(0).path("fields");
    ((ObjectNode) runtimeFields.path("subheader"))
        .put("stringId", runtimeFields.path("header").path("stringId").asText());
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid string IDs: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidRuntimeIcuField()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    ((ObjectNode) artifact.path("contentTypes").get(0).path("fields").get(0))
        .put("type", "ICU_MESSAGE");
    ObjectNode field =
        (ObjectNode)
            artifact.path("entries").get(0).path("variants").get(0).path("fields").path("header");
    field.put("source", "Hello {");
    ((ObjectNode) field.path("values")).put("en", "Hello {");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid ICU fields: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutPublishedLocaleCompleteness()
      throws Exception {
    CmsFixture fixture = setupFixture();
    ObjectNode artifact =
        (ObjectNode)
            objectMapper.readTree(
                validArtifactJson(fixture.project.getProjectKey(), List.of("en")));
    artifact.putArray("completeness");
    CmsPublishSnapshot snapshot =
        publishSnapshot(fixture.project, objectMapper.writeValueAsStringUnchecked(artifact));
    snapshot.setCompletenessJson("[]");
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact has invalid completeness: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotCompletenessMetadataMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setCompletenessJson(
        "[{\"localeTag\":\"en\",\"totalFields\":2,\"approvedFields\":2,\"missingFields\":0,\"reviewNeededFields\":0,\"translationNeededFields\":0,\"complete\":true}]");
    resignSnapshot(snapshot);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot completeness mismatch: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithoutPublisher() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setCreatedByUser(null);
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot publisher is missing: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestKey() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setPublishRequestKey("bad.key");
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot request key is invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestLocaleTags() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setPublishRequestLocaleTags("fr-FR,en");
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot request locale tags are invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestAuthoringSha256() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setPublishRequestAuthoringSha256("bad");
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot request authoring SHA-256 is invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidRequestPackageSha256() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setPublishRequestPackageSha256("bad");
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot request package SHA-256 is invalid: 99");
  }

  @Test
  public void getSnapshotArtifactRejectsStoredSnapshotWithInvalidArtifactSignature() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setArtifactSignature("bad");
    stubSnapshotArtifactLookup(snapshot);

    assertThatThrownBy(() -> getSnapshotArtifact(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish artifact signature is invalid: 99");
  }

  @Test
  public void getProjectRejectsStoredSnapshotCompletenessMetadataMismatch() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot snapshot = validPublishSnapshot(fixture.project);
    snapshot.setCompletenessJson(
        "[{\"localeTag\":\"en\",\"totalFields\":2,\"approvedFields\":2,\"missingFields\":0,\"reviewNeededFields\":0,\"translationNeededFields\":0,\"complete\":true}]");
    resignSnapshot(snapshot);
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of(snapshot));

    assertThatThrownBy(() -> service.getProject(fixture.project.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot completeness mismatch: 99");
  }

  @Test
  public void publishProjectRejectsIncompleteRequestedLocale() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any())).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void publishProjectRejectsInvalidArtifactBeforeSavingSnapshot() {
    CmsFixture fixture = setupFixture();
    fixture.project.setName(" ");
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact name is missing: null");
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
    verify(snapshotSealRepository, never()).save(any(CmsPublishSnapshotSeal.class));
  }

  @Test
  public void getProjectCompletenessRejectsInvalidArtifactBeforeReturningPackage() {
    CmsFixture fixture = setupFixture();
    fixture.project.setName(" ");
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot artifact name is missing: null");
  }

  @Test
  public void publishProjectTreatsDeletedCurrentTranslationAsIncompleteRequestedLocale() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(fixture.tmTextUnit.getId(), "fr-FR", null, null, null)));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void publishProjectRejectsReviewNeededRequestedLocale() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.REVIEW_NEEDED,
                    true)));

    CmsContentService.ProjectCompletenessView completeness =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR"));

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.locales().get(1).reviewNeededFields()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void publishProjectRejectsApprovedButExcludedRequestedLocale() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    false)));

    CmsContentService.ProjectCompletenessView completeness =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR"));

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.locales().get(1).approvedFields()).isZero();
    assertThat(completeness.locales().get(1).missingFields()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void publishProjectRejectsReviewNeededOptionalMappedFieldRequestedLocale() {
    CmsFixture fixture = setupFixture();
    CmsContentTypeField subheaderField = new CmsContentTypeField();
    subheaderField.setId(51L);
    subheaderField.setContentType(fixture.contentType);
    subheaderField.setFieldKey("subheader");
    subheaderField.setName("Subheader");
    subheaderField.setFieldType(CmsContentTypeField.FieldType.TEXT);
    subheaderField.setLocalizable(true);
    subheaderField.setRequired(false);
    subheaderField.setSortOrder(1);
    auditCmsEntity(subheaderField);

    TMTextUnit subheaderTextUnit = new TMTextUnit();
    subheaderTextUnit.setId(81L);
    subheaderTextUnit.setAsset(fixture.project.getAsset());
    subheaderTextUnit.setName("cms.growth-email.welcome.default.subheader");
    subheaderTextUnit.setContent("Subheader");
    subheaderTextUnit.setComment("Shown below welcome email header");

    CmsContentFieldMapping subheaderMapping = new CmsContentFieldMapping();
    subheaderMapping.setId(91L);
    subheaderMapping.setVariant(fixture.variant);
    subheaderMapping.setField(subheaderField);
    subheaderMapping.setContentType(fixture.contentType);
    subheaderMapping.setTmTextUnit(subheaderTextUnit);
    auditCmsEntity(subheaderMapping);

    when(fieldRepository.findByContentTypeIdOrderBySortOrderAscFieldKeyAscIdAsc(
            fixture.contentType.getId()))
        .thenReturn(List.of(fixture.field, subheaderField));
    when(fieldRepository.findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
            List.of(fixture.contentType.getId())))
        .thenReturn(List.of(fixture.field, subheaderField));
    when(mappingRepository.findMappingsByProjectId(fixture.project.getId()))
        .thenReturn(List.of(fixture.mapping, subheaderMapping));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true),
                new CmsCurrentVariantRow(
                    subheaderTextUnit.getId(),
                    "fr-FR",
                    "Sous-titre",
                    TMTextUnitVariant.Status.REVIEW_NEEDED,
                    true)));

    CmsContentService.ProjectCompletenessView completeness =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR"));

    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.locales().get(1).totalFields()).isEqualTo(2);
    assertThat(completeness.locales().get(1).approvedFields()).isEqualTo(1);
    assertThat(completeness.locales().get(1).reviewNeededFields()).isEqualTo(1);
    assertThatThrownBy(
            () ->
                publishProject(
                    fixture.project.getId(),
                    new CmsContentService.PublishCommand(
                        List.of("fr-FR"),
                        completeness.authoringSha256(),
                        completeness.publishPackageSha256())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void publishProjectRejectsMissingAuthenticatedPublisher() {
    CmsFixture fixture = setupFixture();
    when(userService.getCurrentUser()).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
        .hasMessageContaining("Authenticated user required");
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
  }

  @Test
  public void publishProjectRejectsMissingRequiredFieldMapping() {
    CmsFixture fixture = setupFixture();
    CmsContentTypeField ctaField = new CmsContentTypeField();
    ctaField.setId(51L);
    ctaField.setContentType(fixture.contentType);
    ctaField.setFieldKey("cta");
    ctaField.setName("CTA");
    ctaField.setFieldType(CmsContentTypeField.FieldType.TEXT);
    ctaField.setLocalizable(true);
    ctaField.setRequired(true);
    ctaField.setSortOrder(1);
    when(fieldRepository.findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
            List.of(fixture.contentType.getId())))
        .thenReturn(List.of(fixture.field, ctaField));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish with missing required field mappings: welcome.default.cta");
  }

  @Test
  public void publishProjectRejectsReadyVariantWithoutMappedLocalizableFields() {
    CmsFixture fixture = setupFixture();
    fixture.field.setRequired(false);
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setCandidateGroupKey("welcome-header");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, candidateVariant));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish ready variants without mapped localizable fields: welcome.candidate");
  }

  @Test
  public void publishProjectRejectsInactivePublishableMappedTextUnit() {
    CmsFixture fixture = setupFixture();
    when(assetTextUnitToTMTextUnitRepository.findTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
            any(), any()))
        .thenReturn(Set.of());

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish with inactive mapped text units: welcome.default.header");
  }

  @Test
  public void publishProjectRejectsDoNotTranslatePublishableMappedTextUnit() {
    CmsFixture fixture = setupFixture();
    when(assetTextUnitToTMTextUnitRepository
            .findDoNotTranslateTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(Set.of(fixture.tmTextUnit.getId()));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish with do-not-translate mapped text units: welcome.default.header");
  }

  @Test
  public void publishProjectRejectsPublishableMappedTextUnitWithoutMojitoStringId() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setName(" \t ");

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have usable Mojito string IDs: welcome.default.header");
  }

  @Test
  public void publishProjectRejectsPublishableMappedTextUnitWithoutSourceContent() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setContent(" \t ");

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have source content: welcome.default.header");
  }

  @Test
  public void publishProjectRejectsPublishableMappedTextUnitWithoutTranslatorContext() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setComment(null);

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have translator context: welcome.default.header");
  }

  @Test
  public void publishProjectRejectsInvalidApprovedIcuTranslation() {
    CmsFixture fixture = setupFixture();
    fixture.field.setFieldType(CmsContentTypeField.FieldType.ICU_MESSAGE);
    fixture.tmTextUnit.setContent("{count, plural, one {# item} other {# items}}");
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "{count, plural, one {# article}",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ICU message integrity check failed for welcome.default.header fr-FR");
  }

  @Test
  public void publishProjectRejectsProjectWithoutReadyEntries() {
    CmsFixture fixture = setupFixture();
    fixture.entry.setStatus(CmsContentEntry.Status.DRAFT);

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project has no ready entries to publish");
  }

  @Test
  public void publishProjectRejectsDisabledProject() {
    CmsFixture fixture = setupFixture();
    fixture.project.setEnabled(false);

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project is disabled: growth-email");
    verify(snapshotRepository, never()).save(any(CmsPublishSnapshot.class));
  }

  @Test
  public void getProjectCompletenessRejectsDisabledProject() {
    CmsFixture fixture = setupFixture();
    fixture.project.setEnabled(false);

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project is disabled: growth-email");
  }

  @Test
  public void publishProjectRejectsDeletedBackingRepository() {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setDeleted(true);

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project repository is deleted: growth-email");
  }

  @Test
  public void getEntryCompletenessRejectsHiddenBackingRepository() {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setHidden(true);

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project repository is hidden: growth-email");
  }

  @Test
  public void getEntryCompletenessLocksProjectBeforeReadingPublishableState() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR"));

    InOrder inOrder = inOrder(projectRepository, entryRepository);
    inOrder
        .verify(projectRepository)
        .findByEntryIdWithRepositoryAndAssetForUpdate(fixture.entry.getId());
    inOrder.verify(entryRepository).findByIdWithProjectAndType(fixture.entry.getId());
  }

  @Test
  public void upsertFieldMappingRejectsDeletedBackingRepository() throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.project.getRepository().setDeleted(true);

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), null, "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project repository is deleted: growth-email");
    verify(virtualTextUnitBatchUpdaterService, never()).updateCmsTextUnits(any(), any(), eq(false));
  }

  @Test
  public void upsertFieldMappingRejectsDeletedVirtualAssetBeforeGeneratingTextUnit()
      throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.project.getAsset().setDeleted(true);

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), null, "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project virtual asset is deleted");
    verify(virtualTextUnitBatchUpdaterService, never()).updateCmsTextUnits(any(), any(), eq(false));
  }

  @Test
  public void upsertFieldMappingRejectsAssetWithoutCmsManagedMarkerBeforeGeneratingTextUnit()
      throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.project.getAsset().setCmsManaged(false);

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), null, "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project asset must remain CMS-managed");
    verify(virtualTextUnitBatchUpdaterService, never()).updateCmsTextUnits(any(), any(), eq(false));
  }

  @Test
  public void publishProjectRejectsReadyEntryWithoutActiveControlVariant() {
    CmsFixture fixture = setupFixture();
    fixture.variant.setStatus(CmsContentEntryVariant.Status.ARCHIVED);

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish ready entries without exactly one active control variant: welcome");
  }

  @Test
  public void publishProjectRejectsReadyEntryWithMultipleActiveControlVariants() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant duplicateControl = new CmsContentEntryVariant();
    duplicateControl.setId(71L);
    duplicateControl.setEntry(fixture.entry);
    duplicateControl.setContentType(fixture.contentType);
    duplicateControl.setVariantKey("duplicate-control");
    duplicateControl.setName("Duplicate control");
    duplicateControl.setStatus(CmsContentEntryVariant.Status.CONTROL);
    duplicateControl.setControlEntryId(fixture.entry.getId());
    duplicateControl.setSortOrder(1);
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, duplicateControl));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish ready entries without exactly one active control variant: welcome");
  }

  @Test
  public void publishProjectRejectsCandidateVariantWithoutCandidateGroupKey() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, candidateVariant));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish candidate variants without candidate group keys: welcome.candidate");
  }

  @Test
  public void publishProjectRejectsReadyEntryWithMultipleActiveCandidateGroups() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setCandidateGroupKey("welcome-header");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    CmsContentEntryVariant otherCandidate = new CmsContentEntryVariant();
    otherCandidate.setId(72L);
    otherCandidate.setEntry(fixture.entry);
    otherCandidate.setContentType(fixture.contentType);
    otherCandidate.setVariantKey("candidate-b");
    otherCandidate.setName("Candidate B");
    otherCandidate.setCandidateGroupKey("welcome-subheader");
    otherCandidate.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    otherCandidate.setSortOrder(2);
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, candidateVariant, otherCandidate));

    assertThatThrownBy(
            () ->
                publishProject(fixture.project.getId(), publishCommand(fixture, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish ready candidate variants with multiple candidate group keys: welcome");
  }

  @Test
  public void getEntryCompletenessRejectsMultipleActiveCandidateGroups() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setCandidateGroupKey("welcome-header");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    CmsContentEntryVariant otherCandidate = new CmsContentEntryVariant();
    otherCandidate.setId(72L);
    otherCandidate.setEntry(fixture.entry);
    otherCandidate.setContentType(fixture.contentType);
    otherCandidate.setVariantKey("candidate-b");
    otherCandidate.setName("Candidate B");
    otherCandidate.setCandidateGroupKey("welcome-subheader");
    otherCandidate.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    otherCandidate.setSortOrder(2);
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, candidateVariant, otherCandidate));

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Entry has candidate variants with multiple candidate group keys: welcome");
  }

  @Test
  public void getEntryCompletenessIgnoresArchivedVariants() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant archivedVariant = new CmsContentEntryVariant();
    archivedVariant.setId(71L);
    archivedVariant.setEntry(fixture.entry);
    archivedVariant.setContentType(fixture.contentType);
    archivedVariant.setVariantKey("archived");
    archivedVariant.setName("Archived");
    archivedVariant.setStatus(CmsContentEntryVariant.Status.ARCHIVED);
    archivedVariant.setSortOrder(1);
    TMTextUnit archivedTextUnit = new TMTextUnit();
    archivedTextUnit.setId(81L);
    archivedTextUnit.setAsset(fixture.project.getAsset());
    archivedTextUnit.setName("cms.growth-email.welcome.archived.header");
    archivedTextUnit.setContent("Archived hello");
    CmsContentFieldMapping archivedMapping = new CmsContentFieldMapping();
    archivedMapping.setId(91L);
    archivedMapping.setVariant(archivedVariant);
    archivedMapping.setField(fixture.field);
    archivedMapping.setContentType(fixture.contentType);
    archivedMapping.setTmTextUnit(archivedTextUnit);
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId()))
        .thenReturn(List.of(fixture.mapping, archivedMapping));
    when(variantRepository.findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(
            fixture.entry.getId()))
        .thenReturn(List.of(fixture.variant, archivedVariant));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    CmsContentService.EntryCompletenessView completeness =
        service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR"));

    assertThat(completeness.locales())
        .extracting(CmsContentService.LocaleCompleteness::totalFields)
        .containsOnly(1);
    assertThat(completeness.locales()).allMatch(CmsContentService.LocaleCompleteness::complete);
  }

  @Test
  public void getProjectCompletenessMatchesPublishPackageBoundary() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    CmsContentService.ProjectCompletenessView completeness =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR"));

    assertThat(completeness.projectId()).isEqualTo(fixture.project.getId());
    assertThat(completeness.projectKey()).isEqualTo("growth-email");
    assertThat(completeness.publishPackageByteSize()).isPositive();
    assertThat(completeness.localeTags()).containsExactly("en", "fr-FR");
    assertThat(completeness.complete()).isTrue();
    assertThat(completeness.locales()).allMatch(CmsContentService.LocaleCompleteness::complete);
    assertThat(completeness.entries())
        .extracting(CmsContentService.EntryCompletenessView::entryKey)
        .containsExactly("welcome");
    assertThat(completeness.entries().getFirst().locales())
        .allMatch(CmsContentService.LocaleCompleteness::complete);
    verify(mappingRepository, times(2)).findMappingsByProjectId(fixture.project.getId());
    verify(mappingRepository, never()).findMappingsByEntryId(fixture.entry.getId());
    verify(projectRepository).findByIdWithRepositoryAndAssetForUpdate(fixture.project.getId());
    verify(projectRepository, never()).findByIdWithRepositoryAndAsset(fixture.project.getId());
    assertThat(completeness.authoringSha256()).isEqualTo(publishRequestAuthoringSha256(fixture));
    assertThat(completeness.publishPackageSha256()).hasSize(64);
  }

  @Test
  public void getProjectCompletenessRejectsDuplicatePublishableStringIds() {
    CmsFixture fixture = setupFixture();
    CmsContentTypeField subheaderField = new CmsContentTypeField();
    subheaderField.setId(51L);
    subheaderField.setContentType(fixture.contentType);
    subheaderField.setFieldKey("subheader");
    subheaderField.setName("Subheader");
    subheaderField.setFieldType(CmsContentTypeField.FieldType.TEXT);
    subheaderField.setLocalizable(true);
    subheaderField.setRequired(false);
    subheaderField.setSortOrder(1);
    auditCmsEntity(subheaderField);

    TMTextUnit duplicateStringIdTextUnit = new TMTextUnit();
    duplicateStringIdTextUnit.setId(81L);
    duplicateStringIdTextUnit.setAsset(fixture.project.getAsset());
    duplicateStringIdTextUnit.setName(fixture.tmTextUnit.getName());
    duplicateStringIdTextUnit.setContent("Subheader");
    duplicateStringIdTextUnit.setComment("Shown below welcome email header");

    CmsContentFieldMapping subheaderMapping = new CmsContentFieldMapping();
    subheaderMapping.setId(91L);
    subheaderMapping.setVariant(fixture.variant);
    subheaderMapping.setField(subheaderField);
    subheaderMapping.setContentType(fixture.contentType);
    subheaderMapping.setTmTextUnit(duplicateStringIdTextUnit);
    auditCmsEntity(subheaderMapping);

    when(fieldRepository.findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
            List.of(fixture.contentType.getId())))
        .thenReturn(List.of(fixture.field, subheaderField));
    when(mappingRepository.findMappingsByProjectId(fixture.project.getId()))
        .thenReturn(List.of(fixture.mapping, subheaderMapping));

    assertThatThrownBy(() -> service.getProjectCompleteness(fixture.project.getId(), List.of("en")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Publishable mapped text units must have unique Mojito string IDs")
        .hasMessageContaining(
            "cms.growth-email.welcome.default.header"
                + " (welcome.default.header, welcome.default.subheader)");
  }

  @Test
  public void getEntryCompletenessRejectsCandidateVariantWithoutCandidateGroupKey() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, candidateVariant));

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Entry has candidate variants without candidate group keys: welcome.candidate");
  }

  @Test
  public void getEntryCompletenessRejectsEntryWithoutActiveControlVariant() {
    CmsFixture fixture = setupFixture();
    fixture.variant.setStatus(CmsContentEntryVariant.Status.ARCHIVED);

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry does not have exactly one active control variant: welcome");
  }

  @Test
  public void getEntryCompletenessRejectsPublishableVariantWithoutMappedLocalizableFields() {
    CmsFixture fixture = setupFixture();
    fixture.field.setRequired(false);
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId())).thenReturn(List.of());

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Entry has publishable variants without mapped localizable fields: welcome.default");
  }

  @Test
  public void getEntryCompletenessRejectsInactivePublishableMappedTextUnit() {
    CmsFixture fixture = setupFixture();
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId()))
        .thenReturn(List.of(fixture.mapping));
    when(assetTextUnitToTMTextUnitRepository.findTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
            any(), any()))
        .thenReturn(Set.of());

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot publish with inactive mapped text units: welcome.default.header");
  }

  @Test
  public void getEntryCompletenessRejectsPublishableMappedTextUnitWithoutMojitoStringId() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setName(" \t ");
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId()))
        .thenReturn(List.of(fixture.mapping));

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have usable Mojito string IDs: welcome.default.header");
  }

  @Test
  public void getEntryCompletenessRejectsStoredVariantMetadataThatIsNotAnObject() {
    CmsFixture fixture = setupFixture();
    fixture.variant.setMetadataJson("\"not-an-object\"");

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Variant metadata for welcome.default must be a JSON object");
    verify(mappingRepository, never()).findMappingsByEntryId(fixture.entry.getId());
  }

  @Test
  public void getEntryCompletenessRejectsPublishableMappedTextUnitWithoutSourceContent() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setContent(" \t ");
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId()))
        .thenReturn(List.of(fixture.mapping));

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have source content: welcome.default.header");
  }

  @Test
  public void getEntryCompletenessRejectsPublishableMappedTextUnitWithoutTranslatorContext() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setComment(null);
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId()))
        .thenReturn(List.of(fixture.mapping));

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have translator context: welcome.default.header");
  }

  @Test
  public void getEntryCompletenessRejectsInvalidApprovedIcuTranslation() {
    CmsFixture fixture = setupFixture();
    fixture.field.setFieldType(CmsContentTypeField.FieldType.ICU_MESSAGE);
    fixture.tmTextUnit.setContent("{count, plural, one {# item} other {# items}}");
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId()))
        .thenReturn(List.of(fixture.mapping));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "{count, plural, one {# article}",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(() -> service.getEntryCompleteness(fixture.entry.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ICU message integrity check failed for welcome.default.header fr-FR");
  }

  @Test
  public void updateProjectRejectsUnsupportedDeliveryHint() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateProject(
                    fixture.project.getId(),
                    new CmsContentService.ProjectUpdateCommand(
                        "Growth email", null, true, "unknown", fixture.project.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported delivery hint: UNKNOWN");
  }

  @Test
  public void updateProjectPreservesDeliveryHintWhenPatchOmitsIt() {
    CmsFixture fixture = setupFixture();

    CmsContentService.ProjectDetail project =
        service.updateProject(
            fixture.project.getId(),
            new CmsContentService.ProjectUpdateCommand(
                "Growth email", null, true, null, fixture.project.getEntityVersion()));

    assertThat(project.project().deliveryHint()).isEqualTo("BLOB_CDN");
  }

  @Test
  public void patchUpdateCommandsTrackOmittedAndExplicitNullFieldsFromJson() throws Exception {
    CmsContentService.ProjectUpdateCommand omittedProjectDescription =
        objectMapper.readValue(
            "{\"expectedVersion\":0}", CmsContentService.ProjectUpdateCommand.class);
    CmsContentService.ProjectUpdateCommand clearedProjectDescription =
        objectMapper.readValue(
            "{\"description\":null,\"expectedVersion\":0}",
            CmsContentService.ProjectUpdateCommand.class);
    CmsContentService.ContentTypeUpdateCommand omittedMetadataSchema =
        objectMapper.readValue(
            "{\"expectedVersion\":0}", CmsContentService.ContentTypeUpdateCommand.class);
    CmsContentService.ContentTypeUpdateCommand clearedMetadataSchema =
        objectMapper.readValue(
            "{\"metadataSchemaJson\":null,\"expectedVersion\":0}",
            CmsContentService.ContentTypeUpdateCommand.class);
    CmsContentService.ContentTypeFieldUpdateCommand omittedFieldDescription =
        objectMapper.readValue(
            "{\"expectedVersion\":0}", CmsContentService.ContentTypeFieldUpdateCommand.class);
    CmsContentService.ContentTypeFieldUpdateCommand clearedFieldDescription =
        objectMapper.readValue(
            "{\"description\":null,\"expectedVersion\":0}",
            CmsContentService.ContentTypeFieldUpdateCommand.class);
    CmsContentService.EntryUpdateCommand omittedEntryMetadata =
        objectMapper.readValue(
            "{\"expectedVersion\":0}", CmsContentService.EntryUpdateCommand.class);
    CmsContentService.EntryUpdateCommand clearedEntryMetadata =
        objectMapper.readValue(
            "{\"metadataJson\":null,\"expectedVersion\":0}",
            CmsContentService.EntryUpdateCommand.class);
    CmsContentService.VariantUpdateCommand omittedCandidateGroup =
        objectMapper.readValue(
            "{\"expectedVersion\":0}", CmsContentService.VariantUpdateCommand.class);
    CmsContentService.VariantUpdateCommand clearedCandidateGroup =
        objectMapper.readValue(
            "{\"candidateGroupKey\":null,\"expectedVersion\":0}",
            CmsContentService.VariantUpdateCommand.class);

    assertThat(omittedProjectDescription.hasDescription()).isFalse();
    assertThat(clearedProjectDescription.hasDescription()).isTrue();
    assertThat(clearedProjectDescription.description()).isNull();
    assertThat(omittedMetadataSchema.hasMetadataSchemaJson()).isFalse();
    assertThat(clearedMetadataSchema.hasMetadataSchemaJson()).isTrue();
    assertThat(clearedMetadataSchema.metadataSchemaJson()).isNull();
    assertThat(omittedFieldDescription.hasDescription()).isFalse();
    assertThat(clearedFieldDescription.hasDescription()).isTrue();
    assertThat(clearedFieldDescription.description()).isNull();
    assertThat(omittedEntryMetadata.hasMetadataJson()).isFalse();
    assertThat(clearedEntryMetadata.hasMetadataJson()).isTrue();
    assertThat(clearedEntryMetadata.metadataJson()).isNull();
    assertThat(omittedCandidateGroup.hasCandidateGroupKey()).isFalse();
    assertThat(clearedCandidateGroup.hasCandidateGroupKey()).isTrue();
    assertThat(clearedCandidateGroup.candidateGroupKey()).isNull();
  }

  @Test
  public void updateEntryPatchOmittedNullableFieldsPreservesCurrentValues() throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.entry.setDescription("Welcome description");
    fixture.entry.setMetadataJson("{\"owner\":\"growth\"}");
    CmsContentService.EntryUpdateCommand command =
        objectMapper.readValue(
            "{\"status\":\"DRAFT\",\"expectedVersion\":0}",
            CmsContentService.EntryUpdateCommand.class);

    service.updateEntry(fixture.entry.getId(), command);

    assertThat(fixture.entry.getDescription()).isEqualTo("Welcome description");
    assertThat(fixture.entry.getMetadataJson()).isEqualTo("{\"owner\":\"growth\"}");
    assertThat(fixture.entry.getStatus()).isEqualTo(CmsContentEntry.Status.DRAFT);
  }

  @Test
  public void updateEntryPatchExplicitNullClearsNullableFields() throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.entry.setDescription("Welcome description");
    fixture.entry.setMetadataJson("{\"owner\":\"growth\"}");
    CmsContentService.EntryUpdateCommand command =
        objectMapper.readValue(
            "{\"description\":null,\"metadataJson\":null,\"expectedVersion\":0}",
            CmsContentService.EntryUpdateCommand.class);

    service.updateEntry(fixture.entry.getId(), command);

    assertThat(fixture.entry.getDescription()).isNull();
    assertThat(fixture.entry.getMetadataJson()).isNull();
  }

  @Test
  public void createProjectRejectsUnavailableRepositoryBeforeCreatingAsset() throws Exception {
    CmsFixture fixture = setupFixture();
    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(
            List.of(fixture.project.getRepository().getId())))
        .thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.createProject(
                    new CmsContentService.ProjectCommand(
                        "new-project",
                        "New project",
                        null,
                        true,
                        fixture.project.getRepository().getId(),
                        null,
                        "BLOB_CDN")))
        .isInstanceOf(CmsContentNotFoundException.class)
        .hasMessageContaining("Repository not found: " + fixture.project.getRepository().getId());
    verify(virtualAssetService, never()).createOrUpdateVirtualAsset(any());
    verify(projectRepository, never()).saveAndFlush(any(CmsContentProject.class));
  }

  @Test
  public void createProjectRejectsVirtualAssetAlreadyAssignedToAnotherProject() {
    CmsFixture fixture = setupFixture();
    CmsContentProject existingProject = new CmsContentProject();
    existingProject.setId(31L);
    existingProject.setProjectKey("existing-project");
    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(
            List.of(fixture.project.getRepository().getId())))
        .thenReturn(List.of(fixture.project.getRepository()));
    when(assetRepository.findByPathAndRepositoryId(
            fixture.project.getAsset().getPath(), fixture.project.getRepository().getId()))
        .thenReturn(fixture.project.getAsset());
    when(projectRepository.findByAssetId(fixture.project.getAsset().getId()))
        .thenReturn(Optional.of(existingProject));

    assertThatThrownBy(
            () ->
                service.createProject(
                    new CmsContentService.ProjectCommand(
                        "new-project",
                        "New project",
                        null,
                        true,
                        fixture.project.getRepository().getId(),
                        fixture.project.getAsset().getPath(),
                        "BLOB_CDN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CMS asset is already assigned to content project: existing-project");
    verify(projectRepository, never()).saveAndFlush(any(CmsContentProject.class));
  }

  @Test
  public void createProjectRejectsAdoptingUnassignedVirtualAsset() throws Exception {
    CmsFixture fixture = setupFixture();
    when(repositoryRepository.findByIdInAndDeletedFalseAndHiddenFalseOrderByNameAsc(
            List.of(fixture.project.getRepository().getId())))
        .thenReturn(List.of(fixture.project.getRepository()));
    when(assetRepository.findByPathAndRepositoryId(
            fixture.project.getAsset().getPath(), fixture.project.getRepository().getId()))
        .thenReturn(fixture.project.getAsset());
    when(projectRepository.findByAssetId(fixture.project.getAsset().getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.createProject(
                    new CmsContentService.ProjectCommand(
                        "new-project",
                        "New project",
                        null,
                        true,
                        fixture.project.getRepository().getId(),
                        fixture.project.getAsset().getPath(),
                        "BLOB_CDN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "CMS asset path already exists; choose an unused path for a dedicated CMS virtual asset");
    verify(projectRepository, never()).saveAndFlush(any(CmsContentProject.class));
    verify(virtualAssetService, never()).createOrUpdateVirtualAsset(any());
  }

  @Test
  public void updateEntryLocksProjectBeforeWritingPublishableState() {
    CmsFixture fixture = setupFixture();
    fixture.entry.setStatus(CmsContentEntry.Status.DRAFT);

    service.updateEntry(
        fixture.entry.getId(),
        new CmsContentService.EntryUpdateCommand(
            "Welcome", null, CmsContentEntry.Status.READY, null, fixture.entry.getEntityVersion()));

    InOrder inOrder = inOrder(projectRepository, entryRepository);
    inOrder
        .verify(projectRepository)
        .findByEntryIdWithRepositoryAndAssetForUpdate(fixture.entry.getId());
    inOrder.verify(entryRepository).findByIdWithProjectAndType(fixture.entry.getId());
    inOrder.verify(entryRepository).saveAndFlush(fixture.entry);
  }

  @Test
  public void updateEntryRejectsStaleExpectedVersion() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateEntry(
                    fixture.entry.getId(),
                    new CmsContentService.EntryUpdateCommand(
                        "Welcome", null, CmsContentEntry.Status.READY, null, 99L)))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("Content entry changed since it was loaded");
    verify(entryRepository, never()).saveAndFlush(fixture.entry);
  }

  @Test
  public void updateEntryRejectsReadyWithoutMappedFields() {
    CmsFixture fixture = setupFixture();
    fixture.entry.setStatus(CmsContentEntry.Status.DRAFT);
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId())).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.updateEntry(
                    fixture.entry.getId(),
                    new CmsContentService.EntryUpdateCommand(
                        "Welcome",
                        null,
                        CmsContentEntry.Status.READY,
                        null,
                        fixture.entry.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Ready entry has publishable variants without mapped localizable fields: welcome.default");
    verify(entryRepository, never()).saveAndFlush(fixture.entry);
  }

  @Test
  public void createEntryRejectsMetadataMissingRequiredSchemaField() {
    CmsFixture fixture = setupFixture();
    fixture.contentType.setMetadataSchemaJson(
        "{\"type\":\"object\",\"required\":[\"surface\"],\"properties\":{\"surface\":{\"type\":\"string\"}}}");

    assertThatThrownBy(
            () ->
                service.createEntry(
                    fixture.project.getId(),
                    new CmsContentService.EntryCommand(
                        fixture.contentType.getId(),
                        "schema-miss",
                        "Schema miss",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        "{\"owner\":\"growth\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry metadata is missing required property: surface");
    verify(entryRepository, never()).saveAndFlush(any(CmsContentEntry.class));
  }

  @Test
  public void createEntryRejectsMissingContentTypeSelectorBeforeResourceLookup() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createEntry(
                    fixture.project.getId(),
                    new CmsContentService.EntryCommand(
                        null,
                        "missing-type",
                        "Missing type",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content type id is required");
    verify(contentTypeRepository, never()).findByIdWithProject(any());
  }

  @Test
  public void createEntryRejectsContentTypeFromAnotherProjectBeforeSaving() {
    CmsFixture fixture = setupFixture();
    CmsContentProject otherProject = new CmsContentProject();
    otherProject.setId(99L);
    otherProject.setProjectKey("other-project");
    when(projectRepository.findByIdWithRepositoryAndAssetForUpdate(otherProject.getId()))
        .thenReturn(Optional.of(otherProject));

    assertThatThrownBy(
            () ->
                service.createEntry(
                    otherProject.getId(),
                    new CmsContentService.EntryCommand(
                        fixture.contentType.getId(),
                        "cross-project",
                        "Cross project",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Content type does not belong to project: " + fixture.contentType.getId());
    verify(entryRepository, never()).saveAndFlush(any(CmsContentEntry.class));
  }

  @Test
  public void createEntryRejectsDuplicateMetadataObjectKeys() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createEntry(
                    fixture.project.getId(),
                    new CmsContentService.EntryCommand(
                        fixture.contentType.getId(),
                        "duplicate-metadata",
                        "Duplicate metadata",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        "{\"owner\":\"growth\",\"owner\":\"lifecycle\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry metadata must be valid JSON");
    verify(entryRepository, never()).saveAndFlush(any(CmsContentEntry.class));
  }

  @Test
  public void createEntryRejectsTrailingMetadataJsonDocument() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createEntry(
                    fixture.project.getId(),
                    new CmsContentService.EntryCommand(
                        fixture.contentType.getId(),
                        "trailing-metadata",
                        "Trailing metadata",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        "{\"owner\":\"growth\"} {}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry metadata must be valid JSON");
    verify(entryRepository, never()).saveAndFlush(any(CmsContentEntry.class));
  }

  @Test
  public void createEntryRejectsReadyBeforeFieldMappingsExist() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createEntry(
                    fixture.project.getId(),
                    new CmsContentService.EntryCommand(
                        fixture.contentType.getId(),
                        "ready-entry",
                        "Ready entry",
                        null,
                        CmsContentEntry.Status.READY,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "New content entries must start as DRAFT or ARCHIVED; map fields before marking READY");
    verify(entryRepository, never()).saveAndFlush(any(CmsContentEntry.class));
  }

  @Test
  public void createEntryRejectsDotInGeneratedStringIdSegmentKey() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createEntry(
                    fixture.project.getId(),
                    new CmsContentService.EntryCommand(
                        fixture.contentType.getId(),
                        "welcome.hero",
                        "Welcome hero",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Entry key must use lowercase letters, numbers, underscores, or hyphens");
    verify(entryRepository, never()).saveAndFlush(any(CmsContentEntry.class));
  }

  @Test
  public void createContentTypeRejectsUnsupportedMetadataSchemaKeyword() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createContentType(
                    fixture.project.getId(),
                    new CmsContentService.ContentTypeCommand(
                        "schema-type",
                        "Schema type",
                        null,
                        1,
                        "{\"type\":\"object\",\"patternProperties\":{}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Metadata schema has unsupported keyword: patternProperties");
    verify(contentTypeRepository, never()).saveAndFlush(any(CmsContentType.class));
  }

  @Test
  public void createContentTypeRejectsDuplicateMetadataSchemaObjectKeys() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createContentType(
                    fixture.project.getId(),
                    new CmsContentService.ContentTypeCommand(
                        "duplicate-schema",
                        "Duplicate schema",
                        null,
                        1,
                        "{\"type\":\"object\",\"properties\":{\"surface\":{\"type\":\"string\"},\"surface\":{\"type\":\"boolean\"}}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Metadata schema must be valid JSON");
    verify(contentTypeRepository, never()).saveAndFlush(any(CmsContentType.class));
  }

  @Test
  public void createContentTypeRejectsDuplicateMetadataSchemaRequiredProperties() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createContentType(
                    fixture.project.getId(),
                    new CmsContentService.ContentTypeCommand(
                        "duplicate-required",
                        "Duplicate required",
                        null,
                        1,
                        "{\"type\":\"object\",\"required\":[\"surface\",\"surface\"],\"properties\":{\"surface\":{\"type\":\"string\"}}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Metadata schema required property is duplicated: surface");
    verify(contentTypeRepository, never()).saveAndFlush(any(CmsContentType.class));
  }

  @Test
  public void createContentTypeRejectsManualSchemaVersion() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createContentType(
                    fixture.project.getId(),
                    new CmsContentService.ContentTypeCommand(
                        "schema-type", "Schema type", null, 2, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("New content type schema version must start at 1");
    verify(contentTypeRepository, never()).saveAndFlush(any(CmsContentType.class));
  }

  @Test
  public void updateContentTypeRejectsManualSchemaVersionChange() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateContentType(
                    fixture.contentType.getId(),
                    new CmsContentService.ContentTypeUpdateCommand(
                        "Email", null, 2, null, fixture.contentType.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content type schema version is managed automatically");
    verify(contentTypeRepository, never()).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeBumpsSchemaVersionWhenMetadataSchemaChanges() {
    CmsFixture fixture = setupFixture();

    service.updateContentType(
        fixture.contentType.getId(),
        new CmsContentService.ContentTypeUpdateCommand(
            "Email",
            null,
            fixture.contentType.getSchemaVersion(),
            "{\"type\":\"object\",\"properties\":{\"surface\":{\"type\":\"string\"}}}",
            fixture.contentType.getEntityVersion()));

    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(2);
    verify(contentTypeRepository).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeBumpsSchemaVersionWhenArtifactNameChanges() {
    CmsFixture fixture = setupFixture();

    service.updateContentType(
        fixture.contentType.getId(),
        new CmsContentService.ContentTypeUpdateCommand(
            "Updated email",
            null,
            fixture.contentType.getSchemaVersion(),
            null,
            fixture.contentType.getEntityVersion()));

    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(2);
    verify(contentTypeRepository).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeDoesNotBumpSchemaVersionForEquivalentMetadataSchemaJson() {
    CmsFixture fixture = setupFixture();
    fixture.contentType.setMetadataSchemaJson(
        "{\"properties\":{\"surface\":{\"type\":\"string\"}},\"type\":\"object\"}");

    service.updateContentType(
        fixture.contentType.getId(),
        new CmsContentService.ContentTypeUpdateCommand(
            "Email",
            null,
            fixture.contentType.getSchemaVersion(),
            "{ \"type\" : \"object\", \"properties\" : { \"surface\" : { \"type\" : \"string\" } } }",
            fixture.contentType.getEntityVersion()));

    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(1);
    assertThat(fixture.contentType.getMetadataSchemaJson())
        .isEqualTo("{\"properties\":{\"surface\":{\"type\":\"string\"}},\"type\":\"object\"}");
    verify(contentTypeRepository).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeDoesNotBumpSchemaVersionForEquivalentRequiredPropertyOrder() {
    CmsFixture fixture = setupFixture();
    fixture.contentType.setMetadataSchemaJson(
        "{\"properties\":{\"audience\":{\"type\":\"string\"},\"surface\":{\"type\":\"string\"}},\"required\":[\"audience\",\"surface\"],\"type\":\"object\"}");
    fixture.entry.setMetadataJson("{\"audience\":\"all\",\"surface\":\"email\"}");

    service.updateContentType(
        fixture.contentType.getId(),
        new CmsContentService.ContentTypeUpdateCommand(
            "Email",
            null,
            fixture.contentType.getSchemaVersion(),
            "{\"type\":\"object\",\"required\":[\"surface\",\"audience\"],\"properties\":{\"surface\":{\"type\":\"string\"},\"audience\":{\"type\":\"string\"}}}",
            fixture.contentType.getEntityVersion()));

    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(1);
    assertThat(fixture.contentType.getMetadataSchemaJson())
        .isEqualTo(
            "{\"properties\":{\"audience\":{\"type\":\"string\"},\"surface\":{\"type\":\"string\"}},\"required\":[\"audience\",\"surface\"],\"type\":\"object\"}");
    verify(contentTypeRepository).saveAndFlush(fixture.contentType);
  }

  @Test
  public void createContentTypeFieldRejectsNonLocalizableField() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createContentTypeField(
                    fixture.contentType.getId(),
                    new CmsContentService.ContentTypeFieldCommand(
                        "owner",
                        "Owner",
                        null,
                        CmsContentTypeField.FieldType.TEXT,
                        false,
                        false,
                        0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MVP content type fields must be localizable");
    verify(fieldRepository, never()).saveAndFlush(any(CmsContentTypeField.class));
  }

  @Test
  public void createContentTypeFieldRejectsNegativeSortOrder() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createContentTypeField(
                    fixture.contentType.getId(),
                    new CmsContentService.ContentTypeFieldCommand(
                        "cta", "CTA", null, CmsContentTypeField.FieldType.TEXT, true, false, -1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sort order must be at least 0");
    verify(fieldRepository, never()).saveAndFlush(any(CmsContentTypeField.class));
  }

  @Test
  public void createContentTypeFieldBumpsSchemaVersion() {
    CmsFixture fixture = setupFixture();

    service.createContentTypeField(
        fixture.contentType.getId(),
        new CmsContentService.ContentTypeFieldCommand(
            "cta", "CTA", null, CmsContentTypeField.FieldType.TEXT, true, false, 1));

    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(2);
    verify(fieldRepository).saveAndFlush(any(CmsContentTypeField.class));
    verify(contentTypeRepository).saveAndFlush(fixture.contentType);
  }

  @Test
  public void createContentTypeFieldRejectsRequiredFieldThatInvalidatesReadyEntry() {
    CmsFixture fixture = setupFixture();
    java.util.concurrent.atomic.AtomicReference<CmsContentTypeField> createdField =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(fieldRepository.saveAndFlush(any(CmsContentTypeField.class)))
        .thenAnswer(
            invocation -> {
              CmsContentTypeField field = invocation.getArgument(0);
              field.setId(51L);
              createdField.set(field);
              return field;
            });
    when(fieldRepository.findByContentTypeIdOrderBySortOrderAscFieldKeyAscIdAsc(
            fixture.contentType.getId()))
        .thenAnswer(invocation -> List.of(fixture.field, createdField.get()));

    assertThatThrownBy(
            () ->
                service.createContentTypeField(
                    fixture.contentType.getId(),
                    new CmsContentService.ContentTypeFieldCommand(
                        "cta", "CTA", null, CmsContentTypeField.FieldType.TEXT, true, true, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Ready entry has missing required field mappings: welcome.default.cta");
    verify(contentTypeRepository, never()).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeFieldRejectsNonLocalizableField() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateContentTypeField(
                    fixture.field.getId(),
                    new CmsContentService.ContentTypeFieldUpdateCommand(
                        "Header",
                        null,
                        CmsContentTypeField.FieldType.TEXT,
                        false,
                        true,
                        0,
                        fixture.field.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MVP content type fields must be localizable");
    verify(fieldRepository, never()).saveAndFlush(fixture.field);
  }

  @Test
  public void updateContentTypeFieldRejectsNegativeSortOrder() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateContentTypeField(
                    fixture.field.getId(),
                    new CmsContentService.ContentTypeFieldUpdateCommand(
                        "Header",
                        null,
                        CmsContentTypeField.FieldType.TEXT,
                        true,
                        true,
                        -1,
                        fixture.field.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sort order must be at least 0");
    verify(fieldRepository, never()).saveAndFlush(fixture.field);
  }

  @Test
  public void updateContentTypeFieldBumpsSchemaVersionWhenFieldSchemaChanges() {
    CmsFixture fixture = setupFixture();

    service.updateContentTypeField(
        fixture.field.getId(),
        new CmsContentService.ContentTypeFieldUpdateCommand(
            "Updated header",
            null,
            CmsContentTypeField.FieldType.TEXT,
            true,
            true,
            0,
            fixture.field.getEntityVersion()));

    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(2);
    verify(fieldRepository).saveAndFlush(fixture.field);
    verify(contentTypeRepository).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeFieldDoesNotBumpSchemaVersionWhenDescriptionChanges() {
    CmsFixture fixture = setupFixture();

    service.updateContentTypeField(
        fixture.field.getId(),
        new CmsContentService.ContentTypeFieldUpdateCommand(
            "Header",
            "Authoring note",
            CmsContentTypeField.FieldType.TEXT,
            true,
            true,
            0,
            fixture.field.getEntityVersion()));

    assertThat(fixture.field.getDescription()).isEqualTo("Authoring note");
    assertThat(fixture.contentType.getSchemaVersion()).isEqualTo(1);
    verify(fieldRepository).saveAndFlush(fixture.field);
    verify(contentTypeRepository, never()).saveAndFlush(fixture.contentType);
  }

  @Test
  public void updateContentTypeFieldRejectsIcuTypeWhenExistingMappingSourceIsInvalid() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setContent("{count, plural, one {# item}");
    when(mappingRepository.findByFieldId(fixture.field.getId()))
        .thenReturn(List.of(fixture.mapping));

    assertThatThrownBy(
            () ->
                service.updateContentTypeField(
                    fixture.field.getId(),
                    new CmsContentService.ContentTypeFieldUpdateCommand(
                        "Header",
                        null,
                        CmsContentTypeField.FieldType.ICU_MESSAGE,
                        true,
                        true,
                        0,
                        fixture.field.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ICU message integrity check failed for welcome.default.header source");
    verify(fieldRepository, never()).saveAndFlush(fixture.field);
  }

  @Test
  public void updateContentTypeFieldRejectsIcuTypeWhenReadyMappingTargetIsInvalid() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setContent("{count, plural, one {# item} other {# items}}");
    when(mappingRepository.findByFieldId(fixture.field.getId()))
        .thenReturn(List.of(fixture.mapping));
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "{count, plural, one {# article}",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () ->
                service.updateContentTypeField(
                    fixture.field.getId(),
                    new CmsContentService.ContentTypeFieldUpdateCommand(
                        "Header",
                        null,
                        CmsContentTypeField.FieldType.ICU_MESSAGE,
                        true,
                        true,
                        0,
                        fixture.field.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ICU message integrity check failed for welcome.default.header fr-FR");
    verify(fieldRepository, never()).saveAndFlush(fixture.field);
  }

  @Test
  public void updateContentTypeRejectsSchemaThatInvalidatesExistingEntryMetadata() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateContentType(
                    fixture.contentType.getId(),
                    new CmsContentService.ContentTypeUpdateCommand(
                        "Email",
                        null,
                        1,
                        "{\"type\":\"object\",\"required\":[\"surface\"],\"properties\":{\"surface\":{\"type\":\"string\"}}}",
                        fixture.contentType.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry metadata for welcome is missing required property: surface");
    verify(contentTypeRepository, never()).saveAndFlush(fixture.contentType);
  }

  @Test
  public void createVariantRejectsSecondControlVariant() {
    CmsFixture fixture = setupFixture();
    when(variantRepository.findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(
            fixture.entry.getId()))
        .thenReturn(List.of(fixture.variant));

    assertThatThrownBy(
            () ->
                service.createVariant(
                    fixture.entry.getId(),
                    new CmsContentService.VariantCommand(
                        "another-control",
                        "Another control",
                        null,
                        CmsContentEntryVariant.Status.CONTROL,
                        null,
                        1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content entry already has a control variant: default");
    verify(projectRepository).findByEntryIdWithRepositoryAndAssetForUpdate(fixture.entry.getId());
    verify(entryRepository).findByIdWithProjectAndTypeForUpdate(fixture.entry.getId());
  }

  @Test
  public void createVariantRejectsCandidateWithoutCandidateGroupKey() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createVariant(
                    fixture.entry.getId(),
                    new CmsContentService.VariantCommand(
                        "candidate",
                        "Candidate",
                        null,
                        CmsContentEntryVariant.Status.CANDIDATE,
                        null,
                        1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Candidate variants require a candidate group key");
    verify(variantRepository, never()).saveAndFlush(any(CmsContentEntryVariant.class));
  }

  @Test
  public void createVariantRejectsCandidateInDifferentActiveCandidateGroup() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant existingCandidate = new CmsContentEntryVariant();
    existingCandidate.setId(71L);
    existingCandidate.setEntry(fixture.entry);
    existingCandidate.setContentType(fixture.contentType);
    existingCandidate.setVariantKey("candidate");
    existingCandidate.setName("Candidate");
    existingCandidate.setCandidateGroupKey("welcome-header");
    existingCandidate.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    existingCandidate.setSortOrder(1);
    when(variantRepository.findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(
            fixture.entry.getId()))
        .thenReturn(List.of(fixture.variant, existingCandidate));

    assertThatThrownBy(
            () ->
                service.createVariant(
                    fixture.entry.getId(),
                    new CmsContentService.VariantCommand(
                        "candidate-b",
                        "Candidate B",
                        "welcome-subheader",
                        CmsContentEntryVariant.Status.CANDIDATE,
                        null,
                        2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Content entry candidate variants must share a candidate group key: "
                + "welcome has welcome-header, welcome-subheader");
    verify(variantRepository, never()).saveAndFlush(any(CmsContentEntryVariant.class));
  }

  @Test
  public void createVariantRejectsNegativeSortOrder() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.createVariant(
                    fixture.entry.getId(),
                    new CmsContentService.VariantCommand(
                        "candidate",
                        "Candidate",
                        "welcome-header",
                        CmsContentEntryVariant.Status.CANDIDATE,
                        null,
                        -1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sort order must be at least 0");
    verify(variantRepository, never()).saveAndFlush(any(CmsContentEntryVariant.class));
  }

  @Test
  public void updateVariantPromotesCandidateToControlAfterLockingEntry() {
    CmsFixture fixture = setupFixture();
    fixture.entry.setStatus(CmsContentEntry.Status.DRAFT);
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setCandidateGroupKey("welcome-header");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    auditCmsEntity(candidateVariant);
    when(projectRepository.findByVariantIdWithRepositoryAndAssetForUpdate(candidateVariant.getId()))
        .thenReturn(Optional.of(fixture.project));
    when(entryRepository.findByVariantIdWithProjectAndTypeForUpdate(candidateVariant.getId()))
        .thenReturn(Optional.of(fixture.entry));
    when(variantRepository.findByIdWithEntry(candidateVariant.getId()))
        .thenReturn(Optional.of(candidateVariant));
    when(variantRepository.findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(
            fixture.entry.getId()))
        .thenReturn(List.of(fixture.variant, candidateVariant));
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(fixture.entry.getId())))
        .thenReturn(List.of(fixture.variant, candidateVariant));

    CmsContentService.ProjectDetail detail =
        service.updateVariant(
            candidateVariant.getId(),
            new CmsContentService.VariantUpdateCommand(
                "Candidate",
                "welcome-header",
                CmsContentEntryVariant.Status.CONTROL,
                null,
                1,
                candidateVariant.getEntityVersion()));

    assertThat(fixture.variant.getStatus()).isEqualTo(CmsContentEntryVariant.Status.ARCHIVED);
    assertThat(fixture.variant.getControlEntryId()).isNull();
    assertThat(candidateVariant.getStatus()).isEqualTo(CmsContentEntryVariant.Status.CONTROL);
    assertThat(candidateVariant.getControlEntryId()).isEqualTo(fixture.entry.getId());
    assertThat(detail.entries().get(0).variants())
        .extracting(CmsContentService.VariantView::variantKey)
        .containsExactly("default", "candidate");
    assertThat(detail.entries().get(0).variants())
        .extracting(CmsContentService.VariantView::status)
        .containsExactly(
            CmsContentEntryVariant.Status.ARCHIVED, CmsContentEntryVariant.Status.CONTROL);
    InOrder saveOrder = inOrder(variantRepository);
    saveOrder.verify(variantRepository).saveAndFlush(fixture.variant);
    saveOrder.verify(variantRepository).saveAndFlush(candidateVariant);
    verify(projectRepository)
        .findByVariantIdWithRepositoryAndAssetForUpdate(candidateVariant.getId());
    verify(entryRepository).findByVariantIdWithProjectAndTypeForUpdate(candidateVariant.getId());
  }

  @Test
  public void updateVariantRejectsCandidateWithoutCandidateGroupKey() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setCandidateGroupKey("welcome-header");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    when(projectRepository.findByVariantIdWithRepositoryAndAssetForUpdate(candidateVariant.getId()))
        .thenReturn(Optional.of(fixture.project));
    when(entryRepository.findByVariantIdWithProjectAndTypeForUpdate(candidateVariant.getId()))
        .thenReturn(Optional.of(fixture.entry));
    when(variantRepository.findByIdWithEntry(candidateVariant.getId()))
        .thenReturn(Optional.of(candidateVariant));

    assertThatThrownBy(
            () ->
                service.updateVariant(
                    candidateVariant.getId(),
                    new CmsContentService.VariantUpdateCommand(
                        "Candidate",
                        null,
                        CmsContentEntryVariant.Status.CANDIDATE,
                        null,
                        1,
                        candidateVariant.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Candidate variants require a candidate group key");
    verify(variantRepository, never()).saveAndFlush(candidateVariant);
  }

  @Test
  public void updateVariantRejectsCandidateInDifferentActiveCandidateGroup() {
    CmsFixture fixture = setupFixture();
    CmsContentEntryVariant candidateVariant = new CmsContentEntryVariant();
    candidateVariant.setId(71L);
    candidateVariant.setEntry(fixture.entry);
    candidateVariant.setContentType(fixture.contentType);
    candidateVariant.setVariantKey("candidate");
    candidateVariant.setName("Candidate");
    candidateVariant.setCandidateGroupKey("welcome-header");
    candidateVariant.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    candidateVariant.setSortOrder(1);
    CmsContentEntryVariant otherCandidate = new CmsContentEntryVariant();
    otherCandidate.setId(72L);
    otherCandidate.setEntry(fixture.entry);
    otherCandidate.setContentType(fixture.contentType);
    otherCandidate.setVariantKey("candidate-b");
    otherCandidate.setName("Candidate B");
    otherCandidate.setCandidateGroupKey("welcome-header");
    otherCandidate.setStatus(CmsContentEntryVariant.Status.CANDIDATE);
    otherCandidate.setSortOrder(2);
    when(projectRepository.findByVariantIdWithRepositoryAndAssetForUpdate(candidateVariant.getId()))
        .thenReturn(Optional.of(fixture.project));
    when(entryRepository.findByVariantIdWithProjectAndTypeForUpdate(candidateVariant.getId()))
        .thenReturn(Optional.of(fixture.entry));
    when(variantRepository.findByIdWithEntry(candidateVariant.getId()))
        .thenReturn(Optional.of(candidateVariant));
    when(variantRepository.findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(
            fixture.entry.getId()))
        .thenReturn(List.of(fixture.variant, candidateVariant, otherCandidate));

    assertThatThrownBy(
            () ->
                service.updateVariant(
                    candidateVariant.getId(),
                    new CmsContentService.VariantUpdateCommand(
                        "Candidate",
                        "welcome-subheader",
                        CmsContentEntryVariant.Status.CANDIDATE,
                        null,
                        1,
                        candidateVariant.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Content entry candidate variants must share a candidate group key: "
                + "welcome has welcome-header, welcome-subheader");
    verify(variantRepository, never()).saveAndFlush(candidateVariant);
  }

  @Test
  public void updateVariantRejectsNegativeSortOrder() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.updateVariant(
                    fixture.variant.getId(),
                    new CmsContentService.VariantUpdateCommand(
                        "Default",
                        null,
                        CmsContentEntryVariant.Status.CONTROL,
                        null,
                        -1,
                        fixture.variant.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Sort order must be at least 0");
    verify(variantRepository, never()).saveAndFlush(fixture.variant);
  }

  @Test
  public void updateVariantRejectsRemovingOnlyControlVariant() {
    CmsFixture fixture = setupFixture();
    when(variantRepository.findByEntryIdOrderBySortOrderAscVariantKeyAscIdAsc(
            fixture.entry.getId()))
        .thenReturn(List.of(fixture.variant));

    assertThatThrownBy(
            () ->
                service.updateVariant(
                    fixture.variant.getId(),
                    new CmsContentService.VariantUpdateCommand(
                        "Default",
                        "welcome-header",
                        CmsContentEntryVariant.Status.CANDIDATE,
                        null,
                        0,
                        fixture.variant.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content entry must keep a control variant");
    verify(variantRepository, never()).saveAndFlush(fixture.variant);
  }

  @Test
  public void upsertFieldMappingRegistersGeneratedTextUnitOnVirtualAsset() throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setComment("Translator note");
    when(textUnitUtils.computeTextUnitMD5(
            "cms.growth-email.welcome.default.header", "Hello", "Translator note"))
        .thenReturn("cms-md5");
    when(tmTextUnitRepository.findFirstByAssetAndMd5(fixture.project.getAsset(), "cms-md5"))
        .thenReturn(fixture.tmTextUnit);
    when(mappingRepository.findByVariantIdAndFieldId(
            fixture.variant.getId(), fixture.field.getId()))
        .thenReturn(Optional.empty());
    when(mappingRepository.saveAndFlush(any(CmsContentFieldMapping.class)))
        .thenAnswer(
            invocation -> {
              CmsContentFieldMapping mapping = invocation.getArgument(0);
              mapping.setId(101L);
              return mapping;
            });

    service.upsertFieldMapping(
        fixture.variant.getId(),
        new CmsContentService.FieldMappingCommand(
            fixture.field.getId(), null, "Hello", "Translator note", null));

    verify(virtualTextUnitBatchUpdaterService).updateCmsTextUnits(any(), any(), eq(false));
  }

  @Test
  public void upsertFieldMappingRejectsMissingFieldSelectorBeforeResourceLookup() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        null, null, "Hello", "Translator note", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content type field id is required");
    verify(fieldRepository, never()).findByIdWithContentType(any());
  }

  @Test
  public void upsertFieldMappingRejectsFieldFromAnotherContentTypeBeforeSaving() {
    CmsFixture fixture = setupFixture();
    CmsContentType otherContentType = new CmsContentType();
    otherContentType.setId(41L);
    otherContentType.setProject(fixture.project);
    otherContentType.setTypeKey("banner");
    otherContentType.setName("Banner");
    otherContentType.setSchemaVersion(1);
    auditCmsEntity(otherContentType);
    CmsContentTypeField otherField = new CmsContentTypeField();
    otherField.setId(51L);
    otherField.setContentType(otherContentType);
    otherField.setFieldKey("body");
    otherField.setName("Body");
    otherField.setFieldType(CmsContentTypeField.FieldType.TEXT);
    otherField.setLocalizable(true);
    otherField.setRequired(true);
    otherField.setSortOrder(0);
    auditCmsEntity(otherField);
    when(fieldRepository.findByIdWithContentType(otherField.getId()))
        .thenReturn(Optional.of(otherField));

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        otherField.getId(), null, "Hello", "Shown in banner body", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Field does not belong to the entry content type: " + otherField.getId());
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingUpdatesGeneratedTextUnitWithStableCmsStringId() throws Exception {
    CmsFixture fixture = setupFixture();
    TMTextUnit updatedTextUnit = new TMTextUnit();
    updatedTextUnit.setId(81L);
    updatedTextUnit.setAsset(fixture.project.getAsset());
    updatedTextUnit.setName("cms.growth-email.welcome.default.header");
    updatedTextUnit.setContent("Updated hello");
    updatedTextUnit.setComment("Updated translator note");
    when(mappingRepository.findByVariantIdAndFieldId(
            fixture.variant.getId(), fixture.field.getId()))
        .thenReturn(Optional.of(fixture.mapping));
    when(textUnitUtils.computeTextUnitMD5(
            "cms.growth-email.welcome.default.header", "Updated hello", "Updated translator note"))
        .thenReturn("updated-cms-md5");
    when(tmTextUnitRepository.findFirstByAssetAndMd5(fixture.project.getAsset(), "updated-cms-md5"))
        .thenReturn(updatedTextUnit);

    service.upsertFieldMapping(
        fixture.variant.getId(),
        new CmsContentService.FieldMappingCommand(
            fixture.field.getId(),
            null,
            null,
            "Updated hello",
            "Updated translator note",
            fixture.mapping.getEntityVersion()));

    verify(virtualTextUnitBatchUpdaterService)
        .updateCmsTextUnits(
            eq(fixture.project.getAsset()),
            argThat(
                textUnits ->
                    textUnits.size() == 1
                        && "cms.growth-email.welcome.default.header"
                            .equals(textUnits.getFirst().getName())
                        && "Updated hello".equals(textUnits.getFirst().getContent())
                        && "Updated translator note".equals(textUnits.getFirst().getComment())),
            eq(false));
    verify(mappingRepository)
        .saveAndFlush(argThat(mapping -> updatedTextUnit.equals(mapping.getTmTextUnit())));
  }

  @Test
  public void upsertFieldMappingAcceptsGeneratedLongSourceComment() throws Exception {
    CmsFixture fixture = setupFixture();
    String sourceComment = "x".repeat(2049);
    fixture.tmTextUnit.setComment(sourceComment);
    when(textUnitUtils.computeTextUnitMD5(
            "cms.growth-email.welcome.default.header", "Hello", sourceComment))
        .thenReturn("cms-md5");
    when(tmTextUnitRepository.findFirstByAssetAndMd5(fixture.project.getAsset(), "cms-md5"))
        .thenReturn(fixture.tmTextUnit);
    when(mappingRepository.findByVariantIdAndFieldId(
            fixture.variant.getId(), fixture.field.getId()))
        .thenReturn(Optional.empty());
    when(mappingRepository.saveAndFlush(any(CmsContentFieldMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.upsertFieldMapping(
        fixture.variant.getId(),
        new CmsContentService.FieldMappingCommand(
            fixture.field.getId(), null, "Hello", sourceComment, null));

    verify(virtualTextUnitBatchUpdaterService)
        .updateCmsTextUnits(
            eq(fixture.project.getAsset()),
            argThat(
                textUnits ->
                    textUnits.size() == 1
                        && sourceComment.equals(textUnits.getFirst().getComment())),
            eq(false));
  }

  @Test
  public void unmapFieldMappingLocksProjectAndRemovesCmsBinding() {
    CmsFixture fixture = setupFixture();
    fixture.entry.setStatus(CmsContentEntry.Status.DRAFT);

    service.unmapFieldMapping(
        fixture.mapping.getId(),
        new CmsContentService.FieldMappingDeleteCommand(fixture.mapping.getEntityVersion()));

    InOrder inOrder = inOrder(projectRepository, mappingRepository);
    inOrder
        .verify(projectRepository)
        .findByFieldMappingIdWithRepositoryAndAssetForUpdate(fixture.mapping.getId());
    inOrder.verify(mappingRepository).findByIdWithVariantFieldAndTextUnit(fixture.mapping.getId());
    inOrder.verify(mappingRepository).delete(fixture.mapping);
    inOrder.verify(mappingRepository).flush();
  }

  @Test
  public void unmapFieldMappingRejectsRemovingLastFieldFromReadyEntry() {
    CmsFixture fixture = setupFixture();
    when(mappingRepository.findMappingsByEntryId(fixture.entry.getId())).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                service.unmapFieldMapping(
                    fixture.mapping.getId(),
                    new CmsContentService.FieldMappingDeleteCommand(
                        fixture.mapping.getEntityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Ready entry has publishable variants without mapped localizable fields: welcome.default");
    verify(mappingRepository).delete(fixture.mapping);
    verify(mappingRepository).flush();
  }

  @Test
  public void unmapFieldMappingRejectsStaleExpectedVersion() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.unmapFieldMapping(
                    fixture.mapping.getId(), new CmsContentService.FieldMappingDeleteCommand(99L)))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("Field mapping changed since it was loaded");
    verify(mappingRepository, never()).delete(fixture.mapping);
  }

  @Test
  public void upsertFieldMappingMapsActiveMojitoStringId() {
    CmsFixture fixture = setupFixture();
    when(assetTextUnitToTMTextUnitRepository.findTmTextUnitsByAssetExtractionIdAndAssetTextUnitName(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getName()))
        .thenReturn(List.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());
    when(mappingRepository.saveAndFlush(any(CmsContentFieldMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.upsertFieldMapping(
        fixture.variant.getId(),
        new CmsContentService.FieldMappingCommand(
            fixture.field.getId(),
            null,
            fixture.tmTextUnit.getName(),
            "Hello",
            fixture.tmTextUnit.getComment(),
            null));

    verify(tmTextUnitRepository, never()).findById(any());
    verify(mappingRepository)
        .saveAndFlush(argThat(mapping -> fixture.tmTextUnit.equals(mapping.getTmTextUnit())));
  }

  @Test
  public void upsertFieldMappingRejectsAmbiguousActiveMojitoStringId() {
    CmsFixture fixture = setupFixture();
    TMTextUnit pluralTextUnit = new TMTextUnit();
    pluralTextUnit.setId(81L);
    pluralTextUnit.setAsset(fixture.project.getAsset());
    pluralTextUnit.setName(fixture.tmTextUnit.getName());
    pluralTextUnit.setContent("Hello many");
    pluralTextUnit.setComment(fixture.tmTextUnit.getComment());
    when(assetTextUnitToTMTextUnitRepository.findTmTextUnitsByAssetExtractionIdAndAssetTextUnitName(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getName()))
        .thenReturn(List.of(fixture.tmTextUnit, pluralTextUnit));

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(),
                        null,
                        fixture.tmTextUnit.getName(),
                        "Hello",
                        fixture.tmTextUnit.getComment(),
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Mapped string ID resolves to multiple active text units; use TM text unit ID");
    verify(tmTextUnitRepository, never()).findById(any());
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingMapsExactTmTextUnitIdWhenStringIdIsAmbiguous() {
    CmsFixture fixture = setupFixture();
    TMTextUnit pluralTextUnit = new TMTextUnit();
    pluralTextUnit.setId(81L);
    pluralTextUnit.setAsset(fixture.project.getAsset());
    pluralTextUnit.setName(fixture.tmTextUnit.getName());
    pluralTextUnit.setContent("Hello many");
    pluralTextUnit.setComment(fixture.tmTextUnit.getComment());
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findTmTextUnitsByAssetExtractionIdAndAssetTextUnitName(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getName()))
        .thenReturn(List.of(fixture.tmTextUnit, pluralTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());
    when(mappingRepository.saveAndFlush(any(CmsContentFieldMapping.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.upsertFieldMapping(
        fixture.variant.getId(),
        new CmsContentService.FieldMappingCommand(
            fixture.field.getId(),
            fixture.tmTextUnit.getId(),
            null,
            "Hello",
            fixture.tmTextUnit.getComment(),
            null));

    verify(assetTextUnitToTMTextUnitRepository, never())
        .findTmTextUnitsByAssetExtractionIdAndAssetTextUnitName(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getName());
    verify(mappingRepository)
        .saveAndFlush(argThat(mapping -> fixture.tmTextUnit.equals(mapping.getTmTextUnit())));
  }

  @Test
  public void getProjectCompletenessFencesExactTmBindingWithSameRuntimePackage() {
    CmsFixture fixture = setupFixture();
    CmsContentService.ProjectCompletenessView before =
        service.getProjectCompleteness(fixture.project.getId(), List.of("en"));
    TMTextUnit remappedTextUnit = new TMTextUnit();
    remappedTextUnit.setId(81L);
    remappedTextUnit.setAsset(fixture.project.getAsset());
    remappedTextUnit.setName(fixture.tmTextUnit.getName());
    remappedTextUnit.setContent(fixture.tmTextUnit.getContent());
    remappedTextUnit.setComment(fixture.tmTextUnit.getComment());
    fixture.mapping.setTmTextUnit(remappedTextUnit);

    CmsContentService.ProjectCompletenessView after =
        service.getProjectCompleteness(fixture.project.getId(), List.of("en"));

    assertThat(after.publishPackageSha256()).isEqualTo(before.publishPackageSha256());
    assertThat(after.authoringSha256()).isNotEqualTo(before.authoringSha256());
  }

  @Test
  public void upsertFieldMappingRejectsAmbiguousExistingTextUnitSelectors() {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(),
                        fixture.tmTextUnit.getId(),
                        fixture.tmTextUnit.getName(),
                        "Hello",
                        fixture.tmTextUnit.getComment(),
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Map using either TM text unit ID or string ID, not both");
    verify(tmTextUnitRepository, never()).findById(any());
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsGeneratedTextUnitWithoutTranslatorContextBeforeWriting()
      throws Exception {
    CmsFixture fixture = setupFixture();

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), null, "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Translator context is required for mapping: welcome.default.header");
    verify(virtualTextUnitBatchUpdaterService, never()).updateCmsTextUnits(any(), any(), eq(false));
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsInactiveMappedTextUnit() {
    CmsFixture fixture = setupFixture();
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mapped text unit must be active");
  }

  @Test
  public void upsertFieldMappingRejectsMappedTextUnitFromAnotherCmsAsset() {
    CmsFixture fixture = setupFixture();
    Asset otherAsset = new Asset();
    otherAsset.setId(61L);
    fixture.tmTextUnit.setAsset(otherAsset);
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mapped text unit must belong to the content project asset");
    verify(assetTextUnitToTMTextUnitRepository, never())
        .findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId());
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsMismatchedMappedTextUnitComment() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setComment("Translator note");
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(),
                        fixture.tmTextUnit.getId(),
                        "Hello",
                        "Wrong note",
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Translator context does not match the mapped text unit");
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsMappedTextUnitWithoutTranslatorContext() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setComment(null);
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Translator context is required for mapping: welcome.default.header");
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsMappedTextUnitWithoutSourceContent() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setContent(" \t ");
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source content is required for mapping: welcome.default.header");
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsMappedTextUnitWithoutMojitoStringId() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setName(" \t ");
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mojito string ID is required for mapping: welcome.default.header");
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsDoNotTranslateMappedTextUnit() {
    CmsFixture fixture = setupFixture();
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(2L));

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Mapped text unit must be translatable");
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsTextUnitAlreadyBoundToAnotherCmsField() {
    CmsFixture fixture = setupFixture();
    CmsContentTypeField ctaField = new CmsContentTypeField();
    ctaField.setId(51L);
    ctaField.setContentType(fixture.contentType);
    ctaField.setFieldKey("cta");
    ctaField.setName("CTA");
    ctaField.setFieldType(CmsContentTypeField.FieldType.TEXT);
    ctaField.setLocalizable(true);
    ctaField.setRequired(true);
    ctaField.setSortOrder(1);

    CmsContentFieldMapping ctaMapping = new CmsContentFieldMapping();
    ctaMapping.setId(91L);
    ctaMapping.setVariant(fixture.variant);
    ctaMapping.setField(ctaField);
    ctaMapping.setContentType(fixture.contentType);
    ctaMapping.setTmTextUnit(fixture.tmTextUnit);
    when(tmTextUnitRepository.findById(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(fixture.tmTextUnit));
    when(assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(1L));
    when(assetTextUnitToTMTextUnitRepository.findDoNotTranslateIdByAssetExtractionIdAndTmTextUnitId(
            fixture.project.getAsset().getLastSuccessfulAssetExtraction().getId(),
            fixture.tmTextUnit.getId()))
        .thenReturn(Optional.empty());
    when(mappingRepository.findByTmTextUnitId(fixture.tmTextUnit.getId()))
        .thenReturn(Optional.of(ctaMapping));

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), "Hello", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Mapped text unit is already bound to CMS field: welcome.default.cta");
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsInvalidGeneratedIcuMessageSource() throws Exception {
    CmsFixture fixture = setupFixture();
    fixture.field.setFieldType(CmsContentTypeField.FieldType.ICU_MESSAGE);
    String sourceContent = "{count, plural, one {# item}";

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), null, sourceContent, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "ICU message integrity check failed for welcome.default.header source");
    verify(virtualTextUnitBatchUpdaterService, never()).updateCmsTextUnits(any(), any(), eq(false));
    verify(mappingRepository, never()).saveAndFlush(any(CmsContentFieldMapping.class));
  }

  @Test
  public void upsertFieldMappingRejectsStaleExistingMappingBeforeResolvingTextUnit() {
    CmsFixture fixture = setupFixture();
    when(mappingRepository.findByVariantIdAndFieldId(
            fixture.variant.getId(), fixture.field.getId()))
        .thenReturn(Optional.of(fixture.mapping));

    assertThatThrownBy(
            () ->
                service.upsertFieldMapping(
                    fixture.variant.getId(),
                    new CmsContentService.FieldMappingCommand(
                        fixture.field.getId(), fixture.tmTextUnit.getId(), "Hello", null, null)))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("Field mapping changed since it was loaded");
    verify(tmTextUnitRepository, never()).findById(fixture.tmTextUnit.getId());
  }

  @Test
  public void getProjectShowsAuthoritativeMappedTextUnitSourceCopy() {
    CmsFixture fixture = setupFixture();
    fixture.tmTextUnit.setContent("Current source");
    fixture.tmTextUnit.setComment("Current context");

    CmsContentService.ProjectDetail detail = service.getProject(fixture.project.getId());
    CmsContentService.FieldMappingView mapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);

    assertThat(detail.authoringSha256()).isEqualTo(publishRequestAuthoringSha256(fixture));
    assertThat(mapping.sourceContent()).isEqualTo("Current source");
    assertThat(mapping.sourceComment()).isEqualTo("Current context");
    verify(projectRepository).findByIdWithRepositoryAndAssetForUpdate(fixture.project.getId());
    verify(projectRepository, never()).findByIdWithRepositoryAndAsset(fixture.project.getId());
  }

  @Test
  public void getProjectRejectsAuthoringDetailAboveConfiguredByteLimit() {
    CmsFixture fixture = setupFixture();
    configurationProperties.setMaxAuthoringDetailBytes(1);

    assertThatThrownBy(() -> service.getProject(fixture.project.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authoring detail exceeds configured byte limit");
  }

  @Test
  public void getProjectRejectsNonPositiveConfiguredAuthoringDetailByteLimit() {
    CmsFixture fixture = setupFixture();
    configurationProperties.setMaxAuthoringDetailBytes(0);

    assertThatThrownBy(() -> service.getProject(fixture.project.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS max authoring detail bytes must be positive");
  }

  @Test
  public void getProjectExposesServerCursorForRetainedSnapshotHistory() {
    CmsFixture fixture = setupFixture();
    stubSnapshotHistory(fixture.project, 11);
    when(snapshotRepository.findHistoryRowsByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 11)))
        .thenReturn(
            java.util.stream.IntStream.iterate(11, version -> version >= 1, version -> version - 1)
                .mapToObj(version -> publishSnapshotHistoryRow(fixture.project, version))
                .toList());

    CmsContentService.ProjectDetail detail = service.getProject(fixture.project.getId());

    assertThat(detail.publishSnapshots())
        .extracting(CmsContentService.PublishSnapshotView::snapshotVersion)
        .containsExactly(11, 10, 9, 8, 7, 6, 5, 4, 3, 2);
    assertThat(detail.hasMorePublishSnapshots()).isTrue();
    assertThat(detail.nextBeforePublishSnapshotVersion()).isEqualTo(2);
  }

  @Test
  public void getProjectPublishSnapshotsPagesRetainedMetadataWithoutLoadingOlderArtifacts() {
    CmsFixture fixture = setupFixture();
    stubSnapshotHistory(fixture.project, 11);
    when(snapshotRepository.findHistoryRowsByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 11)))
        .thenReturn(
            java.util.stream.IntStream.iterate(11, version -> version >= 1, version -> version - 1)
                .mapToObj(version -> publishSnapshotHistoryRow(fixture.project, version))
                .toList());
    when(snapshotRepository
            .findHistoryRowsByProjectIdBeforeSnapshotVersionOrderBySnapshotVersionDesc(
                fixture.project.getId(), 2, PageRequest.of(0, 11)))
        .thenReturn(List.of(publishSnapshotHistoryRow(fixture.project, 1)));

    CmsContentService.PublishSnapshotHistoryView firstPage =
        service.getProjectPublishSnapshots(fixture.project.getId(), null, null);
    CmsContentService.PublishSnapshotHistoryView secondPage =
        service.getProjectPublishSnapshots(fixture.project.getId(), 2, null);

    assertThat(firstPage.snapshots())
        .extracting(CmsContentService.PublishSnapshotView::snapshotVersion)
        .containsExactly(11, 10, 9, 8, 7, 6, 5, 4, 3, 2);
    assertThat(firstPage.hasMore()).isTrue();
    assertThat(firstPage.nextBeforeSnapshotVersion()).isEqualTo(2);
    assertThat(secondPage.snapshots())
        .extracting(CmsContentService.PublishSnapshotView::snapshotVersion)
        .containsExactly(1);
    assertThat(secondPage.hasMore()).isFalse();
    assertThat(secondPage.nextBeforeSnapshotVersion()).isNull();
    verify(snapshotRepository, times(2))
        .findByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId(), PageRequest.of(0, 10));
  }

  @Test
  public void getProjectPublishSnapshotsValidatesRecentArtifactsBeforeLoadingOlderMetadata() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot recentSnapshot = validPublishSnapshot(fixture.project);
    stubSnapshotHistory(fixture.project, 11);
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of(recentSnapshot));
    when(snapshotRepository
            .findHistoryRowsByProjectIdBeforeSnapshotVersionOrderBySnapshotVersionDesc(
                fixture.project.getId(), 2, PageRequest.of(0, 11)))
        .thenReturn(List.of(publishSnapshotHistoryRow(fixture.project, 1)));

    CmsContentService.PublishSnapshotHistoryView olderPage =
        service.getProjectPublishSnapshots(fixture.project.getId(), 2, null);

    assertThat(olderPage.snapshots())
        .extracting(CmsContentService.PublishSnapshotView::snapshotVersion)
        .containsExactly(1);
    verify(snapshotRepository)
        .findByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId(), PageRequest.of(0, 10));
  }

  @Test
  public void getProjectPublishSnapshotsDoesNotLetSmallPageShrinkRecentValidationWindow() {
    CmsFixture fixture = setupFixture();
    CmsPublishSnapshot recentSnapshot = validPublishSnapshot(fixture.project);
    stubSnapshotHistory(fixture.project, 20);
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of(recentSnapshot));

    assertThatThrownBy(() -> service.getProjectPublishSnapshots(fixture.project.getId(), 15, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Before snapshot version must not skip recent validated snapshot history: 15 > 11");

    verify(snapshotRepository)
        .findByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId(), PageRequest.of(0, 10));
    verify(snapshotRepository, never())
        .findHistoryRowsByProjectIdBeforeSnapshotVersionOrderBySnapshotVersionDesc(
            any(), any(), any());
  }

  @Test
  public void getProjectPublishSnapshotsRejectsHistoryCursorSkippingValidatedRecentPage() {
    CmsFixture fixture = setupFixture();
    stubSnapshotHistory(fixture.project, 20);

    assertThatThrownBy(() -> service.getProjectPublishSnapshots(fixture.project.getId(), 15, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Before snapshot version must not skip recent validated snapshot history: 15 > 11");

    verify(snapshotRepository, never())
        .findHistoryRowsByProjectIdBeforeSnapshotVersionOrderBySnapshotVersionDesc(
            any(), any(), any());
  }

  @Test
  public void getProjectPublishSnapshotsValidatesRequestedRecentHistoryPageLimit() {
    CmsFixture fixture = setupFixture();
    stubSnapshotHistory(fixture.project, 0);
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 50)))
        .thenReturn(List.of());
    when(snapshotRepository.findHistoryRowsByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 51)))
        .thenReturn(List.of());

    service.getProjectPublishSnapshots(fixture.project.getId(), null, 50);

    verify(snapshotRepository)
        .findByProjectIdOrderBySnapshotVersionDesc(fixture.project.getId(), PageRequest.of(0, 50));
  }

  @Test
  public void getProjectPublishSnapshotsRejectsOverlongStoredSigningKeyId() {
    CmsFixture fixture = setupFixture();
    stubSnapshotHistory(fixture.project, 1);
    String overlongKeyId = "a".repeat(CmsPublishSnapshot.SNAPSHOT_SIGNING_KEY_ID_MAX_LENGTH + 1);
    CmsPublishSnapshotHistoryRow invalidHistoryRow =
        new CmsPublishSnapshotHistoryRow(
            101L,
            fixture.project.getId(),
            fixture.project.getProjectKey(),
            1,
            CmsPublishSnapshot.Status.PUBLISHED,
            "en",
            "a".repeat(64),
            512L,
            overlongKeyId,
            "f".repeat(64),
            "e".repeat(64),
            "admin",
            "2026-01-01T00:00:00Z");
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of());
    when(snapshotRepository.findHistoryRowsByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 11)))
        .thenReturn(List.of(invalidHistoryRow));

    assertThatThrownBy(
            () -> service.getProjectPublishSnapshots(fixture.project.getId(), null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot history row is invalid: 101");
  }

  @Test
  public void getProjectPublishSnapshotsRejectsZeroStoredArtifactByteSize() {
    CmsFixture fixture = setupFixture();
    stubSnapshotHistory(fixture.project, 11);
    CmsPublishSnapshotHistoryRow invalidHistoryRow =
        new CmsPublishSnapshotHistoryRow(
            101L,
            fixture.project.getId(),
            fixture.project.getProjectKey(),
            1,
            CmsPublishSnapshot.Status.PUBLISHED,
            "en",
            "a".repeat(64),
            0L,
            "test-v1",
            "f".repeat(64),
            "e".repeat(64),
            "admin",
            "2026-01-01T00:00:00Z");
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            fixture.project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of());
    when(snapshotRepository
            .findHistoryRowsByProjectIdBeforeSnapshotVersionOrderBySnapshotVersionDesc(
                fixture.project.getId(), 2, PageRequest.of(0, 11)))
        .thenReturn(List.of(invalidHistoryRow));

    assertThatThrownBy(() -> service.getProjectPublishSnapshots(fixture.project.getId(), 2, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot history row is invalid: 101");
  }

  @Test
  public void getProjectCompletenessRejectsPublishPackageAboveConfiguredArtifactByteLimit() {
    CmsFixture fixture = setupFixture();
    configurationProperties.setMaxPublishArtifactBytes(1);
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publish package exceeds configured artifact byte limit");
  }

  @Test
  public void getProjectCompletenessRequiresFinalArtifactEnvelopeHeadroom() {
    CmsFixture fixture = setupFixture();
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));
    CmsContentService.ProjectCompletenessView completeness =
        service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR"));
    configurationProperties.setMaxPublishArtifactBytes(completeness.publishPackageByteSize());

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publish artifact exceeds configured artifact byte limit");
  }

  @Test
  public void getProjectCompletenessRejectsNonPositiveConfiguredArtifactByteLimit() {
    CmsFixture fixture = setupFixture();
    configurationProperties.setMaxPublishArtifactBytes(0);
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS max publish artifact bytes must be positive");
  }

  @Test
  public void getProjectCompletenessRejectsMissingSnapshotSigningConfiguration() {
    CmsFixture fixture = setupFixture();
    configurationProperties.setSnapshotSigningKeyId(null);
    when(snapshotRepository.findCurrentVariantRows(any(), any()))
        .thenReturn(
            List.of(
                new CmsCurrentVariantRow(
                    fixture.tmTextUnit.getId(),
                    "fr-FR",
                    "Bonjour",
                    TMTextUnitVariant.Status.APPROVED,
                    true)));

    assertThatThrownBy(
            () -> service.getProjectCompleteness(fixture.project.getId(), List.of("fr-FR")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS snapshot active signing key ID is missing");
  }

  private CmsFixture setupFixture() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(userService.getCurrentUser()).thenReturn(Optional.of(user(100L, "admin")));

    Locale sourceLocale = locale(1L, "en");
    Locale targetLocale = locale(2L, "fr-FR");

    Repository repository = new Repository();
    repository.setId(10L);
    repository.setName("Product copy");
    repository.setSourceLocale(sourceLocale);

    RepositoryLocale sourceRepositoryLocale =
        new RepositoryLocale(repository, sourceLocale, false, null);
    RepositoryLocale targetRepositoryLocale =
        new RepositoryLocale(repository, targetLocale, true, sourceRepositoryLocale);
    repository.setRepositoryLocales(
        new LinkedHashSet<>(List.of(sourceRepositoryLocale, targetRepositoryLocale)));

    Asset asset = new Asset();
    asset.setId(20L);
    asset.setRepository(repository);
    asset.setPath("cms/growth-email");
    asset.setVirtual(true);
    asset.setCmsManaged(true);
    AssetExtraction assetExtraction = new AssetExtraction();
    assetExtraction.setId(25L);
    asset.setLastSuccessfulAssetExtraction(assetExtraction);

    CmsContentProject project = new CmsContentProject();
    project.setId(30L);
    project.setProjectKey("growth-email");
    project.setName("Growth email");
    project.setEnabled(true);
    project.setRepository(repository);
    project.setAsset(asset);
    project.setDeliveryHint("BLOB_CDN");
    auditCmsEntity(project);

    CmsContentType contentType = new CmsContentType();
    contentType.setId(40L);
    contentType.setProject(project);
    contentType.setTypeKey("email");
    contentType.setName("Email");
    contentType.setSchemaVersion(1);
    auditCmsEntity(contentType);

    CmsContentTypeField field = new CmsContentTypeField();
    field.setId(50L);
    field.setContentType(contentType);
    field.setFieldKey("header");
    field.setName("Header");
    field.setFieldType(CmsContentTypeField.FieldType.TEXT);
    field.setLocalizable(true);
    field.setRequired(true);
    field.setSortOrder(0);
    auditCmsEntity(field);

    CmsContentEntry entry = new CmsContentEntry();
    entry.setId(60L);
    entry.setProject(project);
    entry.setContentType(contentType);
    entry.setEntryKey("welcome");
    entry.setName("Welcome");
    entry.setStatus(CmsContentEntry.Status.READY);
    auditCmsEntity(entry);

    CmsContentEntryVariant variant = new CmsContentEntryVariant();
    variant.setId(70L);
    variant.setEntry(entry);
    variant.setContentType(contentType);
    variant.setVariantKey("default");
    variant.setName("Default");
    variant.setStatus(CmsContentEntryVariant.Status.CONTROL);
    variant.setControlEntryId(entry.getId());
    variant.setSortOrder(0);
    auditCmsEntity(variant);

    TMTextUnit tmTextUnit = new TMTextUnit();
    tmTextUnit.setId(80L);
    tmTextUnit.setAsset(asset);
    tmTextUnit.setName("cms.growth-email.welcome.default.header");
    tmTextUnit.setContent("Hello");
    tmTextUnit.setComment("Shown in welcome email header");

    CmsContentFieldMapping mapping = new CmsContentFieldMapping();
    mapping.setId(90L);
    mapping.setVariant(variant);
    mapping.setField(field);
    mapping.setContentType(contentType);
    mapping.setTmTextUnit(tmTextUnit);
    auditCmsEntity(mapping);

    when(projectRepository.findByIdWithRepositoryAndAsset(project.getId()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByProjectKeyWithRepositoryAndAssetForUpdate(project.getProjectKey()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByIdWithRepositoryAndAssetForUpdate(project.getId()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByContentTypeIdWithRepositoryAndAssetForUpdate(contentType.getId()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByFieldIdWithRepositoryAndAssetForUpdate(field.getId()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByEntryIdWithRepositoryAndAssetForUpdate(entry.getId()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByVariantIdWithRepositoryAndAssetForUpdate(variant.getId()))
        .thenReturn(Optional.of(project));
    when(projectRepository.findByFieldMappingIdWithRepositoryAndAssetForUpdate(mapping.getId()))
        .thenReturn(Optional.of(project));
    when(entryRepository.findByIdWithProjectAndType(entry.getId())).thenReturn(Optional.of(entry));
    when(entryRepository.findByIdWithProjectAndTypeForUpdate(entry.getId()))
        .thenReturn(Optional.of(entry));
    when(entryRepository.findByVariantIdWithProjectAndTypeForUpdate(variant.getId()))
        .thenReturn(Optional.of(entry));
    when(variantRepository.findByIdWithEntry(variant.getId())).thenReturn(Optional.of(variant));
    when(contentTypeRepository.findByIdWithProject(contentType.getId()))
        .thenReturn(Optional.of(contentType));
    when(fieldRepository.findByIdWithContentType(field.getId())).thenReturn(Optional.of(field));
    when(mappingRepository.findByVariantIdAndFieldId(variant.getId(), field.getId()))
        .thenReturn(Optional.empty());
    when(mappingRepository.findByIdWithVariantFieldAndTextUnit(mapping.getId()))
        .thenReturn(Optional.of(mapping));
    when(mappingRepository.findByTmTextUnitId(tmTextUnit.getId())).thenReturn(Optional.empty());
    when(mappingRepository.findByFieldId(field.getId())).thenReturn(List.of());
    when(mappingRepository.findMappingsByProjectId(project.getId())).thenReturn(List.of(mapping));
    when(mappingRepository.findMappingsByEntryId(entry.getId())).thenReturn(List.of(mapping));
    when(assetTextUnitToTMTextUnitRepository.findTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(
            any(), any()))
        .thenAnswer(
            invocation -> {
              Set<Long> textUnitIds = invocation.getArgument(1);
              return textUnitIds == null ? Set.of() : Set.copyOf(textUnitIds);
            });
    when(assetTextUnitToTMTextUnitRepository
            .findDoNotTranslateTmTextUnitIdsByAssetExtractionIdAndTmTextUnitIdIn(any(), any()))
        .thenReturn(Set.of());
    when(contentTypeRepository.findByProjectIdOrderByNameAscIdAsc(project.getId()))
        .thenReturn(List.of(contentType));
    when(fieldRepository.findByContentTypeIdOrderBySortOrderAscFieldKeyAscIdAsc(
            contentType.getId()))
        .thenReturn(List.of(field));
    when(fieldRepository.findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
            List.of(contentType.getId())))
        .thenReturn(List.of(field));
    when(entryRepository.findByProjectIdOrderByNameAscIdAsc(project.getId()))
        .thenReturn(List.of(entry));
    when(entryRepository.findByContentTypeIdOrderByNameAscIdAsc(contentType.getId()))
        .thenReturn(List.of(entry));
    when(variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            List.of(entry.getId())))
        .thenReturn(List.of(variant));
    when(snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            project.getId(), PageRequest.of(0, 10)))
        .thenReturn(List.of());
    when(snapshotRepository.findHistoryRowsByProjectIdOrderBySnapshotVersionDesc(
            project.getId(), PageRequest.of(0, 11)))
        .thenReturn(List.of());
    when(snapshotRepository.findByProjectIdAndPublishRequestKey(eq(project.getId()), any()))
        .thenReturn(Optional.empty());
    when(projectRepository.advanceLastPublishedSnapshotVersion(eq(project.getId()), any(), any()))
        .thenAnswer(
            invocation -> {
              project.setLastPublishedSnapshotVersion(invocation.getArgument(2));
              return 1;
            });

    CmsFixture fixture =
        new CmsFixture(project, contentType, field, entry, variant, mapping, tmTextUnit);
    when(snapshotRepository.save(any(CmsPublishSnapshot.class)))
        .thenAnswer(
            invocation -> {
              CmsPublishSnapshot snapshot = invocation.getArgument(0);
              snapshot.setId(99L);
              fixture.savedSnapshot = snapshot;
              return snapshot;
            });
    return fixture;
  }

  private Locale locale(Long id, String bcp47Tag) {
    Locale locale = new Locale();
    locale.setId(id);
    locale.setBcp47Tag(bcp47Tag);
    return locale;
  }

  private void auditCmsEntity(CmsAuditableEntity entity) {
    User actor = user(100L, "admin");
    entity.setCreatedByUser(actor);
    entity.setLastModifiedByUser(actor);
  }

  private User user(Long id, String username) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    return user;
  }

  private CmsPublishSnapshot publishSnapshot(CmsContentProject project, String artifactJson) {
    CmsPublishSnapshot snapshot = new CmsPublishSnapshot();
    snapshot.setId(99L);
    snapshot.setProject(project);
    snapshot.setSnapshotVersion(2);
    snapshot.setStatus(CmsPublishSnapshot.Status.PUBLISHED);
    snapshot.setCreatedByUser(user(100L, "admin"));
    snapshot.setCreatedByUsername("admin");
    snapshot.setPublishedAt("2026-01-01T00:00:00Z");
    snapshot.setPublishRequestKey("publish-request");
    snapshot.setPublishRequestLocaleTags("");
    snapshot.setPublishRequestAuthoringSha256(DigestUtils.sha256Hex("{}"));
    snapshot.setPublishRequestPackageSha256(VALID_PACKAGE_SHA256);
    snapshot.setLocaleTags("en");
    updateSnapshotArtifact(snapshot, artifactJson);
    snapshot.setCompletenessJson("[]");
    resignSnapshot(snapshot);
    stubSnapshotHistory(project, snapshot.getSnapshotVersion());
    return snapshot;
  }

  private CmsPublishSnapshot validPublishSnapshot(CmsContentProject project) {
    return validPublishSnapshot(project, project.getProjectKey(), List.of("en"));
  }

  private CmsPublishSnapshot validPublishSnapshot(
      CmsContentProject project, String artifactProjectKey, List<String> localeTags) {
    CmsPublishSnapshot snapshot =
        publishSnapshot(project, validArtifactJson(artifactProjectKey, localeTags));
    snapshot.setLocaleTags(String.join(",", localeTags));
    snapshot.setCompletenessJson(validCompletenessJson(localeTags, 1));
    resignSnapshot(snapshot);
    return snapshot;
  }

  private CmsPublishSnapshotHistoryRow publishSnapshotHistoryRow(
      CmsContentProject project, int snapshotVersion) {
    return new CmsPublishSnapshotHistoryRow(
        100L + snapshotVersion,
        project.getId(),
        project.getProjectKey(),
        snapshotVersion,
        CmsPublishSnapshot.Status.PUBLISHED,
        "en",
        "a".repeat(64),
        512L,
        "test-v1",
        "f".repeat(64),
        "e".repeat(64),
        "admin",
        "2026-01-01T00:00:00Z");
  }

  private CmsContentService.SnapshotArtifact getSnapshotArtifact(CmsPublishSnapshot snapshot) {
    return service.getSnapshotArtifact(
        snapshot.getProject().getProjectKey(), snapshot.getSnapshotVersion());
  }

  private void stubSnapshotArtifactLookup(CmsPublishSnapshot snapshot) {
    when(snapshotRepository.findByProjectIdAndSnapshotVersion(
            snapshot.getProject().getId(), snapshot.getSnapshotVersion()))
        .thenReturn(Optional.of(snapshot));
  }

  private void stubSnapshotHistory(CmsContentProject project, int snapshotVersion) {
    project.setLastPublishedSnapshotVersion(snapshotVersion);
    when(snapshotRepository.findMaxSnapshotVersionByProjectId(project.getId()))
        .thenReturn(snapshotVersion);
    when(snapshotRepository.countByProjectId(project.getId())).thenReturn((long) snapshotVersion);
    when(snapshotSealRepository.countBySnapshotProjectId(project.getId()))
        .thenReturn((long) snapshotVersion);
  }

  private void updateSnapshotArtifact(CmsPublishSnapshot snapshot, String artifactJson) {
    snapshot.setArtifactJson(artifactJson);
    snapshot.setArtifactSha256(DigestUtils.sha256Hex(artifactJson));
    snapshot.setArtifactByteSize((long) artifactJson.getBytes(StandardCharsets.UTF_8).length);
  }

  private void resignSnapshot(CmsPublishSnapshot snapshot) {
    snapshotSigningService.sign(snapshot);
  }

  private String publishRequestAuthoringSha256(CmsFixture fixture) {
    List<CmsContentType> contentTypes =
        contentTypeRepository.findByProjectIdOrderByNameAscIdAsc(fixture.project.getId());
    List<CmsContentTypeField> fields =
        fieldRepository.findByContentTypeIdInOrderBySortOrderAscFieldKeyAscIdAsc(
            contentTypes.stream().map(CmsContentType::getId).toList());
    Map<Long, List<CmsContentTypeField>> fieldsByContentTypeId = new LinkedHashMap<>();
    for (CmsContentTypeField field : fields) {
      fieldsByContentTypeId
          .computeIfAbsent(field.getContentType().getId(), ignored -> new ArrayList<>())
          .add(field);
    }
    List<CmsContentEntry> entries =
        entryRepository.findByProjectIdOrderByNameAscIdAsc(fixture.project.getId());
    List<CmsContentEntryVariant> variants =
        variantRepository.findByEntryIdInOrderBySortOrderAscVariantKeyAscIdAsc(
            entries.stream().map(CmsContentEntry::getId).toList());
    Map<Long, List<CmsContentEntryVariant>> variantsByEntryId = new LinkedHashMap<>();
    for (CmsContentEntryVariant variant : variants) {
      variantsByEntryId
          .computeIfAbsent(variant.getEntry().getId(), ignored -> new ArrayList<>())
          .add(variant);
    }
    List<CmsContentFieldMapping> mappings =
        mappingRepository.findMappingsByProjectId(fixture.project.getId());
    Map<Long, List<CmsContentFieldMapping>> mappingsByVariantId = new LinkedHashMap<>();
    for (CmsContentFieldMapping mapping : mappings) {
      mappingsByVariantId
          .computeIfAbsent(mapping.getVariant().getId(), ignored -> new ArrayList<>())
          .add(mapping);
    }
    Map<String, Object> authoringState = new LinkedHashMap<>();
    authoringState.put(
        "project", List.of(fixture.project.getId(), fixture.project.getEntityVersion()));
    authoringState.put(
        "contentTypes",
        contentTypes.stream()
            .map(
                contentType ->
                    List.of(
                        contentType.getId(),
                        contentType.getEntityVersion(),
                        fieldsByContentTypeId.getOrDefault(contentType.getId(), List.of()).stream()
                            .map(field -> List.of(field.getId(), field.getEntityVersion()))
                            .toList()))
            .toList());
    authoringState.put(
        "entries",
        entries.stream()
            .map(
                entry ->
                    List.of(
                        entry.getId(),
                        entry.getEntityVersion(),
                        variantsByEntryId.getOrDefault(entry.getId(), List.of()).stream()
                            .map(
                                variant ->
                                    List.of(
                                        variant.getId(),
                                        variant.getEntityVersion(),
                                        mappingsByVariantId
                                            .getOrDefault(variant.getId(), List.of())
                                            .stream()
                                            .map(this::fieldMappingAuthoringState)
                                            .toList()))
                            .toList()))
            .toList());
    return DigestUtils.sha256Hex(objectMapper.writeValueAsStringUnchecked(authoringState));
  }

  private List<Object> fieldMappingAuthoringState(CmsContentFieldMapping mapping) {
    List<Object> state = new ArrayList<>();
    state.add(mapping.getId());
    state.add(mapping.getEntityVersion());
    state.add(mapping.getTmTextUnit().getId());
    state.add(mapping.getTmTextUnit().getName());
    state.add(mapping.getTmTextUnit().getContent());
    state.add(mapping.getTmTextUnit().getComment());
    return state;
  }

  private CmsContentService.PublishCommand publishCommand(
      CmsFixture fixture, List<String> localeTags) {
    return new CmsContentService.PublishCommand(
        localeTags, publishRequestAuthoringSha256(fixture), VALID_PACKAGE_SHA256);
  }

  private CmsContentService.PublishSnapshotView publishProject(
      Long projectId, CmsContentService.PublishCommand command) {
    return publishProject(projectId, command, "test-publish-" + projectId);
  }

  private CmsContentService.PublishSnapshotView publishProject(
      Long projectId, CmsContentService.PublishCommand command, String publishRequestKey) {
    return service.publishProject(projectId, command, publishRequestKey);
  }

  private CmsContentService.PublishCommand validatedPublishCommand(
      CmsFixture fixture, List<String> localeTags) {
    CmsContentService.ProjectCompletenessView validation =
        service.getProjectCompleteness(fixture.project.getId(), localeTags);
    return new CmsContentService.PublishCommand(
        localeTags, validation.authoringSha256(), validation.publishPackageSha256());
  }

  private CmsContentConfigurationProperties contentCmsConfigurationProperties() {
    CmsContentConfigurationProperties configurationProperties =
        new CmsContentConfigurationProperties();
    configurationProperties.setSnapshotSigningKeyId("test-v1");
    configurationProperties.setSnapshotSigningKeys(
        new LinkedHashMap<>(
            java.util.Map.of("test-v1", "test-content-cms-snapshot-signing-key-0001")));
    return configurationProperties;
  }

  private String validArtifactJson(String projectKey, List<String> localeTags) {
    return validArtifactJson(projectKey, localeTags, false);
  }

  private String validArtifactJsonWithOptionalField(String projectKey, List<String> localeTags) {
    return validArtifactJson(projectKey, localeTags, true);
  }

  private String validArtifactJson(
      String projectKey, List<String> localeTags, boolean includeOptionalField) {
    String localeJson =
        String.join(
            ",", localeTags.stream().map(localeTag -> "\"%s\"".formatted(localeTag)).toList());
    String valueJson =
        String.join(
            ",",
            localeTags.stream().map(localeTag -> "\"%s\":\"Hello\"".formatted(localeTag)).toList());
    String contentTypeFields =
        includeOptionalField
            ? "{\"key\":\"header\",\"name\":\"Header\",\"type\":\"TEXT\",\"localizable\":true,\"required\":true},{\"key\":\"subheader\",\"name\":\"Subheader\",\"type\":\"TEXT\",\"localizable\":true,\"required\":false}"
            : "{\"key\":\"header\",\"name\":\"Header\",\"type\":\"TEXT\",\"localizable\":true,\"required\":true}";
    String artifactFields =
        includeOptionalField
            ? "\"header\":{\"stringId\":\"cms.%s.welcome.control.header\",\"source\":\"Hello\",\"values\":{%s}},\"subheader\":{\"stringId\":\"cms.%s.welcome.control.subheader\",\"source\":\"Hello\",\"values\":{%s}}"
                .formatted(projectKey, valueJson, projectKey, valueJson)
            : "\"header\":{\"stringId\":\"cms.%s.welcome.control.header\",\"source\":\"Hello\",\"values\":{%s}}"
                .formatted(projectKey, valueJson);
    int fieldCount = includeOptionalField ? 2 : 1;
    return "{\"formatVersion\":\"mojito.microCms.v1\",\"snapshotVersion\":2,\"generatedAt\":\"2026-01-01T00:00:00Z\",\"delivery\":{\"runtimeDependency\":\"none\",\"projectHint\":\"BLOB_CDN\",\"supportedTargets\":[\"statsig-dynamic-config\",\"blob-cdn\",\"experience-framework\"]},\"project\":{\"key\":\"%s\",\"name\":\"Growth email\",\"sourceLocale\":\"en\"},\"locales\":[%s],\"contentTypes\":[{\"key\":\"email\",\"name\":\"Email\",\"schemaVersion\":1,\"metadataSchema\":{},\"fields\":[%s]}],\"entries\":[{\"key\":\"welcome\",\"name\":\"Welcome\",\"type\":\"email\",\"status\":\"READY\",\"metadata\":{},\"variants\":[{\"key\":\"control\",\"name\":\"Control\",\"status\":\"CONTROL\",\"candidateGroupKey\":null,\"metadata\":{},\"fields\":{%s}}]}],\"completeness\":%s}"
        .formatted(
            projectKey,
            localeJson,
            contentTypeFields,
            artifactFields,
            validCompletenessJson(localeTags, fieldCount));
  }

  private String validCompletenessJson(List<String> localeTags, int fieldCount) {
    return "["
        + String.join(
            ",",
            localeTags.stream()
                .map(
                    localeTag ->
                        "{\"localeTag\":\"%s\",\"totalFields\":%d,\"approvedFields\":%d,\"missingFields\":0,\"reviewNeededFields\":0,\"translationNeededFields\":0,\"complete\":true}"
                            .formatted(localeTag, fieldCount, fieldCount))
                .toList())
        + "]";
  }

  private static class CmsFixture {
    private final CmsContentProject project;
    private final CmsContentType contentType;
    private final CmsContentTypeField field;
    private final CmsContentEntry entry;
    private final CmsContentEntryVariant variant;
    private final CmsContentFieldMapping mapping;
    private final TMTextUnit tmTextUnit;
    private CmsPublishSnapshot savedSnapshot;

    private CmsFixture(
        CmsContentProject project,
        CmsContentType contentType,
        CmsContentTypeField field,
        CmsContentEntry entry,
        CmsContentEntryVariant variant,
        CmsContentFieldMapping mapping,
        TMTextUnit tmTextUnit) {
      this.project = project;
      this.contentType = contentType;
      this.field = field;
      this.entry = entry;
      this.variant = variant;
      this.mapping = mapping;
      this.tmTextUnit = tmTextUnit;
    }
  }
}
