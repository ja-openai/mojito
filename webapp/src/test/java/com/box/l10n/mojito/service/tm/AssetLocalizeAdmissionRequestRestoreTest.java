package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

/** Persisted identity reader contracts, not blob, SQL admission or execution-envelope tests. */
public class AssetLocalizeAdmissionRequestRestoreTest {

  private static final String KEY = "Private-Request-Key-DoNotExpose";
  private static final String GOLDEN =
      "{\"actorUserId\":23,\"assetId\":31,\"content\":\"source=Hello\","
          + "\"filterConfigIdOverride\":\"PROPERTIES_JAVA\","
          + "\"filterOptions\":[\"mode=strict\",\" Keep Case \",\"mode=strict\"],"
          + "\"inheritanceMode\":\"REMOVE_UNTRANSLATED\",\"localeId\":47,"
          + "\"operation\":\"assetlocalize.direct\",\"outputBcp47tag\":\"fr-CA\","
          + "\"protocolVersion\":1,\"pullRunName\":\"Release-1\",\"pullWithNoSource\":true,"
          + "\"pullWithNoSourceBranches\":[\"main\",\" release \",\"main\"],"
          + "\"repositoryId\":17,\"status\":\"ACCEPTED\"}";
  // The independently calculated capture golden also pins the stored-reader contract.
  private static final String GOLDEN_SHA =
      "7f462922d145a9a1d6a2465782fb742bfe7b238dd849dc8032e395aa084b935a";

  private final AssetLocalizeAdmissionRequestDecoder decoder =
      new AssetLocalizeAdmissionRequestDecoder(16_384);

  @Test
  public void restoresTheExistingGoldenWithEveryExecutionOption() {
    AssetLocalizeAdmissionRequest restored = restore(bytes(GOLDEN), GOLDEN_SHA);

    assertArrayEquals(bytes(GOLDEN), restored.canonicalBytes());
    assertEquals(GOLDEN_SHA, restored.requestSha256());
    assertEquals(17, restored.repositoryId());
    assertEquals(23, restored.actorUserId());
    assertEquals(KEY, restored.requestKey());
    LocalizedAssetBody body = restored.toLocalizedAssetBody();
    assertEquals(Long.valueOf(31), body.getAssetId());
    assertEquals(Long.valueOf(47), body.getLocaleId());
    assertEquals("source=Hello", body.getContent());
    assertEquals("fr-CA", body.getOutputBcp47tag());
    assertEquals("Release-1", body.getPullRunName());
    assertEquals(List.of("mode=strict", " Keep Case ", "mode=strict"), body.getFilterOptions());
    assertEquals(List.of("main", " release ", "main"), body.getPullWithNoSourceBranches());
    assertEquals("PROPERTIES_JAVA", body.getFilterConfigIdOverride().name());
    assertEquals("REMOVE_UNTRANSLATED", body.getInheritanceMode().name());
    assertEquals("ACCEPTED", body.getStatus().name());
    assertEquals(true, body.isPullWithNoSource());
  }

