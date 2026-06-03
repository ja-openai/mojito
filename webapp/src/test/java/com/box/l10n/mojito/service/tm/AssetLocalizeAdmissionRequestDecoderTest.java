package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class AssetLocalizeAdmissionRequestDecoderTest {

  private static final String KEY = "Private-Request-Key-DoNotExpose";
  private static final String SECRET = "private-input-sentinel";
  private static final String MINIMAL = "{\"localeId\":47}";
  private static final Map<String, String> FIELD_VALUES =
      Map.ofEntries(
          Map.entry("assetId", "31"),
          Map.entry("localeId", "47"),
          Map.entry("content", "null"),
          Map.entry("outputBcp47tag", "null"),
          Map.entry("filterConfigIdOverride", "\"PROPERTIES_JAVA\""),
          Map.entry("filterOptions", "[]"),
          Map.entry("pullRunName", "null"),
          Map.entry("inheritanceMode", "\"USE_PARENT\""),
          Map.entry("status", "\"ALL\""),
          Map.entry("pullWithNoSource", "false"),
          Map.entry("pullWithNoSourceBranches", "[null]"));
  private static final String FULL_JSON =
      "{\"status\":\"ACCEPTED\",\"pullRunName\":\"Release-1\",\"localeId\":47,"
          + "\"content\":\"source=Hello\",\"assetId\":31,\"outputBcp47tag\":\"fr-CA\","
          + "\"filterConfigIdOverride\":\"PROPERTIES_JAVA\","
          + "\"filterOptions\":[\"mode=strict\",\" Keep Case \",\"mode=strict\"],"
          + "\"inheritanceMode\":\"REMOVE_UNTRANSLATED\",\"pullWithNoSource\":true,"
          + "\"pullWithNoSourceBranches\":[\"main\",\" release \",\"main\"]}";

  private final AssetLocalizeAdmissionRequestDecoder decoder =
      new AssetLocalizeAdmissionRequestDecoder(16_384);

  @Test
  public void requiresExplicitPositiveByteBudget() {
    assertTrue(Modifier.isPublic(AssetLocalizeAdmissionRequestDecoder.class.getModifiers()));
    assertTrue(Modifier.isFinal(AssetLocalizeAdmissionRequestDecoder.class.getModifiers()));
    for (int budget : new int[] {Integer.MIN_VALUE, -1, 0}) {
      reject("budget " + budget, () -> new AssetLocalizeAdmissionRequestDecoder(budget));
    }
    new AssetLocalizeAdmissionRequestDecoder(1);
    new AssetLocalizeAdmissionRequestDecoder(Integer.MAX_VALUE);
  }

  @Test
  public void decodesEveryRequestFieldToTheExistingAsciiCaptureGolden() {
    AssetLocalizeAdmissionRequest request = decode(" \r\n\t" + FULL_JSON + "\t\r\n ");
    assertIdentity(capture(populatedBody()), request);
    assertEquals(
        "7f462922d145a9a1d6a2465782fb742bfe7b238dd849dc8032e395aa084b935a",
        request.requestSha256());
  }

  @Test
  public void acceptsEscapedNamesStringsSupplementaryUnicodeAndContentNfc() {
    LocalizedAssetBody body = minimalBody();
    body.setContent("Caf\u00e9 \ud83d\ude00 / \"\\\b\f\n\r\t\u0000\ufeff");
    body.setOutputBcp47tag("e\u0301");
    String escaped =
        "{\"outputBcp47tag\":\"e\\u0301\",\"loc\\u0061leId\":47,"
            + "\"content\":\"Cafe\\u0301 \\uD83D\\uDE00 \\/ \\\"\\\\"
            + "\\b\\f\\n\\r\\t\\u0000\\uFEFF\"}";
    assertIdentity(capture(body), decode(escaped));
    assertIdentity(
        decode(escaped),
        decode(escaped.replace("Cafe\\u0301 \\uD83D\\uDE00", "Caf\u00e9 \ud83d\ude00")));
    assertEquals("e\u0301", decode(escaped).toLocalizedAssetBody().getOutputBcp47tag());
  }

  @Test
  public void omittedDefaultsAndOptionalNullsMatchCapture() {
    for (String json :
        List.of(
            MINIMAL,
            "{\"localeId\":47,\"assetId\":null,\"content\":null,\"outputBcp47tag\":null,"
                + "\"filterConfigIdOverride\":null,\"filterOptions\":null,\"pullRunName\":null,"
                + "\"pullWithNoSourceBranches\":null}",
            "{\"localeId\":47,\"assetId\":31,\"inheritanceMode\":\"USE_PARENT\","
                + "\"status\":\"ALL\",\"pullWithNoSource\":false,"
                + "\"pullWithNoSourceBranches\":[]}")) {
      assertIdentity(capture(minimalBody()), decode(json));
    }
    for (String field : List.of("content", "outputBcp47tag", "pullRunName", "filterOptions")) {
      String empty = field.equals("filterOptions") ? "[]" : "\"\"";
      assertFalse(
          field,
          decode(object(field, "null"))
              .requestSha256()
              .equals(decode(object(field, empty)).requestSha256()));
    }
  }

  @Test
  public void preservesOrderedOptionsAndUnnamedBranchesWithoutEnablingNoSource() {
    LocalizedAssetBody body = minimalBody();
    body.setFilterOptions(List.of(" X ", "x", " X ", ""));
    body.setPullWithNoSourceBranches(Arrays.asList(null, "main", null, "null", ""));
    AssetLocalizeAdmissionRequest request =
        decode(
            "{\"localeId\":47,\"filterOptions\":[\" X \",\"x\",\" X \",\"\"],"
                + "\"pullWithNoSource\":false,"
                + "\"pullWithNoSourceBranches\":[null,\"main\",null,\"null\",\"\"]}");
    assertIdentity(capture(body), request);
    assertFalse(request.toLocalizedAssetBody().isPullWithNoSource());
    assertEquals(body.getFilterOptions(), request.toLocalizedAssetBody().getFilterOptions());
    assertEquals(
        body.getPullWithNoSourceBranches(),
        request.toLocalizedAssetBody().getPullWithNoSourceBranches());
    Set<String> hashes = new HashSet<>();
    for (String branches :
        List.of("[]", "[null]", "[\"null\"]", "[null,\"main\"]", "[\"main\",null]")) {
      assertTrue(hashes.add(decode(object("pullWithNoSourceBranches", branches)).requestSha256()));
    }
  }

  @Test
  public void acceptsExactEnumNamesForEveryCurrentValue() {
    for (FilterConfigIdOverride value : FilterConfigIdOverride.values()) {
      assertEquals(
          value,
          decode(object("filterConfigIdOverride", "\"" + value.name() + "\""))
              .toLocalizedAssetBody()
              .getFilterConfigIdOverride());
    }
    for (InheritanceMode value : InheritanceMode.values()) {
      assertEquals(
          value,
          decode(object("inheritanceMode", "\"" + value.name() + "\""))
              .toLocalizedAssetBody()
              .getInheritanceMode());
    }
    for (Status value : Status.values()) {
      assertEquals(
          value,
          decode(object("status", "\"" + value.name() + "\"")).toLocalizedAssetBody().getStatus());
    }
  }

  @Test
  public void rejectsDuplicateMembersIncludingEscapedSpellingsAndNullValues() {
    FIELD_VALUES.forEach(
        (field, value) -> {
          String first = object(field, value);
          String escapedField =
              String.format("\\u%04x", (int) field.charAt(0)) + field.substring(1);
          for (String duplicate : List.of(field, escapedField)) {
            rejectJson(
                first.substring(0, first.length() - 1) + ",\"" + duplicate + "\":" + value + "}");
          }
        });
  }

  @Test
  public void rejectsUnknownAndResponseFieldsByPresenceEvenWhenNull() {
    for (String field :
        List.of(
            "bcp47Tag",
            "bcp47tag",
            "outputBcp47Tag",
            "unknown",
            "repositoryId",
            "actorUserId",
            "operation",
            "protocolVersion",
            "requestKey",
            "requestSha256")) {
      for (String value : List.of("null", "\"value\"", "{}")) {
        rejectJson(object(field, value));
      }
    }
  }

  @Test
  public void acceptsOnlyOneStandardJsonObjectWithoutPermissiveSyntax() {
    reject("null bytes", () -> decoder.decode(17, 23, 31, KEY, null));
    for (String json :
        List.of(
            "",
            " \t\r\n",
            "null",
            "[]",
            "47",
            "true",
            "\"text\"",
            "{}",
            MINIMAL + MINIMAL,
            MINIMAL + " false",
            MINIMAL + " garbage",
            "/* comment */" + MINIMAL,
            MINIMAL + " // comment",
            "# comment\n" + MINIMAL,
            "{'localeId':47}",
            "{localeId:47}",
            "{\"localeId\":47,}",
            "{\"localeId\":047}",
            "{\"localeId\":+47}",
            "{\"localeId\":NaN}",
            "{\"localeId\":Infinity}",
            "{\"localeId\":47 \"content\":null}",
            object("filterOptions", "[\"a\",]"),
            object("content", "\"\\x41\""),
            object("content", "\"\\u12G4\""),
            "{\"localeId\":47")) {
      rejectJson(json);
    }
    for (char control = 0; control < 0x20; control++) {
      rejectJson(object("content", "\"raw" + control + "control\""));
    }
  }

  @Test
  public void requiresPositiveLongIntegerTokensWithoutCoercionOrOverflow() {
    for (String field : List.of("assetId", "localeId")) {
      for (String value :
          List.of(
              "0",
              "-1",
              "1.0",
              "1e0",
              "\"1\"",
              "true",
              "false",
              "[]",
              "{}",
              "9223372036854775808",
              "-9223372036854775809")) {
        reject(
            field + "=" + value, () -> decoder.decode(17, 23, 1, KEY, utf8(object(field, value))));
      }
    }
    rejectJson(object("localeId", "null"));
    for (long id : new long[] {1L, 9007199254740993L, Long.MAX_VALUE}) {
      LocalizedAssetBody body = minimalBody();
      body.setAssetId(id);
      body.setLocaleId(id);
      AssetLocalizeAdmissionRequest request =
          decoder.decode(17, 23, id, KEY, utf8("{\"assetId\":" + id + ",\"localeId\":" + id + "}"));
      assertIdentity(AssetLocalizeAdmissionRequest.capture(17, 23, id, KEY, body), request);
      assertEquals(Long.valueOf(id), request.toLocalizedAssetBody().getLocaleId());
    }
  }

  @Test
  public void rejectsWrongStringEnumAndBooleanTokenTypesAndExplicitRequiredNulls() {
    for (String field : List.of("content", "outputBcp47tag", "pullRunName")) {
      for (String value : List.of("1", "true", "[]", "{}")) {
        rejectJson(object(field, value));
      }
    }
    for (String field : List.of("filterConfigIdOverride", "inheritanceMode", "status")) {
      for (String value : List.of("0", "true", "[]", "{}", "\"\"", "\"unknown\"")) {
        rejectJson(object(field, value));
      }
    }
    for (String field : List.of("inheritanceMode", "status", "pullWithNoSource")) {
      rejectJson(object(field, "null"));
    }
    for (String value : List.of("0", "1", "\"false\"", "[]", "{}")) {
      rejectJson(object("pullWithNoSource", value));
    }
    rejectJson(object("status", "\"all\""));
    rejectJson(object("inheritanceMode", "\"use_parent\""));
    rejectJson(object("filterConfigIdOverride", "\"properties_java\""));
    rejectJson(object("filterConfigIdOverride", "\"okf_properties\""));
  }

  @Test
  public void requiresArraysOfStringsWithNullEntriesAllowedOnlyForBranches() {
    for (String field : List.of("filterOptions", "pullWithNoSourceBranches")) {
      for (String value :
          List.of("\"a\"", "1", "true", "{}", "[1]", "[true]", "[{}]", "[[]]", "[\"a\",1]")) {
        rejectJson(object(field, value));
      }
    }
    rejectJson(object("filterOptions", "[null]"));
    rejectJson(object("filterOptions", "[\"a\",null]"));
  }

  @Test
  public void delegatesScopeKeyPathAndEscapedSurrogateValidationToCapture() {
    for (long invalid : new long[] {-1, 0}) {
      reject("repository", () -> decoder.decode(invalid, 23, 31, KEY, utf8(MINIMAL)));
      reject("actor", () -> decoder.decode(17, invalid, 31, KEY, utf8(MINIMAL)));
      reject("path", () -> decoder.decode(17, 23, invalid, KEY, utf8(MINIMAL)));
    }
    for (String key : Arrays.asList(null, "", KEY + " ", "x".repeat(129), "\u00e9")) {
      reject("request key", () -> decoder.decode(17, 23, 31, key, utf8(MINIMAL)));
    }
    rejectJson(object("assetId", "32"));
    for (String field :
        List.of(
            "content",
            "outputBcp47tag",
            "pullRunName",
            "filterOptions",
            "pullWithNoSourceBranches")) {
      for (String escaped : List.of("\\ud800", "\\udc00", "x\\ud800y", "\\udc00\\ud800")) {
        String value = "\"" + escaped + "\"";
        boolean listField =
            field.equals("filterOptions") || field.equals("pullWithNoSourceBranches");
        rejectJson(object(field, listField ? "[\"valid\"," + value + "]" : value));
      }
    }
  }

  @Test
  public void rejectsFramingBomAndNonUtf8DocumentsButAcceptsEmbeddedBom() {
    rejectJson("\ufeff" + MINIMAL);
    rejectJson(" \ufeff" + MINIMAL);
    rejectJson(MINIMAL + "\ufeff");
    for (byte[] json :
        List.of(
            MINIMAL.getBytes(StandardCharsets.UTF_16LE),
            MINIMAL.getBytes(StandardCharsets.UTF_16BE),
            MINIMAL.getBytes(StandardCharsets.UTF_16))) {
      reject("non UTF-8 document", () -> decoder.decode(17, 23, 31, KEY, json));
    }
    assertEquals(
        "\ufeff", decode(object("content", "\"\ufeff\"")).toLocalizedAssetBody().getContent());
  }

  @Test
  public void rejectsMalformedUtf8RatherThanReplacingInvalidSequences() {
    byte[] prefix = utf8("{\"localeId\":47,\"content\":\"");
    byte[] suffix = utf8("\"}");
    for (int[] sequence :
        List.of(
            new int[] {0x80},
            new int[] {0xc0, 0xaf},
            new int[] {0xe0, 0x80, 0xaf},
            new int[] {0xf0, 0x80, 0x80, 0xaf},
            new int[] {0xed, 0xa0, 0x80},
            new int[] {0xed, 0xb0, 0x80},
            new int[] {0xf4, 0x90, 0x80, 0x80},
            new int[] {0xf5, 0x80, 0x80, 0x80},
            new int[] {0xff},
            new int[] {0xc2},
            new int[] {0xe2, 0x82},
            new int[] {0xf0, 0x9f, 0x98},
            new int[] {0xc2, 0x20})) {
      byte[] invalid = new byte[sequence.length];
      for (int index = 0; index < sequence.length; index++) {
        invalid[index] = (byte) sequence[index];
      }
      byte[] json = concat(prefix, invalid, suffix);
      reject("UTF-8 " + Arrays.toString(sequence), () -> decoder.decode(17, 23, 31, KEY, json));
    }
    reject(
        "truncated UTF-8 at EOF",
        () -> decoder.decode(17, 23, 31, KEY, concat(prefix, new byte[] {(byte) 0xc2})));
  }

  @Test
  public void enforcesExactRawByteBudgetIncludingMultibyteCharactersAndWhitespace() {
    String compact = object("content", "\"\u00e9\u4e2d\ud83d\ude00\"");
    String padded = " \r\n" + compact + "\t ";
    byte[] json = utf8(padded);
    assertTrue(json.length > padded.length());
    assertIdentity(
        decode(compact),
        new AssetLocalizeAdmissionRequestDecoder(json.length).decode(17, 23, 31, KEY, json));
    for (int budget : new int[] {json.length - 1, padded.length(), utf8(compact).length}) {
      reject(
          "raw byte budget " + budget,
          () -> new AssetLocalizeAdmissionRequestDecoder(budget).decode(17, 23, 31, KEY, json));
    }
  }

  @Test
  public void failuresDoNotExposeInputOrKeyThroughExceptionGraphs() {
    String prefix = "{\"localeId\":47,\"content\":\"" + SECRET + "\",";
    for (String json :
        List.of(
            prefix + "\"private-field-sentinel\":null}",
            prefix + "\"status\":\"" + SECRET + "\"}",
            prefix + "\"filterOptions\":[\"" + SECRET + "\",null]}",
            prefix + "\"content\":\"" + SECRET + "\"}",
            prefix + "\"pullRunName\":" + SECRET + "}",
            prefix + "\"pullRunName\":\"\\ud800\"}",
            prefix + "\"pullWithNoSource\":false} \"" + SECRET + "\"")) {
      rejectJson(json);
    }
    reject(
        "oversized private input",
        () ->
            new AssetLocalizeAdmissionRequestDecoder(1)
                .decode(17, 23, 31, KEY, utf8(object("content", "\"" + SECRET + "\""))));
  }

  @Test
  public void decodedIdentityDoesNotAliasInputBytesReturnedSnapshotsOrLaterDecodes() {
    byte[] input = utf8(FULL_JSON);
    AssetLocalizeAdmissionRequest request = decoder.decode(17, 23, 31, KEY, input);
    LocalizedAssetBody first = request.toLocalizedAssetBody();
    LocalizedAssetBody second = request.toLocalizedAssetBody();
    assertNotSame(first, second);
    Arrays.fill(input, (byte) 0);
    Arrays.fill(request.canonicalBytes(), (byte) 0);
    first.setContent("changed");
    first.setFilterOptions(List.of("changed"));
    first.setPullWithNoSourceBranches(Arrays.asList((String) null));
    decode(object("content", "\"next request\""));
    assertIdentity(capture(populatedBody()), request);
    assertIdentity(capture(populatedBody()), capture(second));
  }

  @Test
  public void coverageGuardRequiresReviewOfNewDtoFields() {
    Set<String> covered = new HashSet<>(FIELD_VALUES.keySet());
    covered.add("bcp47Tag");
    assertEquals(
        "Review decoding rules for every new DTO field",
        covered,
        Arrays.stream(LocalizedAssetBody.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .map(Field::getName)
            .collect(Collectors.toSet()));
  }

  private AssetLocalizeAdmissionRequest decode(String json) {
    return decoder.decode(17, 23, 31, KEY, utf8(json));
  }

  private void rejectJson(String json) {
    reject("JSON case", () -> decode(json));
  }

  private static void reject(String label, Runnable action) {
    try {
      action.run();
      fail("Expected IllegalArgumentException: " + label);
    } catch (IllegalArgumentException expected) {
      StringWriter rendered = new StringWriter();
      // Stack trace rendering traverses causes and suppressed exceptions, including their toString.
      expected.printStackTrace(new PrintWriter(rendered));
      String details =
          rendered + " " + expected.getMessage() + " " + expected.getLocalizedMessage();
      for (String secret : List.of(KEY, SECRET, "private-field-sentinel")) {
        assertFalse("Exception exposes private data: " + label, details.contains(secret));
      }
    }
  }

  private static String object(String field, String value) {
    return "{"
        + (field.equals("localeId") ? "" : "\"localeId\":47,")
        + "\""
        + field
        + "\":"
        + value
        + "}";
  }

  private static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] concat(byte[]... parts) {
    byte[] result = new byte[Arrays.stream(parts).mapToInt(part -> part.length).sum()];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, offset, part.length);
      offset += part.length;
    }
    return result;
  }

  private static AssetLocalizeAdmissionRequest capture(LocalizedAssetBody body) {
    return AssetLocalizeAdmissionRequest.capture(17, 23, 31, KEY, body);
  }

  private static void assertIdentity(
      AssetLocalizeAdmissionRequest expected, AssetLocalizeAdmissionRequest actual) {
    assertArrayEquals(expected.canonicalBytes(), actual.canonicalBytes());
    assertEquals(expected.requestSha256(), actual.requestSha256());
    assertEquals(expected.repositoryId(), actual.repositoryId());
    assertEquals(expected.actorUserId(), actual.actorUserId());
    assertEquals(expected.requestKey(), actual.requestKey());
    LocalizedAssetBody snapshot = actual.toLocalizedAssetBody();
    assertArrayEquals(
        expected.canonicalBytes(),
        AssetLocalizeAdmissionRequest.capture(
                actual.repositoryId(),
                actual.actorUserId(),
                snapshot.getAssetId(),
                actual.requestKey(),
                snapshot)
            .canonicalBytes());
  }

  private static LocalizedAssetBody minimalBody() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setAssetId(31L);
    body.setLocaleId(47L);
    return body;
  }

  private static LocalizedAssetBody populatedBody() {
    LocalizedAssetBody body = minimalBody();
    body.setContent("source=Hello");
    body.setOutputBcp47tag("fr-CA");
    body.setFilterConfigIdOverride(FilterConfigIdOverride.PROPERTIES_JAVA);
    body.setFilterOptions(List.of("mode=strict", " Keep Case ", "mode=strict"));
    body.setPullRunName("Release-1");
    body.setInheritanceMode(InheritanceMode.REMOVE_UNTRANSLATED);
    body.setStatus(Status.ACCEPTED);
    body.setPullWithNoSource(true);
    body.setPullWithNoSourceBranches(List.of("main", " release ", "main"));
    return body;
  }
}
