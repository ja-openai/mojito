package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import com.box.l10n.mojito.service.NormalizationUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.Test;

/** Model-only contract tests; these do not exercise HTTP or raw JSON deserialization. */
public class AssetLocalizeAdmissionRequestTest {

  private static final long REPOSITORY_ID = 17L;
  private static final long ACTOR_USER_ID = 23L;
  private static final long ASSET_ID = 31L;
  private static final long LOCALE_ID = 47L;
  private static final String REQUEST_KEY = "Request-Key_1";

  private static final String ASCII_JSON =
      "{\"actorUserId\":23,\"assetId\":31,\"content\":\"source=Hello\","
          + "\"filterConfigIdOverride\":\"PROPERTIES_JAVA\","
          + "\"filterOptions\":[\"mode=strict\",\" Keep Case \",\"mode=strict\"],"
          + "\"inheritanceMode\":\"REMOVE_UNTRANSLATED\",\"localeId\":47,"
          + "\"operation\":\"assetlocalize.direct\",\"outputBcp47tag\":\"fr-CA\","
          + "\"protocolVersion\":1,\"pullRunName\":\"Release-1\",\"pullWithNoSource\":true,"
          + "\"pullWithNoSourceBranches\":[\"main\",\" release \",\"main\"],"
          + "\"repositoryId\":17,\"status\":\"ACCEPTED\"}";

  private static final String UNICODE_JSON =
      "{\"actorUserId\":23,\"assetId\":31,"
          + "\"content\":\"Caf\u00e9 \u4e2d \ud83d\ude00 \\\"\\\\/"
          + "\\u0000\\u0001\\u0002\\u0003\\u0004\\u0005\\u0006\\u0007"
          + "\\u0008\\u0009\\u000a\\u000b\\u000c\\u000d\\u000e\\u000f"
          + "\\u0010\\u0011\\u0012\\u0013\\u0014\\u0015\\u0016\\u0017"
          + "\\u0018\\u0019\\u001a\\u001b\\u001c\\u001d\\u001e\\u001f\","
          + "\"filterConfigIdOverride\":null,"
          + "\"filterOptions\":[\"e\u0301\",\"\u96ea\\u0009\\\"\\\\\"],"
          + "\"inheritanceMode\":\"USE_PARENT\",\"localeId\":47,"
          + "\"operation\":\"assetlocalize.direct\",\"outputBcp47tag\":\"e\u0301-CA\","
          + "\"protocolVersion\":1,\"pullRunName\":\"re\u0301lease\\u000a\u2028\u2029\","
          + "\"pullWithNoSource\":false,"
          + "\"pullWithNoSourceBranches\":[\"\u03b2\",\" e\u0301 \",\"\ud83d\ude80\"],"
          + "\"repositoryId\":17,\"status\":\"ALL\"}";

  private static final String DEFAULT_JSON =
      "{\"actorUserId\":23,\"assetId\":31,\"content\":null,"
          + "\"filterConfigIdOverride\":null,\"filterOptions\":null,"
          + "\"inheritanceMode\":\"USE_PARENT\",\"localeId\":47,"
          + "\"operation\":\"assetlocalize.direct\",\"outputBcp47tag\":null,"
          + "\"protocolVersion\":1,\"pullRunName\":null,\"pullWithNoSource\":false,"
          + "\"pullWithNoSourceBranches\":[],\"repositoryId\":17,\"status\":\"ALL\"}";

  @Test
  public void exposesVersionOperationAndImmutablePublicType() {
    assertEquals(1, AssetLocalizeAdmissionRequest.PROTOCOL_VERSION);
    assertEquals("assetlocalize.direct", AssetLocalizeAdmissionRequest.OPERATION);
    assertTrue(Modifier.isPublic(AssetLocalizeAdmissionRequest.class.getModifiers()));
    assertTrue(Modifier.isFinal(AssetLocalizeAdmissionRequest.class.getModifiers()));
  }

  @Test
  public void matchesAsciiGoldenBytesAndIndependentlyCalculatedSha256() {
    LocalizedAssetBody body = populatedBody();
    AssetLocalizeAdmissionRequest request = capture(body);

    // Golden hashes were computed independently with printf '%s' '<JSON>' | shasum -a 256.
    assertGolden(
        request, ASCII_JSON, "7f462922d145a9a1d6a2465782fb742bfe7b238dd849dc8032e395aa084b935a");
    assertEquals(REPOSITORY_ID, request.repositoryId());
    assertEquals(ACTOR_USER_ID, request.actorUserId());
    assertEquals(REQUEST_KEY, request.requestKey());
    assertBodyEquals(body, request.toLocalizedAssetBody());
  }