  @Test
  public void preservesDefaultsOptionalNullsUnicodeAndUnnamedBranches() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setLocaleId(47L);
    assertRoundTrip(body);
    body.setContent("Cafe\u0301 \ud83d\ude00 \ufeff\ufffd \"\\/\n\t\u0000");
    body.setOutputBcp47tag("e\u0301");
    body.setFilterOptions(List.of(" X ", "x", " X ", ""));
    body.setPullWithNoSourceBranches(Arrays.asList(null, "main", null, "null", ""));
    AssetLocalizeAdmissionRequest restored = assertRoundTrip(body);
    assertEquals(
        "Caf\u00e9 \ud83d\ude00 \ufeff\ufffd \"\\/\n\t\u0000",
        restored.toLocalizedAssetBody().getContent());
    assertEquals(
        body.getPullWithNoSourceBranches(),
        restored.toLocalizedAssetBody().getPullWithNoSourceBranches());
    assertFalse(restored.toLocalizedAssetBody().isPullWithNoSource());
  }

  @Test
  public void requiresTheExactPersistedHashRatherThanRehashingChangedInput() {
    for (String changed :
        List.of(
            GOLDEN.replace("source=Hello", "source=Other"),
            GOLDEN.replace("\"localeId\":47", "\"localeId\":49"))) {
      reject(() -> restore(bytes(changed), GOLDEN_SHA));
    }
    reject(() -> restore(bytes(GOLDEN), "0".repeat(64)));
  }

  @Test
  public void rejectsInvalidDigestRepresentationAndMissingBytes() {
    for (String hash :
        Arrays.asList(
            null,
            "",
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
            GOLDEN_SHA.toUpperCase(Locale.ROOT),
            " " + GOLDEN_SHA)) {
      reject(() -> restore(bytes(GOLDEN), hash));
    }
    reject(() -> restore(null, GOLDEN_SHA));
    reject(() -> restore(new byte[0], DigestUtils.sha256Hex(new byte[0])));
  }

  @Test
  public void verifiesAllSuppliedTrustedScopeAndKeyPreconditions() {
    reject(() -> decoder.restore(18, 23, 31, KEY, GOLDEN_SHA, bytes(GOLDEN)));
    reject(() -> decoder.restore(17, 24, 31, KEY, GOLDEN_SHA, bytes(GOLDEN)));
    reject(() -> decoder.restore(17, 23, 32, KEY, GOLDEN_SHA, bytes(GOLDEN)));
    reject(() -> decoder.restore(0, 23, 31, KEY, GOLDEN_SHA, bytes(GOLDEN)));
    reject(() -> decoder.restore(17, 0, 31, KEY, GOLDEN_SHA, bytes(GOLDEN)));
    reject(() -> decoder.restore(17, 23, 0, KEY, GOLDEN_SHA, bytes(GOLDEN)));
    for (String key : Arrays.asList(null, "", "key with spaces", "\u00e9", "k".repeat(129))) {
      reject(() -> decoder.restore(17, 23, 31, key, GOLDEN_SHA, bytes(GOLDEN)));
    }
  }

  @Test
  public void doesNotPretendThatAHashAuthenticatesTheExternalRequestKey() {
    AssetLocalizeAdmissionRequest otherKey =
        decoder.restore(17, 23, 31, "another-key", GOLDEN_SHA, bytes(GOLDEN));
    assertEquals("another-key", otherKey.requestKey());
    assertArrayEquals(bytes(GOLDEN), otherKey.canonicalBytes());
    assertEquals(GOLDEN_SHA, otherKey.requestSha256());
  }

  @Test
  public void rejectsUnsupportedVersionOperationAndCoercedMetadataEvenWithMatchingHashes() {
    for (String value :
        List.of("0", "2", "null", "true", "1.0", "1e0", "\"1\"", "9223372036854775808")) {
      rejectRehashed(GOLDEN.replace("\"protocolVersion\":1", "\"protocolVersion\":" + value));
    }
    for (String value :
        List.of("null", "true", "1", "\"assetlocalize.parallel\"", "\"ASSETLOCALIZE.DIRECT\"")) {
      rejectRehashed(
          GOLDEN.replace("\"operation\":\"assetlocalize.direct\"", "\"operation\":" + value));
    }
    rejectRehashed(GOLDEN.replace("\"actorUserId\":23", "\"actorUserId\":\"23\""));
    rejectRehashed(GOLDEN.replace("\"repositoryId\":17", "\"repositoryId\":17.0"));
    rejectRehashed(GOLDEN.replace("\"assetId\":31", "\"assetId\":null"));
  }

  @Test
  public void requiresEveryCanonicalFieldRatherThanFillingMissingStoredDefaults() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode original = (ObjectNode) mapper.readTree(GOLDEN);
    original
        .fieldNames()
        .forEachRemaining(
            field -> {
              ObjectNode missing = original.deepCopy();
              missing.remove(field);
              rejectRehashed(missing.toString());
            });
  }

  @Test
  public void rejectsNoncanonicalRepresentationsEvenWhenTheirOwnHashMatches() {
    for (String json :
        List.of(
            " " + GOLDEN,
            GOLDEN + "\n",
            GOLDEN.replace(
                "\"actorUserId\":23,\"assetId\":31", "\"assetId\":31,\"actorUserId\":23"),
            GOLDEN.replace("source=Hello", "source=\\u0048ello"),
            GOLDEN.replace("\"protocolVersion\":1", "\"protocolVersion\": 1"))) {
      rejectRehashed(json);
    }
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setLocaleId(47L);
    body.setContent("Caf\u00e9\n");
    String canonical =
        new String(
            AssetLocalizeAdmissionRequest.capture(17, 23, 31, KEY, body).canonicalBytes(),
            StandardCharsets.UTF_8);
    rejectRehashed(canonical.replace("Caf\u00e9", "Cafe\u0301"));
    rejectRehashed(canonical.replace("\\u000a", "\\n"));
  }

  @Test
  public void rejectsMalformedUnknownDuplicateAndTrailingDataEvenWithMatchingHashes() {
    for (String json :
        List.of(
            "null",
            "[]",
            "{}",
            GOLDEN.substring(0, GOLDEN.length() - 1),
            GOLDEN + "{}",
            GOLDEN.replace("\"actorUserId\":23", "\"actorUserId\":23,\"actorUserId\":23"),
            GOLDEN.replace("\"actorUserId\":23", "\"actorUserId\":23,\"actorUser\\u0049d\":23"),
            GOLDEN.replace("\"content\":", "\"private-unknown-field\":null,\"content\":"),
            GOLDEN.replace("source=Hello", "\\ud800"))) {
      rejectRehashed(json);
    }
  }

  @Test
  public void rejectsMalformedUtf8AndOtherEncodingsWithoutRepairingBytes() {
    byte[] invalidUtf8 = bytes(GOLDEN);
    invalidUtf8[GOLDEN.indexOf("source=Hello")] = (byte) 0xff;
    for (byte[] json :
        List.of(
            invalidUtf8,
            bytes("\ufeff" + GOLDEN),
            GOLDEN.getBytes(StandardCharsets.UTF_16),
            GOLDEN.getBytes(StandardCharsets.UTF_16LE))) {
      reject(() -> restore(json, DigestUtils.sha256Hex(json)));
    }
  }

  @Test
  public void enforcesTheByteBudgetBeforeParsingAndAcceptsAnExactUnicodeBoundary() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setLocaleId(47L);
    body.setContent("\u96ea\ud83d\ude80");
    AssetLocalizeAdmissionRequest captured =
        AssetLocalizeAdmissionRequest.capture(17, 23, 31, KEY, body);
    byte[] json = captured.canonicalBytes();
    AssetLocalizeAdmissionRequest restored =
        new AssetLocalizeAdmissionRequestDecoder(json.length)
            .restore(17, 23, 31, KEY, captured.requestSha256(), json);
    assertArrayEquals(json, restored.canonicalBytes());
    reject(
        () ->
            new AssetLocalizeAdmissionRequestDecoder(json.length - 1)
                .restore(17, 23, 31, KEY, captured.requestSha256(), json));
  }

  @Test
  public void restoredSnapshotIsIndependentOfStorageBuffersAndExecutionDtos() {
    byte[] storage = bytes(GOLDEN);
    AssetLocalizeAdmissionRequest restored = restore(storage, GOLDEN_SHA);
    Arrays.fill(storage, (byte) 0);
    byte[] returned = restored.canonicalBytes();
    Arrays.fill(returned, (byte) 1);
    LocalizedAssetBody execution = restored.toLocalizedAssetBody();
    execution.setContent("changed");
    execution.getFilterOptions().clear();
    execution.setPullWithNoSourceBranches(List.of("changed"));
    assertArrayEquals(bytes(GOLDEN), restored.canonicalBytes());
    assertEquals(GOLDEN_SHA, restored.requestSha256());
    assertEquals("source=Hello", restored.toLocalizedAssetBody().getContent());
  }

  @Test
  public void addingStoredReaderDoesNotAllowMetadataIntoClientBodyDecoder() {
    rejectClient(bytes(GOLDEN));
    for (String field : List.of("repositoryId", "actorUserId", "protocolVersion", "operation")) {
      rejectClient(bytes("{\"localeId\":47,\"" + field + "\":null}"));
    }
    assertEquals(
        Long.valueOf(47),
        decoder
            .decode(17, 23, 31, KEY, bytes("{\"localeId\":47}"))
            .toLocalizedAssetBody()
            .getLocaleId());
  }

  private AssetLocalizeAdmissionRequest assertRoundTrip(LocalizedAssetBody body) {
    AssetLocalizeAdmissionRequest expected =
        AssetLocalizeAdmissionRequest.capture(17, 23, 31, KEY, body);
    AssetLocalizeAdmissionRequest restored =
        restore(expected.canonicalBytes(), expected.requestSha256());
    assertArrayEquals(expected.canonicalBytes(), restored.canonicalBytes());
    assertEquals(expected.requestSha256(), restored.requestSha256());
    return restored;
  }

  private AssetLocalizeAdmissionRequest restore(byte[] json, String hash) {
    return decoder.restore(17, 23, 31, KEY, hash, json);
  }

  private void rejectRehashed(String json) {
    byte[] input = bytes(json);
    reject(() -> restore(input, DigestUtils.sha256Hex(input)));
  }

  private void rejectClient(byte[] input) {
    try {
      decoder.decode(17, 23, 31, KEY, input);
      fail("Expected client metadata rejection");
    } catch (IllegalArgumentException expected) {
      assertEquals("Invalid v1 asset localization request", expected.getMessage());
      assertNull(expected.getCause());
    }
  }

  private static void reject(Runnable action) {
    try {
      action.run();
      fail("Expected stored identity rejection");
    } catch (IllegalArgumentException expected) {
      assertEquals("Invalid stored v1 asset localization request", expected.getMessage());
      assertNull(expected.getCause());
      assertEquals(0, expected.getSuppressed().length);
    }
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
