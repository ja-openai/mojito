package com.box.l10n.mojito.service.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.cms.CmsContentProject;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;
import com.box.l10n.mojito.entity.security.user.User;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

public class CmsSnapshotSigningServiceTest {

  @Test
  public void validatesSnapshotSignedWithRetainedVerificationKeyAfterRotation() {
    CmsContentConfigurationProperties configurationProperties = configurationProperties();
    CmsSnapshotSigningService oldSigningService =
        signingService(configurationProperties, "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    oldSigningService.sign(snapshot);
    configurationProperties.setSnapshotSigningKeyId("test-v2");
    CmsSnapshotSigningService rotatedSigningService =
        new CmsSnapshotSigningService(configurationProperties);

    rotatedSigningService.validate(snapshot);

    assertThat(snapshot.getSnapshotSigningKeyId()).isEqualTo("test-v1");
    assertThat(snapshot.getSnapshotSignature()).hasSize(64);
    assertThat(snapshot.getArtifactSignature()).hasSize(64);
  }

  @Test
  public void rejectsSnapshotWhenStoredVerificationKeyIsRemoved() {
    CmsContentConfigurationProperties configurationProperties = configurationProperties();
    CmsSnapshotSigningService signingService = signingService(configurationProperties, "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    configurationProperties.setSnapshotSigningKeys(
        new LinkedHashMap<>(Map.of("test-v2", "test-content-cms-snapshot-signing-key-0002")));

    assertThatThrownBy(() -> signingService.validate(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS snapshot signing key is missing: test-v1");
  }

  @Test
  public void validatesSnapshotAfterLivePublisherUsernameRename() {
    CmsSnapshotSigningService signingService = signingService(configurationProperties(), "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    snapshot.getCreatedByUser().setUsername("renamed-admin");

    signingService.validate(snapshot);

    assertThat(snapshot.getCreatedByUsername()).isEqualTo("admin");
  }

  @Test
  public void rejectsSnapshotWhenSignedPublishedTimestampChanges() {
    CmsSnapshotSigningService signingService = signingService(configurationProperties(), "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    snapshot.setPublishedAt("2026-01-02T00:00:00Z");

    assertThatThrownBy(() -> signingService.validate(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot signature mismatch: 99");
  }

  @Test
  public void rejectsSnapshotWhenSignedPublishRequestKeyChanges() {
    CmsSnapshotSigningService signingService = signingService(configurationProperties(), "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    snapshot.setPublishRequestKey("publish-request-2");

    assertThatThrownBy(() -> signingService.validate(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot signature mismatch: 99");
  }

  @Test
  public void rejectsSnapshotWhenSignedPublishRequestAuthoringSha256Changes() {
    CmsSnapshotSigningService signingService = signingService(configurationProperties(), "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    snapshot.setPublishRequestAuthoringSha256(DigestUtils.sha256Hex("{\"changed\":true}"));

    assertThatThrownBy(() -> signingService.validate(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot signature mismatch: 99");
  }

  @Test
  public void rejectsSnapshotWhenSignedPublishRequestPackageSha256Changes() {
    CmsSnapshotSigningService signingService = signingService(configurationProperties(), "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    snapshot.setPublishRequestPackageSha256(DigestUtils.sha256Hex("{\"changed-package\":true}"));

    assertThatThrownBy(() -> signingService.validate(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish snapshot signature mismatch: 99");
  }

  @Test
  public void rejectsSnapshotWhenStoredArtifactSignatureChanges() {
    CmsSnapshotSigningService signingService = signingService(configurationProperties(), "test-v1");
    CmsPublishSnapshot snapshot = snapshot();
    signingService.sign(snapshot);
    snapshot.setArtifactSignature("bad");

    assertThatThrownBy(() -> signingService.validate(snapshot))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Publish artifact signature is invalid: 99");
  }

  @Test
  public void rejectsPublishSigningWithoutConfiguredActiveKey() {
    CmsContentConfigurationProperties configurationProperties = configurationProperties();
    configurationProperties.setSnapshotSigningKeyId(null);
    CmsSnapshotSigningService signingService =
        new CmsSnapshotSigningService(configurationProperties);

    assertThatThrownBy(() -> signingService.sign(snapshot()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS snapshot active signing key ID is missing");
  }

  private CmsSnapshotSigningService signingService(
      CmsContentConfigurationProperties configurationProperties, String activeKeyId) {
    configurationProperties.setSnapshotSigningKeyId(activeKeyId);
    return new CmsSnapshotSigningService(configurationProperties);
  }

  private CmsContentConfigurationProperties configurationProperties() {
    CmsContentConfigurationProperties configurationProperties =
        new CmsContentConfigurationProperties();
    configurationProperties.setSnapshotSigningKeys(
        new LinkedHashMap<>(
            Map.of(
                "test-v1",
                "test-content-cms-snapshot-signing-key-0001",
                "test-v2",
                "test-content-cms-snapshot-signing-key-0002")));
    return configurationProperties;
  }

  private CmsPublishSnapshot snapshot() {
    String artifactJson = "{\"artifact\":\"json\"}";
    CmsPublishSnapshot snapshot = new CmsPublishSnapshot();
    snapshot.setId(99L);
    CmsContentProject project = new CmsContentProject();
    project.setProjectKey("growth-email");
    snapshot.setProject(project);
    snapshot.setSnapshotVersion(2);
    snapshot.setStatus(CmsPublishSnapshot.Status.PUBLISHED);
    User publisher = new User();
    publisher.setId(100L);
    publisher.setUsername("admin");
    snapshot.setCreatedByUser(publisher);
    snapshot.setCreatedByUsername("admin");
    snapshot.setPublishedAt("2026-01-01T00:00:00Z");
    snapshot.setPublishRequestKey("publish-request-1");
    snapshot.setPublishRequestLocaleTags("");
    snapshot.setPublishRequestAuthoringSha256(DigestUtils.sha256Hex("{}"));
    snapshot.setPublishRequestPackageSha256(DigestUtils.sha256Hex("{\"package\":true}"));
    snapshot.setLocaleTags("en");
    snapshot.setArtifactJson(artifactJson);
    snapshot.setArtifactSha256(DigestUtils.sha256Hex(artifactJson));
    snapshot.setArtifactByteSize((long) artifactJson.getBytes(StandardCharsets.UTF_8).length);
    snapshot.setCompletenessJson("[]");
    return snapshot;
  }
}