  @Test
  public void matchesLiteralUnicodeAndEveryJsonControlCharacterGolden() {
    LocalizedAssetBody body = minimalBody();
    StringBuilder content = new StringBuilder("Cafe\u0301 \u4e2d \ud83d\ude00 \"\\/");
    for (char control = 0; control < 0x20; control++) {
      content.append(control);
    }
    body.setContent(content.toString());
    body.setOutputBcp47tag("e\u0301-CA");
    body.setFilterOptions(List.of("e\u0301", "\u96ea\t\"\\"));
    body.setPullRunName("re\u0301lease\n\u2028\u2029");
    body.setPullWithNoSourceBranches(List.of("\u03b2", " e\u0301 ", "\ud83d\ude80"));

    AssetLocalizeAdmissionRequest request = capture(body);

    assertGolden(
        request, UNICODE_JSON, "94fb4cba672b54fefa4444ca6904f8d2c5a7e25e21d42dbe48db634175cdafd7");
    LocalizedAssetBody snapshot = request.toLocalizedAssetBody();
    assertEquals(NormalizationUtils.normalize(content.toString()), snapshot.getContent());
    assertEquals(content.toString(), body.getContent());
    assertEquals(body.getOutputBcp47tag(), snapshot.getOutputBcp47tag());
    assertEquals(body.getFilterOptions(), snapshot.getFilterOptions());
    assertEquals(body.getPullRunName(), snapshot.getPullRunName());
    assertEquals(body.getPullWithNoSourceBranches(), snapshot.getPullWithNoSourceBranches());
  }

  @Test
  public void preservesDelC1SeparatorsBomCodePointAndSupplementaryCharactersLiterally() {
    String literal =
        "\ufeff\u007f\u0080\u0081\u0082\u0083\u0084\u0085\u0086\u0087"
            + "\u0088\u0089\u008a\u008b\u008c\u008d\u008e\u008f"
            + "\u0090\u0091\u0092\u0093\u0094\u0095\u0096\u0097"
            + "\u0098\u0099\u009a\u009b\u009c\u009d\u009e\u009f"
            + "\u2028\u2029\ud800\udc00\udbff\udfff";
    LocalizedAssetBody body = minimalBody();
    body.setContent(literal);
    AssetLocalizeAdmissionRequest request = capture(body);
    String expectedJson =
        DEFAULT_JSON.replace("\"content\":null", "\"content\":\"" + literal + "\"");

    assertArrayEquals(expectedJson.getBytes(StandardCharsets.UTF_8), request.canonicalBytes());
    assertEquals(literal, request.toLocalizedAssetBody().getContent());
    assertEquals('{', request.canonicalBytes()[0]);
    assertSameCanonical(request, capture(request.toLocalizedAssetBody()));
  }

  @Test
  public void fillsMissingBodyAssetFromPathWithoutMutatingInputAndPreservesDefaults() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setLocaleId(LOCALE_ID);

    AssetLocalizeAdmissionRequest request = capture(body);

    assertArrayEquals(DEFAULT_JSON.getBytes(StandardCharsets.UTF_8), request.canonicalBytes());
    assertNull(body.getAssetId());
    LocalizedAssetBody snapshot = request.toLocalizedAssetBody();
    assertEquals(Long.valueOf(ASSET_ID), snapshot.getAssetId());
    assertEquals(Long.valueOf(LOCALE_ID), snapshot.getLocaleId());
    assertNull(snapshot.getBcp47Tag());
    assertNull(snapshot.getContent());
    assertNull(snapshot.getOutputBcp47tag());
    assertNull(snapshot.getFilterConfigIdOverride());
    assertNull(snapshot.getFilterOptions());
    assertNull(snapshot.getPullRunName());
    assertEquals(InheritanceMode.USE_PARENT, snapshot.getInheritanceMode());
    assertEquals(Status.ALL, snapshot.getStatus());
    assertFalse(snapshot.isPullWithNoSource());
    assertEquals(List.of(), snapshot.getPullWithNoSourceBranches());

