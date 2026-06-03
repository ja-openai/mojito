package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.NormalizationUtils;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Immutable direct-localization request identity for the proposed v1 admission protocol.
 *
 * <p>Not a submission API or proof of acceptance. Callers must authorize the supplied scope and
 * validate raw JSON before DTO binding (unknown/duplicate fields and explicit null booleans cannot
 * be detected here). This model is not wired into legacy producers or consumers.
 */
public final class AssetLocalizeAdmissionRequest {

  public static final int PROTOCOL_VERSION = 1;
  public static final String OPERATION = "assetlocalize.direct";

  private static final JsonFactory CANONICAL_JSON =
      new JsonFactoryBuilder()
          .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
          .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
          .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
          .characterEscapes(new CanonicalEscapes())
          .build();

  private final long repositoryId;
  private final long actorUserId;
  private final String requestKey;
  private final Input input;
  private final byte[] canonicalBytes;
  private final String requestSha256;

  private AssetLocalizeAdmissionRequest(
      long repositoryId, long actorUserId, String requestKey, Input input) {
    this.repositoryId = repositoryId;
    this.actorUserId = actorUserId;
    this.requestKey = requestKey;
    this.input = input;
    this.canonicalBytes = encode();
    this.requestSha256 = DigestUtils.sha256Hex(canonicalBytes);
  }

  public static AssetLocalizeAdmissionRequest capture(
      long repositoryId,
      long actorUserId,
      long pathAssetId,
      String requestKey,
      LocalizedAssetBody body) {
    requirePositive(repositoryId, "repositoryId");
    requirePositive(actorUserId, "actorUserId");
    requirePositive(pathAssetId, "pathAssetId");
    validateKey(requestKey);
    if (body == null) {
      throw new IllegalArgumentException("body is required");
    }
    if (body.getAssetId() != null && body.getAssetId() != pathAssetId) {
      throw new IllegalArgumentException("body assetId must match pathAssetId");
    }
    requirePositive(body.getLocaleId(), "localeId");
    if (body.getBcp47Tag() != null) {
      throw new IllegalArgumentException("bcp47Tag is a response-only field");
    }
    if (body.getInheritanceMode() == null || body.getStatus() == null) {
      throw new IllegalArgumentException("inheritanceMode and status are required");
    }
    List<String> branches = body.getPullWithNoSourceBranches();
    Input input =
        new Input(
            pathAssetId,
            body.getLocaleId(),
            NormalizationUtils.normalize(validateString(body.getContent(), "content")),
            validateString(body.getOutputBcp47tag(), "outputBcp47tag"),
            body.getFilterConfigIdOverride(),
            copyStrings(body.getFilterOptions(), "filterOptions", false),
            validateString(body.getPullRunName(), "pullRunName"),
            body.getInheritanceMode(),
            body.getStatus(),
            body.isPullWithNoSource(),
            // A null entry selects the unnamed branch; a null list canonicalizes to empty.
            copyStrings(branches == null ? List.of() : branches, "pullWithNoSourceBranches", true));
    return new AssetLocalizeAdmissionRequest(repositoryId, actorUserId, requestKey, input);
  }

  public long repositoryId() {
    return repositoryId;
  }

  public long actorUserId() {
    return actorUserId;
  }

  public String requestKey() {
    return requestKey;
  }

  public String requestSha256() {
    return requestSha256;
  }

