package com.box.l10n.mojito.service.cms;

import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;

@Service
public class CmsSnapshotSigningService {

  public static final String SIGNATURE_ALGORITHM = "HmacSHA256";
  public static final String SNAPSHOT_SIGNATURE_VERSION = "mojito.microCms.snapshot-signature.v1";
  public static final String ARTIFACT_SIGNATURE_VERSION = "mojito.microCms.artifact-signature.v1";
  private static final int MIN_SIGNING_KEY_BYTES = 32;
  private static final Pattern SIGNING_KEY_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]*");

  private final CmsContentConfigurationProperties configurationProperties;

  public CmsSnapshotSigningService(CmsContentConfigurationProperties configurationProperties) {
    this.configurationProperties = configurationProperties;
  }

  public void sign(CmsPublishSnapshot snapshot) {
    String signingKeyId = requireActiveSigningKeyId();
    snapshot.setSnapshotSigningKeyId(signingKeyId);
    snapshot.setSnapshotSignature(computeSnapshotSignature(snapshot, signingKeyId));
    snapshot.setArtifactSignature(computeArtifactSignature(snapshot, signingKeyId));
  }

  public void validateActiveConfiguration() {
    requireActiveSigningKeyId();
  }

  public void validate(CmsPublishSnapshot snapshot) {
    String signingKeyId = requireStoredSigningKeyId(snapshot);
    String expectedSignature = computeSnapshotSignature(snapshot, signingKeyId);
    if (!MessageDigest.isEqual(
        decodeSignature(requireStoredSignature(snapshot), snapshot, "Publish snapshot signature"),
        decodeSignature(expectedSignature, snapshot, "Publish snapshot signature"))) {
      throw new IllegalStateException("Publish snapshot signature mismatch: " + snapshot.getId());
    }
    String expectedArtifactSignature = computeArtifactSignature(snapshot, signingKeyId);
    if (!MessageDigest.isEqual(
        decodeSignature(
            requireStoredArtifactSignature(snapshot), snapshot, "Publish artifact signature"),
        decodeSignature(expectedArtifactSignature, snapshot, "Publish artifact signature"))) {
      throw new IllegalStateException("Publish artifact signature mismatch: " + snapshot.getId());
    }
  }

  private String requireActiveSigningKeyId() {
    String signingKeyId = configurationProperties.getSnapshotSigningKeyId();
    if (signingKeyId == null || signingKeyId.isBlank()) {
      throw new IllegalStateException("CMS snapshot active signing key ID is missing");
    }
    requireSigningKey(signingKeyId);
    return signingKeyId;
  }

  private String requireStoredSigningKeyId(CmsPublishSnapshot snapshot) {
    String signingKeyId = snapshot.getSnapshotSigningKeyId();
    if (signingKeyId == null || signingKeyId.isBlank()) {
      throw new IllegalStateException(
          "Publish snapshot signing key ID is missing: " + snapshot.getId());
    }
    requireSigningKey(signingKeyId);
    return signingKeyId;
  }

  private String requireSigningKey(String signingKeyId) {
    if (signingKeyId.length() > CmsPublishSnapshot.SNAPSHOT_SIGNING_KEY_ID_MAX_LENGTH
        || !SIGNING_KEY_ID_PATTERN.matcher(signingKeyId).matches()) {
      throw new IllegalStateException("CMS snapshot signing key ID is invalid: " + signingKeyId);
    }
    String signingKey = configurationProperties.getSnapshotSigningKeys().get(signingKeyId);
    if (signingKey == null || signingKey.isBlank()) {
      throw new IllegalStateException("CMS snapshot signing key is missing: " + signingKeyId);
    }
    if (signingKey.getBytes(StandardCharsets.UTF_8).length < MIN_SIGNING_KEY_BYTES) {
      throw new IllegalStateException("CMS snapshot signing key is too short: " + signingKeyId);
    }
    return signingKey;
  }

  private String requireStoredSignature(CmsPublishSnapshot snapshot) {
    String signature = snapshot.getSnapshotSignature();
    if (signature == null || signature.isBlank()) {
      throw new IllegalStateException("Publish snapshot signature is missing: " + snapshot.getId());
    }
    return signature;
  }

  private String requireStoredArtifactSignature(CmsPublishSnapshot snapshot) {
    String signature = snapshot.getArtifactSignature();
    if (signature == null || signature.isBlank()) {
      throw new IllegalStateException("Publish artifact signature is missing: " + snapshot.getId());
    }
    return signature;
  }

  private String computeSnapshotSignature(CmsPublishSnapshot snapshot, String signingKeyId) {
    return computeSignature(snapshotSignaturePayload(snapshot, signingKeyId), signingKeyId);
  }

  private String computeArtifactSignature(CmsPublishSnapshot snapshot, String signingKeyId) {
    return computeSignature(artifactSignaturePayload(snapshot, signingKeyId), signingKeyId);
  }

  private String computeSignature(String signaturePayload, String signingKeyId) {
    try {
      Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM);
      mac.init(
          new SecretKeySpec(
              requireSigningKey(signingKeyId).getBytes(StandardCharsets.UTF_8),
              SIGNATURE_ALGORITHM));
      return Hex.encodeHexString(mac.doFinal(signaturePayload.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("CMS snapshot signing failed", ex);
    }
  }

  private String snapshotSignaturePayload(CmsPublishSnapshot snapshot, String signingKeyId) {
    StringBuilder payload = new StringBuilder();
    appendPayloadField(payload, "signatureVersion", SNAPSHOT_SIGNATURE_VERSION);
    appendPayloadField(payload, "signingKeyId", signingKeyId);
    appendPayloadField(payload, "projectKey", requireSnapshotProjectKey(snapshot));
    appendPayloadField(payload, "snapshotVersion", requireSnapshotVersion(snapshot));
    appendPayloadField(payload, "publishRequestKey", requireSnapshotPublishRequestKey(snapshot));
    appendPayloadField(
        payload,
        "publishRequestLocaleTags",
        requireSnapshotValue(snapshot.getPublishRequestLocaleTags(), snapshot));
    appendPayloadField(
        payload,
        "publishRequestAuthoringSha256",
        requireSnapshotValue(snapshot.getPublishRequestAuthoringSha256(), snapshot));
    appendPayloadField(
        payload,
        "publishRequestPackageSha256",
        requireSnapshotValue(snapshot.getPublishRequestPackageSha256(), snapshot));
    appendPayloadField(payload, "status", requireSnapshotStatus(snapshot));
    appendPayloadField(payload, "publisherUserId", requireSnapshotPublisherUserId(snapshot));
    appendPayloadField(payload, "publisherUsername", requireSnapshotPublisherUsername(snapshot));
    appendPayloadField(payload, "publishedAt", requireSnapshotPublishedAt(snapshot));
    appendPayloadField(
        payload, "localeTags", requireSnapshotValue(snapshot.getLocaleTags(), snapshot));
    appendPayloadField(
        payload, "artifactSha256", requireSnapshotValue(snapshot.getArtifactSha256(), snapshot));
    appendPayloadField(
        payload,
        "artifactByteSize",
        snapshot.getArtifactByteSize() == null ? null : snapshot.getArtifactByteSize().toString());
    appendPayloadField(
        payload,
        "completenessJson",
        requireSnapshotValue(snapshot.getCompletenessJson(), snapshot));
    appendPayloadField(
        payload, "artifactJson", requireSnapshotValue(snapshot.getArtifactJson(), snapshot));
    return payload.toString();
  }

  private String artifactSignaturePayload(CmsPublishSnapshot snapshot, String signingKeyId) {
    StringBuilder payload = new StringBuilder();
    appendPayloadField(payload, "signatureVersion", ARTIFACT_SIGNATURE_VERSION);
    appendPayloadField(payload, "signingKeyId", signingKeyId);
    appendPayloadField(payload, "projectKey", requireSnapshotProjectKey(snapshot));
    appendPayloadField(payload, "snapshotVersion", requireSnapshotVersion(snapshot));
    appendPayloadField(payload, "status", requireSnapshotStatus(snapshot));
    appendPayloadField(payload, "publishedAt", requireSnapshotPublishedAt(snapshot));
    appendPayloadField(
        payload, "localeTags", requireSnapshotValue(snapshot.getLocaleTags(), snapshot));
    appendPayloadField(
        payload, "artifactSha256", requireSnapshotValue(snapshot.getArtifactSha256(), snapshot));
    appendPayloadField(
        payload,
        "artifactByteSize",
        snapshot.getArtifactByteSize() == null ? null : snapshot.getArtifactByteSize().toString());
    appendPayloadField(
        payload, "artifactJson", requireSnapshotValue(snapshot.getArtifactJson(), snapshot));
    return payload.toString();
  }

  private void appendPayloadField(StringBuilder payload, String fieldName, String fieldValue) {
    if (fieldValue == null) {
      throw new IllegalStateException("CMS snapshot signing payload is missing " + fieldName);
    }
    payload
        .append(fieldName)
        .append(':')
        .append(fieldValue.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(fieldValue)
        .append('\n');
  }

  private String requireSnapshotProjectKey(CmsPublishSnapshot snapshot) {
    if (snapshot.getProject() == null
        || snapshot.getProject().getProjectKey() == null
        || snapshot.getProject().getProjectKey().isBlank()) {
      throw new IllegalStateException("Publish snapshot project is missing: " + snapshot.getId());
    }
    return snapshot.getProject().getProjectKey();
  }

  private String requireSnapshotVersion(CmsPublishSnapshot snapshot) {
    if (snapshot.getSnapshotVersion() == null) {
      throw new IllegalStateException("Publish snapshot version is missing: " + snapshot.getId());
    }
    return snapshot.getSnapshotVersion().toString();
  }

  private String requireSnapshotStatus(CmsPublishSnapshot snapshot) {
    if (snapshot.getStatus() == null) {
      throw new IllegalStateException("Publish snapshot status is missing: " + snapshot.getId());
    }
    return snapshot.getStatus().name();
  }

  private String requireSnapshotPublishRequestKey(CmsPublishSnapshot snapshot) {
    String publishRequestKey = snapshot.getPublishRequestKey();
    if (publishRequestKey == null || publishRequestKey.isBlank()) {
      throw new IllegalStateException(
          "Publish snapshot request key is missing: " + snapshot.getId());
    }
    return publishRequestKey;
  }

  private String requireSnapshotPublisherUsername(CmsPublishSnapshot snapshot) {
    String publisherUsername = snapshot.getCreatedByUsername();
    if (publisherUsername == null || publisherUsername.isBlank()) {
      throw new IllegalStateException("Publish snapshot publisher is missing: " + snapshot.getId());
    }
    return publisherUsername;
  }

  private String requireSnapshotPublisherUserId(CmsPublishSnapshot snapshot) {
    if (snapshot.getCreatedByUser() == null || snapshot.getCreatedByUser().getId() == null) {
      throw new IllegalStateException("Publish snapshot publisher is missing: " + snapshot.getId());
    }
    return snapshot.getCreatedByUser().getId().toString();
  }

  private String requireSnapshotPublishedAt(CmsPublishSnapshot snapshot) {
    String publishedAt = snapshot.getPublishedAt();
    if (publishedAt == null || publishedAt.isBlank()) {
      throw new IllegalStateException(
          "Publish snapshot published timestamp is missing: " + snapshot.getId());
    }
    try {
      if (!Objects.equals(Instant.parse(publishedAt).toString(), publishedAt)) {
        throw new IllegalStateException(
            "Publish snapshot published timestamp is invalid: " + snapshot.getId());
      }
      return publishedAt;
    } catch (DateTimeParseException ex) {
      throw new IllegalStateException(
          "Publish snapshot published timestamp is invalid: " + snapshot.getId(), ex);
    }
  }

  private String requireSnapshotValue(String value, CmsPublishSnapshot snapshot) {
    if (value == null) {
      throw new IllegalStateException(
          "Publish snapshot signing payload is missing: " + snapshot.getId());
    }
    return value;
  }

  private byte[] decodeSignature(
      String signature, CmsPublishSnapshot snapshot, String signatureLabel) {
    try {
      byte[] signatureBytes = Hex.decodeHex(signature);
      if (signatureBytes.length != 32
          || !Objects.equals(signature, Hex.encodeHexString(signatureBytes))) {
        throw new IllegalStateException(signatureLabel + " is invalid: " + snapshot.getId());
      }
      return signatureBytes;
    } catch (DecoderException ex) {
      throw new IllegalStateException(signatureLabel + " is invalid: " + snapshot.getId(), ex);
    }
  }
}