    LocalizedAssetBody explicit = minimalBody();
    explicit.setInheritanceMode(InheritanceMode.USE_PARENT);
    explicit.setStatus(Status.ALL);
    explicit.setPullWithNoSource(false);
    explicit.setPullWithNoSourceBranches(List.of());
    assertSameCanonical(request, capture(explicit));
  }

  @Test
  public void treatsDtoNullBranchesAsEmptyButDoesNotTreatNullFilterOptionsAsEmpty() {
    LocalizedAssetBody body = minimalBody();
    AssetLocalizeAdmissionRequest defaults = capture(body);
    body.setPullWithNoSourceBranches(null);
    assertEquals(List.of(), body.getPullWithNoSourceBranches());
    assertSameCanonical(defaults, capture(body));

    body.setFilterOptions(List.of());
    assertDifferentCanonical("null versus empty filterOptions", defaults, capture(body));
    assertEquals(List.of(), capture(body).toLocalizedAssetBody().getFilterOptions());
  }

  @Test
  public void distinguishesUnnamedBranchLiteralNullAndEmptyBranchesEvenWhenNoSourceIsFalse() {
    LocalizedAssetBody unnamedBody = minimalBody();
    unnamedBody.setPullWithNoSourceBranches(Arrays.asList((String) null));
    LocalizedAssetBody literalNullBody = minimalBody();
    literalNullBody.setPullWithNoSourceBranches(List.of("null"));
    AssetLocalizeAdmissionRequest unnamed = capture(unnamedBody);
    AssetLocalizeAdmissionRequest literalNull = capture(literalNullBody);
    AssetLocalizeAdmissionRequest empty = capture(minimalBody());

    assertDifferentCanonical("unnamed versus literal null", unnamed, literalNull);
    assertDifferentCanonical("unnamed versus all branches", unnamed, empty);
    assertDifferentCanonical("literal null versus all branches", literalNull, empty);
    assertEquals(
        DEFAULT_JSON.replace(
            "\"pullWithNoSourceBranches\":[]", "\"pullWithNoSourceBranches\":[null]"),
        canonical(unnamed));
    assertEquals(
        DEFAULT_JSON.replace(
            "\"pullWithNoSourceBranches\":[]", "\"pullWithNoSourceBranches\":[\"null\"]"),
        canonical(literalNull));
    assertFalse(unnamed.toLocalizedAssetBody().isPullWithNoSource());
    assertFalse(literalNull.toLocalizedAssetBody().isPullWithNoSource());
    assertBodyEquals(unnamedBody, unnamed.toLocalizedAssetBody());
    assertBodyEquals(literalNullBody, literalNull.toLocalizedAssetBody());
    assertSameCanonical(unnamed, capture(unnamed.toLocalizedAssetBody()));
  }

  @Test
  public void preservesNullBranchOrderDuplicatesAndSnapshotIndependence() {
    List<String> expected = Arrays.asList(null, "main", null, "null", "");
    List<String> callerBranches = new ArrayList<>(expected);
    LocalizedAssetBody body = minimalBody();
    body.setPullWithNoSourceBranches(callerBranches);
    AssetLocalizeAdmissionRequest request = capture(body);
    byte[] originalBytes = request.canonicalBytes();
    String originalSha256 = request.requestSha256();

    callerBranches.clear();
    body.setPullWithNoSourceBranches(List.of());
    LocalizedAssetBody first = request.toLocalizedAssetBody();
    LocalizedAssetBody second = request.toLocalizedAssetBody();
    assertEquals(expected, first.getPullWithNoSourceBranches());
    assertTrue(
        canonical(request)
            .contains("\"pullWithNoSourceBranches\":[null,\"main\",null,\"null\",\"\"]"));
    mutateListIfMutable(first.getPullWithNoSourceBranches());
    first.setPullWithNoSourceBranches(List.of("changed"));

    assertEquals(expected, second.getPullWithNoSourceBranches());
    assertEquals(expected, request.toLocalizedAssetBody().getPullWithNoSourceBranches());
    assertArrayEquals(originalBytes, request.canonicalBytes());
    assertEquals(originalSha256, request.requestSha256());
    for (List<String> variant :
        List.of(
            Arrays.asList("main", null, null, "null", ""),
            Arrays.asList(null, "main", "null", ""),
            Arrays.asList("null", "main", null, "null", ""))) {
      LocalizedAssetBody changed = minimalBody();
      changed.setPullWithNoSourceBranches(variant);
      assertDifferentCanonical("null branch entry semantics", request, capture(changed));
    }
  }

  @Test
  public void preservesNullVersusEmptyForEveryOptionalString() {
    Map<String, Consumer<LocalizedAssetBody>> emptyValues = new LinkedHashMap<>();
    emptyValues.put("content", body -> body.setContent(""));
    emptyValues.put("outputBcp47tag", body -> body.setOutputBcp47tag(""));
    emptyValues.put("pullRunName", body -> body.setPullRunName(""));
    AssetLocalizeAdmissionRequest nullValues = capture(minimalBody());

    emptyValues.forEach(
        (field, mutation) -> {
          LocalizedAssetBody body = minimalBody();
          mutation.accept(body);
          AssetLocalizeAdmissionRequest request = capture(body);
          assertDifferentCanonical(field, nullValues, request);
          assertBodyEquals(body, request.toLocalizedAssetBody());
        });
  }

  @Test
  public void normalizesOnlyContentUsingTheGenerationNormalizationContract() {
    LocalizedAssetBody decomposed = populatedBody();
    decomposed.setContent(" Cafe\u0301\r\n\u1100\u1161 \ufb01 ");
    LocalizedAssetBody composed = populatedBody();
    composed.setContent(" Caf\u00e9\r\n\uac00 \ufb01 ");

    AssetLocalizeAdmissionRequest request = capture(decomposed);

    assertSameCanonical(request, capture(composed));
    assertEquals(composed.getContent(), request.toLocalizedAssetBody().getContent());
    assertEquals(
        NormalizationUtils.normalize(decomposed.getContent()),
        request.toLocalizedAssetBody().getContent());
    assertEquals(" Cafe\u0301\r\n\u1100\u1161 \ufb01 ", decomposed.getContent());

    Map<String, BiConsumer<LocalizedAssetBody, String>> metadata = new LinkedHashMap<>();
    metadata.put("outputBcp47tag", LocalizedAssetBody::setOutputBcp47tag);
    metadata.put("pullRunName", LocalizedAssetBody::setPullRunName);
    metadata.put("filterOptions", (body, value) -> body.setFilterOptions(List.of(value)));
    metadata.put(
        "pullWithNoSourceBranches",
        (body, value) -> body.setPullWithNoSourceBranches(List.of(value)));
    metadata.forEach(
        (field, setter) -> {
          LocalizedAssetBody first = populatedBody();
          LocalizedAssetBody second = populatedBody();
          setter.accept(first, "e\u0301");
          setter.accept(second, "\u00e9");
          assertDifferentCanonical(field + " is not normalized", capture(first), capture(second));
          assertBodyEquals(first, capture(first).toLocalizedAssetBody());
        });
  }

  @Test
  public void includesEveryExecutionAffectingBodyFieldInBytesAndChecksum() {
    Map<String, Consumer<LocalizedAssetBody>> mutations = new LinkedHashMap<>();
    mutations.put("localeId", body -> body.setLocaleId(LOCALE_ID + 1));
    mutations.put("content", body -> body.setContent("source=Changed"));
    mutations.put("outputBcp47tag", body -> body.setOutputBcp47tag("fr-FR"));
    mutations.put(
        "filterConfigIdOverride",
        body -> body.setFilterConfigIdOverride(FilterConfigIdOverride.HTML_ALPHA));
    mutations.put("filterOptions", body -> body.setFilterOptions(List.of("mode=changed")));
    mutations.put("inheritanceMode", body -> body.setInheritanceMode(InheritanceMode.USE_PARENT));
    mutations.put("status", body -> body.setStatus(Status.ACCEPTED_OR_NEEDS_REVIEW));
    mutations.put("pullRunName", body -> body.setPullRunName("Release-2"));
    mutations.put("pullWithNoSource", body -> body.setPullWithNoSource(false));
    mutations.put(
        "pullWithNoSourceBranches", body -> body.setPullWithNoSourceBranches(List.of("other")));
    AssetLocalizeAdmissionRequest baseline = capture(populatedBody());

    mutations.forEach(
        (field, mutation) -> {
          LocalizedAssetBody body = populatedBody();
          mutation.accept(body);
          AssetLocalizeAdmissionRequest changed = capture(body);
          assertDifferentCanonical(field, baseline, changed);
          assertBodyEquals(body, changed.toLocalizedAssetBody());
        });
  }

  @Test
  public void includesRepositoryActorAndAuthoritativeAssetInBytesAndChecksum() {
    LocalizedAssetBody body = populatedBody();
    AssetLocalizeAdmissionRequest baseline = capture(body);
    AssetLocalizeAdmissionRequest repositoryChanged =
        AssetLocalizeAdmissionRequest.capture(
            REPOSITORY_ID + 1, ACTOR_USER_ID, ASSET_ID, REQUEST_KEY, body);
    AssetLocalizeAdmissionRequest actorChanged =
        AssetLocalizeAdmissionRequest.capture(
            REPOSITORY_ID, ACTOR_USER_ID + 1, ASSET_ID, REQUEST_KEY, body);
    body.setAssetId(ASSET_ID + 1);
    AssetLocalizeAdmissionRequest assetChanged =
        AssetLocalizeAdmissionRequest.capture(
            REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID + 1, REQUEST_KEY, body);

    assertDifferentCanonical("repositoryId", baseline, repositoryChanged);
    assertDifferentCanonical("actorUserId", baseline, actorChanged);
    assertDifferentCanonical("assetId", baseline, assetChanged);
    assertEquals(REPOSITORY_ID + 1, repositoryChanged.repositoryId());
    assertEquals(ACTOR_USER_ID + 1, actorChanged.actorUserId());
    assertEquals(Long.valueOf(ASSET_ID + 1), assetChanged.toLocalizedAssetBody().getAssetId());
  }

  @Test
  public void usesEnumNamesForEverySupportedEnumValue() {
    for (FilterConfigIdOverride value : FilterConfigIdOverride.values()) {
      LocalizedAssetBody body = minimalBody();
      body.setFilterConfigIdOverride(value);
      AssetLocalizeAdmissionRequest request = capture(body);
      assertTrue(
          canonical(request).contains("\"filterConfigIdOverride\":\"" + value.name() + "\""));
      assertEquals(value, request.toLocalizedAssetBody().getFilterConfigIdOverride());
    }
    for (InheritanceMode value : InheritanceMode.values()) {
      LocalizedAssetBody body = minimalBody();
      body.setInheritanceMode(value);
      AssetLocalizeAdmissionRequest request = capture(body);
      assertTrue(canonical(request).contains("\"inheritanceMode\":\"" + value.name() + "\""));
      assertEquals(value, request.toLocalizedAssetBody().getInheritanceMode());
    }
    for (Status value : Status.values()) {
      LocalizedAssetBody body = minimalBody();
      body.setStatus(value);
      AssetLocalizeAdmissionRequest request = capture(body);
      assertTrue(canonical(request).contains("\"status\":\"" + value.name() + "\""));
      assertEquals(value, request.toLocalizedAssetBody().getStatus());
    }
  }

  @Test
  public void preservesListOrderDuplicatesWhitespaceCaseAndEmptyEntries() {
    List<String> original = List.of(" First ", "second", " First ", "");
    List<List<String>> variants =
        List.of(
            List.of("second", " First ", " First ", ""),
            List.of(" First ", "second", ""),
            List.of("First", "second", " First ", ""),
            List.of(" first ", "second", " First ", ""),
            List.of(" First ", "second", " First "));
    Map<String, BiConsumer<LocalizedAssetBody, List<String>>> lists = new LinkedHashMap<>();
    lists.put("filterOptions", LocalizedAssetBody::setFilterOptions);
    lists.put("pullWithNoSourceBranches", LocalizedAssetBody::setPullWithNoSourceBranches);

    lists.forEach(
        (field, setter) -> {
          LocalizedAssetBody body = minimalBody();
          setter.accept(body, original);
          AssetLocalizeAdmissionRequest baseline = capture(body);
          assertBodyEquals(body, baseline.toLocalizedAssetBody());
          for (int index = 0; index < variants.size(); index++) {
            LocalizedAssetBody changed = minimalBody();
            setter.accept(changed, variants.get(index));
            assertDifferentCanonical(field + " variant " + index, baseline, capture(changed));
          }
        });
  }

  @Test
  public void requestKeyIsCasePreservedAndExcludedFromCanonicalBytesAndChecksum() {
    LocalizedAssetBody body = populatedBody();
    AssetLocalizeAdmissionRequest baseline = capture(body);
    for (String key : List.of("request-key_1", "Different-Key", "!", "~", "K".repeat(128))) {
      AssetLocalizeAdmissionRequest request =
          AssetLocalizeAdmissionRequest.capture(REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID, key, body);
      assertEquals(key, request.requestKey());
      assertSameCanonical(baseline, request);
    }

    StringBuilder printableAscii = new StringBuilder();
    for (char character = '!'; character <= '~'; character++) {
      printableAscii.append(character);
    }
    AssetLocalizeAdmissionRequest allAllowedCharacters =
        AssetLocalizeAdmissionRequest.capture(
            REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID, printableAscii.toString(), body);
    assertEquals(printableAscii.toString(), allAllowedCharacters.requestKey());
    assertSameCanonical(baseline, allAllowedCharacters);
    assertFalse(canonical(baseline).contains(REQUEST_KEY));
    assertFalse(canonical(baseline).contains("requestKey"));
  }

  @Test
  public void acceptsPositiveLongBoundariesAndEncodesDecimalIntegersExactly() {
    for (long id : new long[] {1L, 9007199254740993L, Long.MAX_VALUE}) {
      LocalizedAssetBody body = minimalBody();
      body.setAssetId(id);
      body.setLocaleId(id);
      AssetLocalizeAdmissionRequest request =
          AssetLocalizeAdmissionRequest.capture(id, id, id, REQUEST_KEY, body);

      assertEquals(id, request.repositoryId());
      assertEquals(id, request.actorUserId());
      assertEquals(Long.valueOf(id), request.toLocalizedAssetBody().getAssetId());
      assertEquals(Long.valueOf(id), request.toLocalizedAssetBody().getLocaleId());
      for (String field : List.of("actorUserId", "assetId", "localeId", "repositoryId")) {
        assertTrue(field, canonical(request).contains("\"" + field + "\":" + id + ","));
      }
    }
  }

  @Test
  public void rejectsNullBodyAndNonpositiveRepositoryActorPathAndLocaleIds() {
    assertRejected("null body", () -> capture(null));
    for (long id : new long[] {Long.MIN_VALUE, -1L, 0L}) {
      assertRejected(
          "repositoryId=" + id,
          () ->
              AssetLocalizeAdmissionRequest.capture(
                  id, ACTOR_USER_ID, ASSET_ID, REQUEST_KEY, minimalBody()));
      assertRejected(
          "actorUserId=" + id,
          () ->
              AssetLocalizeAdmissionRequest.capture(
                  REPOSITORY_ID, id, ASSET_ID, REQUEST_KEY, minimalBody()));
      LocalizedAssetBody matchingPathBody = minimalBody();
      matchingPathBody.setAssetId(id);
      assertRejected(
          "pathAssetId=" + id,
          () ->
              AssetLocalizeAdmissionRequest.capture(
                  REPOSITORY_ID, ACTOR_USER_ID, id, REQUEST_KEY, matchingPathBody));
      LocalizedAssetBody localeBody = minimalBody();
      localeBody.setLocaleId(id);
      assertRejected("localeId=" + id, () -> capture(localeBody));
    }
  }

  @Test
  public void rejectsMismatchedBodyAssetIdsEvenWhenBothIdsArePositive() {
    for (long bodyAssetId : new long[] {Long.MIN_VALUE, -1L, 0L, ASSET_ID + 1, Long.MAX_VALUE}) {
      LocalizedAssetBody body = minimalBody();
      body.setAssetId(bodyAssetId);
      assertRejected("body assetId=" + bodyAssetId, () -> capture(body));
    }
  }

  @Test
  public void rejectsMissingRequiredFieldsAndNullFilterOptionEntries() {
    Map<String, Consumer<LocalizedAssetBody>> invalid = new LinkedHashMap<>();
    invalid.put("null localeId", body -> body.setLocaleId(null));
    invalid.put("null inheritanceMode", body -> body.setInheritanceMode(null));
    invalid.put("null status", body -> body.setStatus(null));
    invalid.put("null filter option", body -> body.setFilterOptions(Arrays.asList((String) null)));
    invalid.put(
        "null middle filter option", body -> body.setFilterOptions(Arrays.asList("a", null, "b")));
    invalid.forEach(
        (label, mutation) -> {
          LocalizedAssetBody body = minimalBody();
          mutation.accept(body);
          assertRejected(label, () -> capture(body));
        });
  }

  @Test
  public void rejectsEverySuppliedResponseBcp47TagIncludingEmpty() {
    for (String tag : List.of("", "fr", "fr-CA", " ", "\ud83d\ude00")) {
      LocalizedAssetBody body = minimalBody();
      body.setBcp47Tag(tag);
      assertRejected("response bcp47Tag", () -> capture(body));
    }
  }

  @Test
  public void rejectsInvalidRequestKeyMatrixWithoutTrimmingOrUnicodeCoercion() {
    List<String> invalid =
        Arrays.asList(
            null,
            "",
            "K".repeat(129),
            " leading",
            "trailing ",
            "in ternal",
            "\tkey",
            "key\n",
            "key\r",
            "\u0000",
            "\u001f",
            "\u007f",
            "\u00a0",
            "\u00e9",
            "\u20ac",
            "\ud83d\ude00",
            "\ud800",
            "\udc00");
    for (int index = 0; index < invalid.size(); index++) {
      String key = invalid.get(index);
      assertRejected(
          "request key case " + index,
          () ->
              AssetLocalizeAdmissionRequest.capture(
                  REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID, key, minimalBody()));
    }
  }

  @Test
  public void rejectsUnpairedUtf16SurrogatesInEveryStringFieldAndListEntry() {
    List<String> malformed =
        List.of(
            "\ud800",
            "\udc00",
            "\ud800x",
            "x\udc00",
            "x\ud800y",
            "x\udc00y",
            "\ud800\ud800",
            "\udc00\udc00",
            "\udc00\ud800",
            "\ud83d\ude00\ud800",
            "\ud800\ud83d\ude00");
    Map<String, BiConsumer<LocalizedAssetBody, String>> fields = new LinkedHashMap<>();
    fields.put("content", LocalizedAssetBody::setContent);
    fields.put("bcp47Tag", LocalizedAssetBody::setBcp47Tag);
    fields.put("outputBcp47tag", LocalizedAssetBody::setOutputBcp47tag);
    fields.put("pullRunName", LocalizedAssetBody::setPullRunName);
    fields.put("filterOptions", (body, value) -> body.setFilterOptions(List.of("valid", value)));
    fields.put(
        "pullWithNoSourceBranches",
        (body, value) -> body.setPullWithNoSourceBranches(List.of("valid", value)));
    fields.forEach(
        (field, setter) -> {
          for (int index = 0; index < malformed.size(); index++) {
            LocalizedAssetBody body = minimalBody();
            setter.accept(body, malformed.get(index));
            assertRejected(field + " surrogate case " + index, () -> capture(body));
          }
        });
    for (int index = 0; index < malformed.size(); index++) {
      String key = malformed.get(index);
      assertRejected(
          "requestKey surrogate case " + index,
          () ->
              AssetLocalizeAdmissionRequest.capture(
                  REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID, key, minimalBody()));
    }
  }

  @Test
  public void inputDtoAndCallerOwnedListsCannotMutateCapturedRequest() {
    LocalizedAssetBody body = populatedBody();
    List<String> options = new ArrayList<>(body.getFilterOptions());
    List<String> branches = new ArrayList<>(body.getPullWithNoSourceBranches());
    body.setFilterOptions(options);
    body.setPullWithNoSourceBranches(branches);
    AssetLocalizeAdmissionRequest request = capture(body);

    options.set(0, "changed in caller list");
    options.add(null);
    branches.clear();
    branches.add("changed in caller list");
    overwriteBody(body);

    assertGolden(
        request, ASCII_JSON, "7f462922d145a9a1d6a2465782fb742bfe7b238dd849dc8032e395aa084b935a");
    assertBodyEquals(populatedBody(), request.toLocalizedAssetBody());
    assertEquals(REPOSITORY_ID, request.repositoryId());
    assertEquals(ACTOR_USER_ID, request.actorUserId());
    assertEquals(REQUEST_KEY, request.requestKey());
  }

  @Test
  public void returnedDtosAndListsCannotMutateEachOtherOrTheCapturedRequest() {
    AssetLocalizeAdmissionRequest request = capture(populatedBody());
    LocalizedAssetBody first = request.toLocalizedAssetBody();
    LocalizedAssetBody second = request.toLocalizedAssetBody();
    assertNotSame(first, second);

    mutateListIfMutable(first.getFilterOptions());
    mutateListIfMutable(first.getPullWithNoSourceBranches());
    assertBodyEquals(populatedBody(), second);
    assertBodyEquals(populatedBody(), request.toLocalizedAssetBody());
    overwriteBody(first);
    assertBodyEquals(populatedBody(), second);
    overwriteBody(second);

    assertBodyEquals(populatedBody(), request.toLocalizedAssetBody());
    assertGolden(
        request, ASCII_JSON, "7f462922d145a9a1d6a2465782fb742bfe7b238dd849dc8032e395aa084b935a");
  }

  @Test
  public void canonicalBytesAreFreshDefensiveCopiesAndIndependentOfReturnedBodies() {
    AssetLocalizeAdmissionRequest request = capture(populatedBody());
    byte[] first = request.canonicalBytes();
    byte[] second = request.canonicalBytes();
    assertNotSame(first, second);

    Arrays.fill(first, (byte) 0);
    assertArrayEquals(ASCII_JSON.getBytes(StandardCharsets.UTF_8), second);
    Arrays.fill(second, (byte) 0xff);

    assertGolden(
        request, ASCII_JSON, "7f462922d145a9a1d6a2465782fb742bfe7b238dd849dc8032e395aa084b935a");
    assertBodyEquals(populatedBody(), request.toLocalizedAssetBody());
  }

  @Test
  public void recapturingAnIndependentSnapshotPreservesCanonicalBytesAndChecksum() {
    AssetLocalizeAdmissionRequest original = capture(populatedBody());
    AssetLocalizeAdmissionRequest recaptured = capture(original.toLocalizedAssetBody());
    assertNotSame(original, recaptured);
    assertSameCanonical(original, recaptured);
  }

  @Test
  public void toStringDoesNotExposeRequestKeyInputOrChecksum() {
    LocalizedAssetBody body = populatedBody();
    body.setContent("secret-content-sentinel");
    body.setFilterOptions(List.of("secret-option-sentinel", "another-secret-option"));
    body.setOutputBcp47tag("secret-output-tag-sentinel");
    body.setPullRunName("secret-pull-run-sentinel");
    body.setPullWithNoSourceBranches(List.of("secret-branch-sentinel"));
    AssetLocalizeAdmissionRequest request =
        AssetLocalizeAdmissionRequest.capture(
            REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID, "secret-request-key-sentinel", body);

    String description = request.toString();

    assertNotNull(description);
    for (String secret :
        List.of(
            "secret-content-sentinel",
            "secret-option-sentinel",
            "another-secret-option",
            "secret-output-tag-sentinel",
            "secret-pull-run-sentinel",
            "secret-branch-sentinel",
            "secret-request-key-sentinel",
            request.requestSha256())) {
      assertFalse("toString exposes " + secret, description.contains(secret));
    }
  }

  @Test
  public void dtoFieldCoverageMustBeReviewedWhenItsRequestOrResponseShapeChanges() {
    Set<String> instanceFields =
        Arrays.stream(LocalizedAssetBody.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
            .map(Field::getName)
            .collect(Collectors.toSet());

    assertEquals(
        "Review canonical identity, validation and snapshot tests for every added DTO field",
        Set.of(
            "assetId",
            "localeId",
            "bcp47Tag",
            "content",
            "outputBcp47tag",
            "filterConfigIdOverride",
            "filterOptions",
            "pullRunName",
            "inheritanceMode",
            "status",
            "pullWithNoSource",
            "pullWithNoSourceBranches"),
        instanceFields);
  }

  private static AssetLocalizeAdmissionRequest capture(LocalizedAssetBody body) {
    return AssetLocalizeAdmissionRequest.capture(
        REPOSITORY_ID, ACTOR_USER_ID, ASSET_ID, REQUEST_KEY, body);
  }

  private static LocalizedAssetBody minimalBody() {
    LocalizedAssetBody body = new LocalizedAssetBody();
    body.setAssetId(ASSET_ID);
    body.setLocaleId(LOCALE_ID);
    return body;
  }

  private static LocalizedAssetBody populatedBody() {
    LocalizedAssetBody body = minimalBody();
    body.setContent("source=Hello");
    body.setOutputBcp47tag("fr-CA");
    body.setFilterConfigIdOverride(FilterConfigIdOverride.PROPERTIES_JAVA);
    body.setFilterOptions(new ArrayList<>(List.of("mode=strict", " Keep Case ", "mode=strict")));
    body.setInheritanceMode(InheritanceMode.REMOVE_UNTRANSLATED);
    body.setStatus(Status.ACCEPTED);
    body.setPullRunName("Release-1");
    body.setPullWithNoSource(true);
    body.setPullWithNoSourceBranches(List.of("main", " release ", "main"));
    return body;
  }

  private static void overwriteBody(LocalizedAssetBody body) {
    body.setAssetId(101L);
    body.setLocaleId(103L);
    body.setBcp47Tag("response-only");
    body.setContent("changed content");
    body.setOutputBcp47tag("changed output");
    body.setFilterConfigIdOverride(FilterConfigIdOverride.HTML_ALPHA);
    body.setFilterOptions(new ArrayList<>(List.of("changed option")));
    body.setInheritanceMode(InheritanceMode.USE_PARENT);
    body.setStatus(Status.ALL);
    body.setPullRunName("changed run");
    body.setPullWithNoSource(false);
    body.setPullWithNoSourceBranches(List.of("changed branch"));
  }

  private static void mutateListIfMutable(List<String> values) {
    try {
      values.set(0, "changed returned entry");
    } catch (UnsupportedOperationException expected) {
      // Immutable lists and mutable independent copies both satisfy snapshot isolation.
    }
  }

  private static void assertBodyEquals(LocalizedAssetBody expected, LocalizedAssetBody actual) {
    assertEquals(expected.getAssetId(), actual.getAssetId());
    assertEquals(expected.getLocaleId(), actual.getLocaleId());
    assertEquals(expected.getBcp47Tag(), actual.getBcp47Tag());
    assertEquals(expected.getContent(), actual.getContent());
    assertEquals(expected.getOutputBcp47tag(), actual.getOutputBcp47tag());
    assertEquals(expected.getFilterConfigIdOverride(), actual.getFilterConfigIdOverride());
    assertEquals(expected.getFilterOptions(), actual.getFilterOptions());
    assertEquals(expected.getInheritanceMode(), actual.getInheritanceMode());
    assertEquals(expected.getStatus(), actual.getStatus());
    assertEquals(expected.getPullRunName(), actual.getPullRunName());
    assertEquals(expected.isPullWithNoSource(), actual.isPullWithNoSource());
    assertEquals(expected.getPullWithNoSourceBranches(), actual.getPullWithNoSourceBranches());
  }

  private static void assertGolden(
      AssetLocalizeAdmissionRequest request, String expectedJson, String expectedSha256) {
    assertArrayEquals(expectedJson.getBytes(StandardCharsets.UTF_8), request.canonicalBytes());
    assertEquals(expectedJson, canonical(request));
    assertEquals(expectedSha256, request.requestSha256());
  }

  private static String canonical(AssetLocalizeAdmissionRequest request) {
    return new String(request.canonicalBytes(), StandardCharsets.UTF_8);
  }

  private static void assertSameCanonical(
      AssetLocalizeAdmissionRequest expected, AssetLocalizeAdmissionRequest actual) {
    assertArrayEquals(expected.canonicalBytes(), actual.canonicalBytes());
    assertEquals(expected.requestSha256(), actual.requestSha256());
  }

  private static void assertDifferentCanonical(
      String field, AssetLocalizeAdmissionRequest first, AssetLocalizeAdmissionRequest second) {
    assertFalse(
        field + " must affect bytes",
        Arrays.equals(first.canonicalBytes(), second.canonicalBytes()));
    assertFalse(
        field + " must affect checksum", first.requestSha256().equals(second.requestSha256()));
  }

  private static void assertRejected(String label, Runnable capture) {
    try {
      capture.run();
      fail("Expected IllegalArgumentException: " + label);
    } catch (IllegalArgumentException expected) {
      // Error messages are not part of the capture API contract.
    }
  }
}
