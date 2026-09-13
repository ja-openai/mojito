package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Strict, unwired readers for the proposed v1 direct-localization request identity.
 *
 * <p>Neither reader authorizes a request. Callers must supply trusted scope and enforce byte limits
 * while reading the transport/storage, before allocating the input. No legacy endpoint or worker
 * uses these readers. The stored identity is not a resolved execution envelope.
 */
public final class AssetLocalizeAdmissionRequestDecoder {

  private final int maxRequestBytes;
  private final JsonFactory jsonFactory;

  /**
   * An explicit positive input budget is required; this does not select an API/storage size policy.
   */
  public AssetLocalizeAdmissionRequestDecoder(int maxRequestBytes) {
    if (maxRequestBytes <= 0) {
      throw new IllegalArgumentException("maxRequestBytes must be positive");
    }
    this.maxRequestBytes = maxRequestBytes;
    this.jsonFactory =
        new JsonFactoryBuilder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxDocumentLength(maxRequestBytes)
                    .maxStringLength(maxRequestBytes)
                    .maxNameLength(32)
                    .maxNumberLength(20)
                    .maxNestingDepth(2)
                    .build())
            .build();
  }

  public AssetLocalizeAdmissionRequest decode(
      long repositoryId, long actorUserId, long pathAssetId, String requestKey, byte[] json) {
    return decode(repositoryId, actorUserId, pathAssetId, requestKey, json, false);
  }

  /**
   * Restore an exact canonical request snapshot using scope and hash from a trusted durable row.
   * This is not authentication: the key is not embedded in the bytes, and a supplied hash alone
   * cannot prove that storage belongs to an accepted request. Callers must resolve that binding.
   */
  public AssetLocalizeAdmissionRequest restore(
      long repositoryId,
      long actorUserId,
      long pathAssetId,
      String requestKey,
      String requestSha256,
      byte[] canonicalJson) {
    try {
      if (canonicalJson == null
          || canonicalJson.length == 0
          || canonicalJson.length > maxRequestBytes
          || requestSha256 == null
          || !requestSha256.matches("[0-9a-f]{64}")) {
        throw invalidRequest();
      }
      // Hash, parse and compare the same snapshot, even if the caller reuses its byte array.
      byte[] snapshot = canonicalJson.clone();
      if (!DigestUtils.sha256Hex(snapshot).equals(requestSha256)) {
        throw invalidRequest();
      }
      AssetLocalizeAdmissionRequest request =
          decode(repositoryId, actorUserId, pathAssetId, requestKey, snapshot, true);
      if (!Arrays.equals(snapshot, request.canonicalBytes())) {
        throw invalidRequest();
      }
      return request;
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException("Invalid stored v1 asset localization request");
    }
  }

  private AssetLocalizeAdmissionRequest decode(
      long repositoryId,
      long actorUserId,
      long pathAssetId,
      String requestKey,
      byte[] json,
      boolean stored) {
    if (json == null || json.length == 0 || json.length > maxRequestBytes) {
      throw invalidRequest();
    }
    try {
      // Validate UTF-8 before JSON parsing; do not autodetect UTF-16/32.
      String bodyJson =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(json))
              .toString();
      if (bodyJson.startsWith("\ufeff")) {
        throw invalidRequest();
      }
      try (JsonParser parser = jsonFactory.createParser(bodyJson)) {
        requireToken(parser.nextToken(), JsonToken.START_OBJECT);
        LocalizedAssetBody body = new LocalizedAssetBody();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          requireToken(parser.currentToken(), JsonToken.FIELD_NAME);
          String field = parser.currentName();
          if (parser.nextToken() == null) {
            throw invalidRequest();
          }
          switch (field) {
            case "repositoryId" -> {
              if (!stored || !Objects.equals(readId(parser), repositoryId)) {
                throw invalidRequest();
              }
            }
            case "actorUserId" -> {
              if (!stored || !Objects.equals(readId(parser), actorUserId)) {
                throw invalidRequest();
              }
            }
            case "protocolVersion" -> {
              if (!stored
                  || !Objects.equals(
                      readId(parser), (long) AssetLocalizeAdmissionRequest.PROTOCOL_VERSION)) {
                throw invalidRequest();
              }
            }
            case "operation" -> {
              if (!stored || !AssetLocalizeAdmissionRequest.OPERATION.equals(readString(parser))) {
                throw invalidRequest();
              }
            }
            case "assetId" -> body.setAssetId(readId(parser));
            case "localeId" -> body.setLocaleId(readId(parser));
            case "content" -> body.setContent(readString(parser));
            case "outputBcp47tag" -> body.setOutputBcp47tag(readString(parser));
            case "pullRunName" -> body.setPullRunName(readString(parser));
            case "filterConfigIdOverride" ->
                body.setFilterConfigIdOverride(
                    readEnum(parser, FilterConfigIdOverride.class, true));
            case "inheritanceMode" ->
                body.setInheritanceMode(readEnum(parser, InheritanceMode.class, false));
            case "status" -> body.setStatus(readEnum(parser, Status.class, false));
            case "filterOptions" -> body.setFilterOptions(readStrings(parser, false));
            case "pullWithNoSourceBranches" ->
                body.setPullWithNoSourceBranches(readStrings(parser, true));
            case "pullWithNoSource" -> {
              if (!parser.hasToken(JsonToken.VALUE_TRUE)
                  && !parser.hasToken(JsonToken.VALUE_FALSE)) {
                throw invalidRequest();
              }
              body.setPullWithNoSource(parser.getBooleanValue());
            }
            default -> throw invalidRequest();
          }
        }
        if (parser.nextToken() != null) {
          throw invalidRequest();
        }
        return AssetLocalizeAdmissionRequest.capture(
            repositoryId, actorUserId, pathAssetId, requestKey, body);
      }
    } catch (IOException | IllegalArgumentException failure) {
      // Parser messages/causes can contain supplied field names, values or source excerpts.
      throw invalidRequest();
    }
  }

  private static Long readId(JsonParser parser) throws IOException {
    if (parser.hasToken(JsonToken.VALUE_NULL)) {
      return null;
    }
    requireToken(parser.currentToken(), JsonToken.VALUE_NUMBER_INT);
    return parser.getLongValue();
  }

  private static String readString(JsonParser parser) throws IOException {
    if (parser.hasToken(JsonToken.VALUE_NULL)) {
      return null;
    }
    requireToken(parser.currentToken(), JsonToken.VALUE_STRING);
    return parser.getText();
  }

  private static <E extends Enum<E>> E readEnum(JsonParser parser, Class<E> type, boolean nullable)
      throws IOException {
    String value = readString(parser);
    if (value == null) {
      if (nullable) {
        return null;
      }
      throw invalidRequest();
    }
    return Enum.valueOf(type, value);
  }

  private static List<String> readStrings(JsonParser parser, boolean allowNullEntries)
      throws IOException {
    if (parser.hasToken(JsonToken.VALUE_NULL)) {
      return null;
    }
    requireToken(parser.currentToken(), JsonToken.START_ARRAY);
    List<String> values = new ArrayList<>();
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      String value = readString(parser);
      if (value == null && !allowNullEntries) {
        throw invalidRequest();
      }
      values.add(value);
    }
    return values;
  }

  private static void requireToken(JsonToken actual, JsonToken expected) {
    if (actual != expected) {
      throw invalidRequest();
    }
  }

  private static IllegalArgumentException invalidRequest() {
    return new IllegalArgumentException("Invalid v1 asset localization request");
  }
}