  /** Canonical v1 envelope, not the legacy PollableTask input format. */
  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  /** Returns independent execution input; generation may mutate this DTO. */
  public LocalizedAssetBody toLocalizedAssetBody() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setAssetId(input.assetId());
    body.setLocaleId(input.localeId());
    body.setContent(input.content());
    body.setOutputBcp47tag(input.outputBcp47tag());
    body.setFilterConfigIdOverride(input.filterConfigIdOverride());
    body.setFilterOptions(
        input.filterOptions() == null ? null : new ArrayList<>(input.filterOptions()));
    body.setPullRunName(input.pullRunName());
    body.setInheritanceMode(input.inheritanceMode());
    body.setStatus(input.status());
    body.setPullWithNoSource(input.pullWithNoSource());
    body.setPullWithNoSourceBranches(input.pullWithNoSourceBranches());
    return body;
  }

  @Override
  public String toString() {
    return "AssetLocalizeAdmissionRequest[protocolVersion="
        + PROTOCOL_VERSION
        + ", operation="
        + OPERATION
        + ", repositoryId="
        + repositoryId
        + ", actorUserId="
        + actorUserId
        + "]";
  }

  private byte[] encode() {
    StringWriter output = new StringWriter();
    try (JsonGenerator json = CANONICAL_JSON.createGenerator(output)) {
      // Fixed alphabetical order is part of v1. Never serialize the mutable API DTO here.
      json.writeStartObject();
      json.writeNumberField("actorUserId", actorUserId);
      json.writeNumberField("assetId", input.assetId());
      json.writeStringField("content", input.content());
      json.writeStringField(
          "filterConfigIdOverride",
          input.filterConfigIdOverride() == null ? null : input.filterConfigIdOverride().name());
      writeStrings(json, "filterOptions", input.filterOptions());
      json.writeStringField("inheritanceMode", input.inheritanceMode().name());
      json.writeNumberField("localeId", input.localeId());
      json.writeStringField("operation", OPERATION);
      json.writeStringField("outputBcp47tag", input.outputBcp47tag());
      json.writeNumberField("protocolVersion", PROTOCOL_VERSION);
      json.writeStringField("pullRunName", input.pullRunName());
      json.writeBooleanField("pullWithNoSource", input.pullWithNoSource());
      writeStrings(json, "pullWithNoSourceBranches", input.pullWithNoSourceBranches());
      json.writeNumberField("repositoryId", repositoryId);
      json.writeStringField("status", input.status().name());
      json.writeEndObject();
    } catch (IOException failure) {
      throw new UncheckedIOException("Cannot encode asset localization admission input", failure);
    }
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void writeStrings(JsonGenerator json, String name, List<String> values)
      throws IOException {
    json.writeFieldName(name);
    if (values == null) {
      json.writeNull();
    } else {
      json.writeStartArray();
      for (String value : values) {
        json.writeString(value);
      }
      json.writeEndArray();
    }
  }

  private static void requirePositive(Long value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void validateKey(String key) {
    if (key == null || key.isEmpty() || key.length() > 128) {
      throw new IllegalArgumentException("requestKey must contain 1-128 printable ASCII bytes");
    }
    for (int i = 0; i < key.length(); i++) {
      if (key.charAt(i) < '!' || key.charAt(i) > '~') {
        throw new IllegalArgumentException("requestKey must contain only ASCII ! through ~");
      }
    }
  }

  private static List<String> copyStrings(
      List<String> values, String name, boolean allowNullEntries) {
    if (values == null) {
      return null;
    }
    List<String> copy = new ArrayList<>(values);
    for (String value : copy) {
      if (value == null && !allowNullEntries) {
        throw new IllegalArgumentException(name + " must not contain null entries");
      }
      validateString(value, name);
    }
    return Collections.unmodifiableList(copy);
  }

  private static String validateString(String value, String name) {
    if (value != null) {
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (Character.isHighSurrogate(c)) {
          if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) {
            throw new IllegalArgumentException(name + " contains an unpaired surrogate");
          }
        } else if (Character.isLowSurrogate(c)) {
          throw new IllegalArgumentException(name + " contains an unpaired surrogate");
        }
      }
    }
    return value;
  }

  private record Input(
      long assetId,
      long localeId,
      String content,
      String outputBcp47tag,
      FilterConfigIdOverride filterConfigIdOverride,
      List<String> filterOptions,
      String pullRunName,
      InheritanceMode inheritanceMode,
      Status status,
      boolean pullWithNoSource,
      List<String> pullWithNoSourceBranches) {}

  private static final class CanonicalEscapes extends CharacterEscapes {
    private final int[] escapes = standardAsciiEscapesForJSON();
    private final SerializableString[] controls = new SerializableString[32];

    private CanonicalEscapes() {
      for (int i = 0; i < controls.length; i++) {
        escapes[i] = ESCAPE_CUSTOM;
        controls[i] =
            new SerializedString(
                "\\u00" + Character.forDigit(i >> 4, 16) + Character.forDigit(i & 15, 16));
      }
    }

    @Override
    public int[] getEscapeCodesForAscii() {
      return escapes;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
      return ch < controls.length ? controls[ch] : null;
    }
  }
}
