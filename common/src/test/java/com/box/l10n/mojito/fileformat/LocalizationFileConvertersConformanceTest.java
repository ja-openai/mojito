package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.text.MessagePattern.ApostropheMode;
import com.ibm.icu.text.PluralRules;
import com.ibm.icu.util.ULocale;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;
import org.junit.Test;

/** Executes the same real-file manifest used by the standalone Rust converter crate. */
public class LocalizationFileConvertersConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<Integer> EXTENDED_GETTEXT_SAMPLES =
      List.of(
          1001,
          1002,
          1010,
          1011,
          1100,
          10_000,
          100_000,
          999_999,
          1_000_000,
          1_000_001,
          1_000_002,
          2_000_000,
          1_000_000_000);

  @Test
  public void existingTranslationMemoryPlaceholdersAreNormalizedOnce() {
    LocalizationMessage message = new LocalizationMessage("{arg0}", null, null, null, null);

    assertEquals(
        "Bonjour {arg0}",
        LocalizationFileConverters.normalizeMojitoTranslation(
            LocalizationFileFormat.ANDROID, message, null, "Bonjour %1$s"));
    assertEquals(
        "Bonjour {arg0}",
        LocalizationFileConverters.normalizeMojitoTranslation(
            LocalizationFileFormat.APPLE_STRINGS, message, null, "Bonjour %1$@"));
    assertEquals(
        "Bonjour {arg0}",
        LocalizationFileConverters.normalizeMojitoTranslation(
            LocalizationFileFormat.GETTEXT_PO, message, null, "Bonjour %1$d"));
  }

  @Test
  public void allSharedMojitoWorkflowFixtures() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      String id = fixture.path("id").asText();
      LocalizationFileFormat format =
          LocalizationFileFormat.fromId(fixture.path("format").asText());
      byte[] source = Files.readAllBytes(root.resolve(fixture.path("input").asText()));
      List<String> options = new ArrayList<>();
      fixture.path("filterOptions").forEach(option -> options.add(option.asText()));
      if (fixture.has("error")) {
        try {
          if (fixture.has("translations")) {
            Map<String, String> translations = new LinkedHashMap<>();
            fixture
                .path("translations")
                .fields()
                .forEachRemaining(
                    entry -> translations.put(entry.getKey(), entry.getValue().asText()));
            LocalizationFileConverters.localizeForMojito(
                format,
                source,
                translations,
                options,
                fixture.path("removeUntranslated").asBoolean());
          } else if (fixture.has("importPolicy")) {
            LocalizationFileConverters.parseForMojitoImport(
                format,
                source,
                options,
                fixture.path("importPolicy").path("targetLocale").asText(),
                fixture.path("importPolicy").path("copyFormsOnImport").asBoolean());
          } else {
            LocalizationFileConverters.parseForMojito(format, source, options);
          }
          fail(id + ": expected workflow error " + fixture.path("error").asText());
        } catch (LocalizationParseException invalid) {
          assertEquals(id, fixture.path("error").asText(), invalid.code());
        }
        checked++;
        continue;
      }
      if (fixture.has("expected")) {
        JsonNode expected = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
        LocalizationCatalog actual =
            fixture.has("importPolicy")
                ? LocalizationFileConverters.parseForMojitoImport(
                    format,
                    source,
                    options,
                    fixture.path("importPolicy").path("targetLocale").asText(),
                    fixture.path("importPolicy").path("copyFormsOnImport").asBoolean())
                : LocalizationFileConverters.parseForMojito(format, source, options);
        assertEquals(id, expected, JSON.valueToTree(actual));
        if (fixture.path("importRoundTrip").asBoolean()) {
          String normalized = LocalizationFileConverters.write(format, actual);
          LocalizationCatalog reparsed =
              LocalizationFileConverters.parse(format, normalized.getBytes(StandardCharsets.UTF_8));
          assertEquals(
              id + ": imported normalized round trip", expected, JSON.valueToTree(reparsed));
        }
      }
      if (fixture.has("translations")) {
        Map<String, String> translations = new LinkedHashMap<>();
        fixture
            .path("translations")
            .fields()
            .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
        String localized =
            new String(
                LocalizationFileConverters.localizeForMojito(
                    format,
                    source,
                    translations,
                    options,
                    fixture.path("removeUntranslated").asBoolean(),
                    fixture.has("targetLocale") ? fixture.path("targetLocale").asText() : null),
                StandardCharsets.UTF_8);
        if (fixture.has("localized")) {
          String path = fixture.path("localized").asText();
          String expected = path.isEmpty() ? "" : Files.readString(root.resolve(path));
          assertEquals(id + ": localized output", expected, localized);
        }
        if (fixture.has("localizedEndsWithNewline")) {
          assertEquals(
              id + ": source-owned final newline",
              fixture.path("localizedEndsWithNewline").asBoolean(),
              localized.endsWith("\n"));
        }
        for (JsonNode value : fixture.path("localizedContains")) {
          assertTrue(
              id + ": missing localized content " + value.asText(),
              localized.contains(value.asText()));
        }
        for (JsonNode value : fixture.path("localizedExcludes")) {
          assertFalse(
              id + ": retained excluded content " + value.asText(),
              localized.contains(value.asText()));
        }
      }
      checked++;
    }
    assertEquals(
        "Every configured Mojito workflow fixture must execute",
        manifest.path("workflowCases").size(),
        checked);
  }

  @Test
  public void mojitoWorkflowPreservesUtf16SourceEncodings() throws Exception {
    Path root = findFixtureRoot();
    String androidSource =
        Files.readString(root.resolve("fixtures/workflow/android-output.xml"))
            .replace("encoding=\"utf-8\"", "encoding=\"UTF-16LE\"");
    ByteArrayOutputStream androidBytes = new ByteArrayOutputStream();
    androidBytes.write(new byte[] {(byte) 0xff, (byte) 0xfe});
    androidBytes.write(androidSource.getBytes(StandardCharsets.UTF_16LE));
    byte[] android = androidBytes.toByteArray();
    byte[] localizedAndroid =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.ANDROID,
            android,
            Map.of("retained", "Bonjour", "count#other", "Articles"),
            List.of("removeDescription=true", "postRemoveTranslatableFalse=true"),
            true);
    assertTrue(
        "Android UTF-16 BOM must survive postprocessing", localizedAndroid[0] == (byte) 0xff);
    assertTrue(
        "Android UTF-16 declaration must retain original spelling",
        new String(localizedAndroid, 2, localizedAndroid.length - 2, StandardCharsets.UTF_16LE)
            .contains("encoding=\"UTF-16LE\""));

    byte[] apple =
        encode(root.resolve("fixtures/workflow/apple-output.strings"), "UTF-16BE-BOM", "");
    byte[] localizedApple =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.APPLE_STRINGS,
            apple,
            Map.of("visible", "Bonjour"),
            List.of("removeComment=true"),
            true);
    assertTrue("Apple UTF-16 BOM must survive postprocessing", localizedApple[0] == (byte) 0xfe);
    assertTrue(
        "Apple UTF-16 translation must remain encoded",
        new String(localizedApple, 2, localizedApple.length - 2, StandardCharsets.UTF_16BE)
            .contains("Bonjour"));

    byte[] applePlurals =
        encode(root.resolve("fixtures/apple/multiple.stringsdict"), "UTF-16LE-BOM", "");
    byte[] localizedPlurals =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.APPLE_STRINGSDICT,
            applePlurals,
            Map.of(
                "summary#files#other", "{files} 個のファイル",
                "summary#folders#other", "{folders} 個のフォルダー"),
            List.of(),
            false,
            "ja-JP");
    assertTrue(
        "Apple plural UTF-16 BOM must survive locale cleanup",
        localizedPlurals[0] == (byte) 0xff && localizedPlurals[1] == (byte) 0xfe);
    String localizedPluralText =
        new String(localizedPlurals, 2, localizedPlurals.length - 2, StandardCharsets.UTF_16LE);
    assertFalse(
        "Japanese output must remove unused one forms",
        localizedPluralText.contains("<key>one</key>"));
    assertTrue(
        "Japanese output must preserve translated UTF-16 text",
        localizedPluralText.contains("個のファイル"));
  }

  @Test
  public void allSharedFormatFixtures() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.get("cases")) {
      String id = fixture.get("id").asText();
      LocalizationFileFormat format = LocalizationFileFormat.fromId(fixture.get("format").asText());
      byte[] bytes =
          fixture.has("binaryFixture")
              ? HexFormat.of()
                  .parseHex(
                      Files.readString(root.resolve(fixture.get("binaryFixture").asText()))
                          .replaceAll("\\s+", ""))
              : encode(
                  root.resolve(fixture.get("input").asText()),
                  fixture.path("encoding").asText(),
                  fixture.path("lineEndings").asText());
      if (fixture.has("binaryPaddingBytes")) {
        bytes = Arrays.copyOf(bytes, bytes.length + fixture.get("binaryPaddingBytes").asInt());
      }
      Charset propertiesCharset =
          "ISO-8859-1".equals(fixture.path("encoding").asText())
              ? StandardCharsets.ISO_8859_1
              : StandardCharsets.UTF_8;
      String resourcePath =
          fixture.has("resourcePath") ? fixture.get("resourcePath").asText() : null;
      Map<String, Boolean> featureFlags = androidFeatureFlags(fixture);
      List<AndroidFeatureFlag> featureFlagDefinitions = androidFeatureFlagDefinitions(fixture);
      List<String> selectedProducts = androidSelectedProducts(fixture);
      String applicationPackage =
          fixture.has("androidApplicationPackage")
              ? fixture.get("androidApplicationPackage").asText()
              : null;
      if (fixture.has("error")) {
        try {
          parseFixture(
              format,
              bytes,
              propertiesCharset,
              resourcePath,
              featureFlags,
              featureFlagDefinitions,
              selectedProducts,
              applicationPackage);
          fail(id + ": expected stable error " + fixture.get("error").asText());
        } catch (LocalizationParseException exception) {
          assertEquals(id, fixture.get("error").asText(), exception.code());
        }
      } else {
        JsonNode expected = JSON.readTree(root.resolve(fixture.get("expected").asText()).toFile());
        LocalizationCatalog actual =
            parseFixture(
                format,
                bytes,
                propertiesCharset,
                resourcePath,
                featureFlags,
                featureFlagDefinitions,
                selectedProducts,
                applicationPackage);
        assertEquals(id, expected, JSON.valueToTree(actual));
        if (fixture.has("androidNormalized")
            || fixture.has("appleNormalized")
            || fixture.has("appleStringsdictNormalized")
            || fixture.has("xcstringsNormalized")
            || fixture.has("propertiesNormalized")
            || fixture.has("gettextNormalized")) {
          String normalizedFixture =
              fixture.has("androidNormalized")
                  ? fixture.get("androidNormalized").asText()
                  : fixture.has("appleNormalized")
                      ? fixture.get("appleNormalized").asText()
                      : fixture.has("appleStringsdictNormalized")
                          ? fixture.get("appleStringsdictNormalized").asText()
                          : fixture.has("xcstringsNormalized")
                              ? fixture.get("xcstringsNormalized").asText()
                              : fixture.has("propertiesNormalized")
                                  ? fixture.get("propertiesNormalized").asText()
                                  : fixture.get("gettextNormalized").asText();
          String normalized = LocalizationFileConverters.write(format, actual);
          assertEquals(
              id + ": deterministic normalized resource",
              Files.readString(root.resolve(normalizedFixture)),
              normalized);
          LocalizationCatalog repeated =
              parseFixture(
                  format,
                  normalized.getBytes(StandardCharsets.UTF_8),
                  fixture.has("propertiesNormalized") ? StandardCharsets.UTF_8 : propertiesCharset,
                  resourcePath,
                  featureFlags,
                  featureFlagDefinitions,
                  selectedProducts,
                  applicationPackage);
          assertEquals(
              id + ": lossless canonical round trip", expected, JSON.valueToTree(repeated));
          assertEquals(
              id + ": normalized writing is idempotent",
              normalized,
              LocalizationFileConverters.write(format, repeated));
        }
        if (fixture.has("writerReject")) {
          JsonNode rejected = fixture.get("writerReject");
          try {
            LocalizationFileConverters.write(
                LocalizationFileFormat.fromId(rejected.get("format").asText()), actual);
            fail(id + ": expected stable writer error " + rejected.get("error").asText());
          } catch (LocalizationParseException exception) {
            assertEquals(
                id + ": stable writer error", rejected.get("error").asText(), exception.code());
          }
        }
        for (JsonNode mutation : fixture.path("writerMutations")) {
          LocalizationCatalog modified = new LocalizationCatalog(format);
          modified.setLocale(actual.locale());
          for (Map.Entry<String, LocalizationMessage> message : actual.messages().entrySet()) {
            LocalizationMessage descriptor = message.getValue();
            if (message.getKey().equals(mutation.get("message").asText())) {
              descriptor =
                  LocalizationMessage.of(
                      descriptor.defaultMessage(),
                      mutation.has("description")
                          ? mutation.get("description").asText()
                          : descriptor.description(),
                      mutation.has("variants")
                          ? JSON.convertValue(mutation.get("variants"), Map.class)
                          : descriptor.variants(),
                      descriptor.placeholders(),
                      mutation.has("metadata")
                          ? JSON.convertValue(mutation.get("metadata"), Map.class)
                          : descriptor.metadata());
            }
            modified.add(message.getKey(), descriptor);
          }
          try {
            LocalizationFileConverters.write(format, modified);
            fail(id + ": expected stable writer mutation error " + mutation.get("error").asText());
          } catch (LocalizationParseException exception) {
            assertEquals(
                id + ": stable writer mutation error",
                mutation.get("error").asText(),
                exception.code());
          }
        }
      }
      checked++;
    }
    assertEquals(
        "Every language-neutral case must be executed", manifest.get("cases").size(), checked);
  }

  @Test
  public void allSharedSourcePreservingSkeletons() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("sourceSkeletons")) {
      String id = fixture.path("id").asText();
      LocalizationFileFormat format =
          LocalizationFileFormat.fromId(fixture.path("format").asText());
      String encoding = fixture.path("encoding").asText();
      String lineEndings = fixture.path("lineEndings").asText();
      byte[] original = encode(root.resolve(fixture.path("input").asText()), encoding, lineEndings);
      Charset charset =
          "ISO-8859-1".equals(encoding) ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
      List<AndroidFeatureFlag> featureFlags = androidFeatureFlagDefinitions(fixture);
      String resourcePath =
          fixture.has("resourcePath") ? fixture.get("resourcePath").asText() : null;
      if (fixture.path("xcstringsInsertSourceLocale").asBoolean(false)) {
        try {
          LocalizationFileConverters.extractSkeleton(format, original, charset);
          fail(id + ": default Xcode source extraction must preserve fallback rejection");
        } catch (LocalizationParseException expected) {
          assertEquals("UNSUPPORTED_SKELETON_SOURCE", expected.code());
        }
      }
      LocalizationSourceSkeleton skeleton =
          fixture.has("xcstringsTargetLocale")
              ? LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                  original, fixture.get("xcstringsTargetLocale").asText())
              : fixture.path("xcstringsInsertSourceLocale").asBoolean(false)
                  ? LocalizationFileConverters.extractSkeletonWithXcodeSourceInsertion(original)
                  : fixture.path("appleAllVariationSlots").asBoolean(false)
                      ? LocalizationFileConverters.extractSkeletonWithAppleVariations(original)
                      : fixture.path("xcstringsAllDeviceSlots").asBoolean(false)
                          ? LocalizationFileConverters.extractSkeletonWithXcodeDevices(original)
                          : resourcePath != null
                              ? LocalizationFileConverters.extractSkeletonWithAndroidContext(
                                  original,
                                  resourcePath,
                                  featureFlags == null ? List.of() : featureFlags)
                              : featureFlags == null
                                  ? LocalizationFileConverters.extractSkeleton(
                                      format, original, charset)
                                  : LocalizationFileConverters
                                      .extractSkeletonWithAndroidFeatureFlags(
                                          original, featureFlags);
      JsonNode expected = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
      assertEquals(id + ": portable source skeleton", expected, JSON.valueToTree(skeleton));
      assertArrayEquals(
          id + ": untouched source bytes",
          original,
          LocalizationFileConverters.renderSkeleton(skeleton, Map.of()));

      Map<String, String> translations = new LinkedHashMap<>();
      JSON.readTree(root.resolve(fixture.path("translations").asText()).toFile())
          .fields()
          .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
      byte[] actual = LocalizationFileConverters.renderSkeleton(skeleton, translations);
      if (fixture.path("xcstringsTargetDeviceInsertion").asBoolean(false)) {
        JsonNode expectedCatalog =
            JSON.readTree(root.resolve(fixture.path("localized").asText()).toFile());
        JsonNode actualCatalog = JSON.readTree(actual);
        for (String insertedId :
            List.of(
                "harbor.target.device.missing.scalar🧭",
                "harbor.target.device.null.scalar🧭",
                "harbor.target.device.missing.plural🧭",
                "harbor.target.device.null.plural🧭")) {
          assertEquals(
              id + ": native target device tree " + insertedId,
              expectedCatalog.path("strings").path(insertedId).path("localizations").path("ru"),
              actualCatalog.path("strings").path(insertedId).path("localizations").path("ru"));
        }
      }
      assertArrayEquals(
          id + ": source-preserving localized output",
          encode(root.resolve(fixture.path("localized").asText()), encoding, lineEndings),
          actual);
      LocalizationCatalog localized =
          featureFlags == null && resourcePath == null
              ? LocalizationFileConverters.parse(format, actual, charset)
              : LocalizationFileConverters.parseWithAndroidFeatureFlags(
                  format,
                  actual,
                  charset,
                  resourcePath,
                  featureFlags == null ? List.of() : featureFlags,
                  null);
      if (format == LocalizationFileFormat.ANDROID) {
        if (id.startsWith("android-source-skeleton-preserves-literal-hashes")) {
          assertEquals("Quai #7", localized.messages().get("scalar_hash").defaultMessage());
          assertEquals("Quai #8", localized.messages().get("escaped_hash").defaultMessage());
          assertEquals("Quai #9", localized.messages().get("entity_hash").defaultMessage());
          assertEquals("Quai #10", localized.messages().get("unicode_hash").defaultMessage());
          assertEquals(
              "{arg0} #port", localized.messages().get("protected_conventional").defaultMessage());
          assertEquals(
              List.of(Map.of()),
              ((Map<?, ?>)
                      localized
                          .messages()
                          .get("protected_conventional")
                          .metadata()
                          .get("androidProtectedPlaceholderOccurrences"))
                  .get("arg0"));
          List<?> mixedProtection =
              (List<?>)
                  ((Map<?, ?>)
                          localized
                              .messages()
                              .get("mixed_conventional")
                              .metadata()
                              .get("androidProtectedPlaceholderOccurrences"))
                      .get("arg0");
          assertEquals(Map.of(), mixedProtection.getFirst());
          assertNull(mixedProtection.get(1));
          assertEquals(Map.of("example", ""), mixedProtection.get(2));
          assertEquals(
              List.of(Map.of()),
              ((Map<?, ?>)
                      localized
                          .messages()
                          .get("conventional_routes[0]")
                          .metadata()
                          .get("androidProtectedPlaceholderOccurrences"))
                  .get("arg0"));
          Map<?, ?> conventionalCategories =
              (Map<?, ?>)
                  localized
                      .messages()
                      .get("conventional_signals")
                      .metadata()
                      .get("androidPluralProtectedPlaceholderOccurrences");
          assertEquals(
              List.of(Map.of()), ((Map<?, ?>) conventionalCategories.get("one")).get("arg0"));
          List<?> otherProtection =
              (List<?>) ((Map<?, ?>) conventionalCategories.get("other")).get("arg0");
          assertNull(otherProtection.getFirst());
          assertEquals(Map.of(), otherProtection.get(1));
          for (String color :
              List.of("escaped_color", "unicode_color", "quoted_color", "strict_color")) {
            assertEquals("#def", localized.messages().get(color).defaultMessage());
          }
          assertFalse(localized.messages().containsKey("primitive_color"));
          assertFalse(localized.messages().containsKey("mixed_hashes[0]"));
          assertEquals(
              "#abc",
              ((Map<?, ?>)
                      localized
                          .messages()
                          .get("mixed_hashes[1]")
                          .metadata()
                          .get("androidArrayPrimitives"))
                  .get("0"));
          assertEquals("Quai ##4", localized.messages().get("mixed_hashes[3]").defaultMessage());
          assertEquals(
              "{arg0} voie #nord", localized.messages().get("raw_signals").variants().get("one"));
          assertEquals(
              "{arg0} voies ##nord",
              localized.messages().get("raw_signals").variants().get("other"));
          assertEquals(
              "{arg0} '<'b lane=\"#inner\">#bleu'<'/b> voie",
              localized.messages().get("styled_signals").variants().get("one"));
          assertEquals(
              "{arg0} '<'i lane=\"hash#inner\">##bleu'<'/i> voies",
              localized.messages().get("styled_signals").variants().get("other"));
          assertEquals(
              "#1",
              localized.messages().get("protected_signals").placeholders().getFirst().example());
          assertEquals(
              "#2", localized.messages().get("protected_signals").placeholders().get(1).example());
          Map<?, ?> categoryExamples =
              (Map<?, ?>)
                  localized
                      .messages()
                      .get("protected_signals")
                      .metadata()
                      .get("androidPluralPlaceholderExamples");
          assertEquals(List.of("#1"), ((Map<?, ?>) categoryExamples.get("one")).get("count"));
          assertEquals(List.of("#2"), ((Map<?, ?>) categoryExamples.get("other")).get("count"));
          Map<?, ?> optionalExamples =
              (Map<?, ?>)
                  localized
                      .messages()
                      .get("optional_examples")
                      .metadata()
                      .get("androidPluralPlaceholderExamples");
          assertNull(((List<?>) ((Map<?, ?>) optionalExamples.get("one")).get("count")).getFirst());
          assertEquals(List.of("#many"), ((Map<?, ?>) optionalExamples.get("other")).get("count"));
          Map<?, ?> emptyExamples =
              (Map<?, ?>)
                  localized
                      .messages()
                      .get("empty_examples")
                      .metadata()
                      .get("androidPluralPlaceholderExamples");
          assertEquals(List.of(""), ((Map<?, ?>) emptyExamples.get("one")).get("count"));
          assertNull(((List<?>) ((Map<?, ?>) emptyExamples.get("other")).get("count")).getFirst());
          Map<?, ?> repeatedExamples =
              (Map<?, ?>)
                  localized
                      .messages()
                      .get("repeated_examples")
                      .metadata()
                      .get("androidPluralPlaceholderExamples");
          assertEquals(
              List.of("#lead", "#tail"), ((Map<?, ?>) repeatedExamples.get("one")).get("count"));
          assertEquals(
              List.of("#tail", "#lead"), ((Map<?, ?>) repeatedExamples.get("other")).get("count"));
          assertEquals(
              "{count} voies protégées ##nord",
              localized.messages().get("protected_signals").variants().get("other"));
          assertEquals(
              "{arg0} défaut ##signaux",
              localized.messages().get("product_signals").variants().get("other"));
          assertEquals(
              "{arg0} tablette ##signaux",
              localized.messages().get("product_signals@product=tablet").variants().get("other"));
          assertFalse(localized.messages().containsKey("protected_private"));
        } else if (id.startsWith("android-source-skeleton-preserves-xml-attribute-control")) {
          assertEquals("Port calme", localized.messages().get("scalar").defaultMessage());
          assertEquals("North\tdeck\nline\rend", localized.messages().get("scalar").description());
          assertEquals("Voie générale", localized.messages().get("generic").defaultMessage());
          assertEquals("Generic\r\ndeck", localized.messages().get("generic").description());
          assertEquals(
              "A\tB\nC\rD",
              localized.messages().get("protected").placeholders().getFirst().example());
          assertEquals("Route\tdeck", localized.messages().get("routes[0]").description());
          assertEquals(
              "P\r\n7", localized.messages().get("routes[1]").placeholders().getFirst().example());
          assertEquals("Count\tlights\nvisible", localized.messages().get("signals").description());
          assertEquals(
              "M\t1\n2\r3",
              localized.messages().get("signals").placeholders().getFirst().example());
          assertEquals(
              "'<'annotation route=\"north\tmiddle\nend\rfinish\">Style visible'<'/annotation>",
              localized.messages().get("styled").defaultMessage());
          assertFalse(localized.messages().containsKey("untouched"));
        } else if (id.startsWith("android-source-skeleton-preserves-whitespace-bearing")) {
          assertEquals("Un port calme", localized.messages().get("visible").defaultMessage());
          assertEquals(
              "Référence @ préservée", localized.messages().get("quoted").defaultMessage());
          assertEquals(
              "Référence @ échappée", localized.messages().get("escaped").defaultMessage());
          assertEquals(
              "Route visible traduite", localized.messages().get("routes[6]").defaultMessage());
          assertEquals(
              "{arg0} lumières visibles",
              localized.messages().get("signals").variants().get("other"));
          for (String reference : List.of("space", "line", "carriage", "double_tab", "untouched")) {
            assertFalse(localized.messages().containsKey(reference));
          }
          Map<?, ?> arrayReferences =
              (Map<?, ?>)
                  localized.messages().get("routes[6]").metadata().get("androidArrayReferences");
          assertEquals("@string/north pier", arrayReferences.get("0"));
          assertEquals("@string/north\tpier", arrayReferences.get("1"));
          assertEquals("@string/north\npier", arrayReferences.get("2"));
          assertEquals("@string/north\rpier", arrayReferences.get("3"));
          assertEquals("@string/north\r\npier", arrayReferences.get("4"));
          Map<?, ?> pluralReferences =
              (Map<?, ?>)
                  localized.messages().get("signals").metadata().get("androidPluralReferences");
          assertEquals("@string/north\rpier", pluralReferences.get("zero"));
          assertEquals("@string/north\tpier", pluralReferences.get("two"));
          assertEquals("@string/north\npier", pluralReferences.get("few"));
        } else if (id.startsWith("android-source-skeleton-preserves-doubled-resource-aliases")) {
          assertEquals("Abri calme", localized.messages().get("anchor").defaultMessage());
          assertEquals("Texte @@ protégé", localized.messages().get("escaped").defaultMessage());
          assertEquals("Texte @@ cité", localized.messages().get("quoted").defaultMessage());
          assertEquals("Texte @@@ distinct", localized.messages().get("triple").defaultMessage());
          assertEquals(
              "Instruction directe traduite",
              localized.messages().get("single_macro").defaultMessage());
          assertEquals(
              "Instruction indirecte traduite",
              localized.messages().get("double_macro").defaultMessage());
          assertEquals(
              "Route macro traduite", localized.messages().get("routes[2]").defaultMessage());
          assertEquals(
              "Route visible traduite", localized.messages().get("routes[3]").defaultMessage());
          assertEquals(
              "Deux instructions traduites",
              localized.messages().get("signals").variants().get("two"));
          assertEquals(
              "{arg0} signaux traduits",
              localized.messages().get("signals").variants().get("other"));
          for (String reference :
              List.of(
                  "single_alias", "double_alias", "generic_alias", "double_comment", "untouched")) {
            assertFalse(localized.messages().containsKey(reference));
          }
          assertEquals(
              "@@string/anchor",
              ((Map<?, ?>)
                      localized
                          .messages()
                          .get("routes[2]")
                          .metadata()
                          .get("androidArrayReferences"))
                  .get("1"));
          assertEquals(
              "@@string/anchor",
              ((Map<?, ?>)
                      localized.messages().get("signals").metadata().get("androidPluralReferences"))
                  .get("one"));
        } else if (id.startsWith("android-source-skeleton-preserves-literal-quotes")) {
          assertEquals(
              "Quai \"nord\" et \"calme\"", localized.messages().get("scalar").defaultMessage());
          assertEquals(
              "Port '<'b lane=\"harbor&quot;side\">\"doux\"'<'/b> sûr",
              localized.messages().get("styled").defaultMessage());
          assertEquals(
              "Pilote {pilot} vers \"nord\"",
              localized.messages().get("protected").defaultMessage());
          assertEquals(
              "Port \"bleu\" calme", localized.messages().get("commented").defaultMessage());
          assertEquals(
              "Quai \"ouest, calme\"", localized.messages().get("routes[0]").defaultMessage());
          assertEquals(
              "Port '<'i lane=\"water&quot;side\">\"stable\"'<'/i> sûr",
              localized.messages().get("routes[1]").defaultMessage());
          assertEquals(
              "Une \"lumière\" sûre", localized.messages().get("signals").variants().get("one"));
          assertEquals(
              "{arg0} <b>\"lumières\"</b> sûres",
              localized.messages().get("signals").variants().get("other"));
          assertFalse(localized.messages().containsKey("untouched"));
        } else if (fixture.path("androidDecoratedInline").asBoolean(false)) {
          assertEquals(
              translations.get("protected_comments"),
              localized.messages().get("protected_comments").defaultMessage());
          assertEquals(
              translations.get("routes[1]"),
              localized.messages().get("routes[1]").defaultMessage());
          for (String message :
              List.of("styled_comments", "nested_comments", "comment_only", "routes[0]")) {
            String canonical = localized.messages().get(message).defaultMessage();
            assertTrue(
                id + ": decorated italic span precedes bold span",
                canonical.indexOf("'<'i") < canonical.indexOf("'<'b"));
          }
          for (String quantity : List.of("one", "other")) {
            String canonical = localized.messages().get("signals").variants().get(quantity);
            assertTrue(canonical.indexOf("'<'i") < canonical.indexOf("'<'b"));
            assertTrue(canonical.contains("{arg0} nord"));
          }
          assertFalse(localized.messages().containsKey("private_route"));
        } else if (fixture.path("androidReorderableInline").asBoolean(false)) {
          assertEquals(
              translations.get("protected_route"),
              localized.messages().get("protected_route").defaultMessage());
          assertEquals(
              translations.get("routes[1]"),
              localized.messages().get("routes[1]").defaultMessage());
          for (String message :
              List.of("styled_route", "nested_route", "mixed_route", "routes[0]")) {
            String canonical = localized.messages().get(message).defaultMessage();
            assertTrue(
                id + ": reordered italic span precedes bold span",
                canonical.indexOf("'<'i") < canonical.indexOf("'<'b"));
          }
          String repeated = localized.messages().get("repeated_style").defaultMessage();
          assertTrue(repeated.indexOf("lane=\"south\"") < repeated.indexOf("lane=\"north\""));
          for (String quantity : List.of("one", "other")) {
            String canonical = localized.messages().get("signals").variants().get(quantity);
            assertTrue(canonical.indexOf("'<'i") < canonical.indexOf("'<'b"));
            assertTrue(canonical.contains("{arg0} nord"));
          }
          assertFalse(localized.messages().containsKey("private_route"));
        } else if (id.contains("read-only-items") || id.contains("array-item-ownership")) {
          assertEquals("Ancre sûre", localized.messages().get("anchor").defaultMessage());
          assertEquals("Route nord visible", localized.messages().get("lanes[0]").defaultMessage());
          assertEquals("Route est mutable", localized.messages().get("lanes[1]").defaultMessage());
          assertEquals(
              "Inverse <ouest> & sûr", localized.messages().get("lanes[2]").defaultMessage());
          assertEquals("Route sud ouverte", localized.messages().get("lanes[4]").defaultMessage());
          assertEquals("Route vide ajoutée", localized.messages().get("lanes[5]").defaultMessage());
          assertEquals(
              "Route tablette visible",
              localized.messages().get("lanes@product=tablet[0]").defaultMessage());
          assertEquals(
              "Route tablette inverse",
              localized.messages().get("lanes@product=tablet[1]").defaultMessage());
          assertEquals("Cargaison mutable", localized.messages().get("cargo[1]").defaultMessage());
          assertEquals("Cargaison inverse", localized.messages().get("cargo[3]").defaultMessage());
          assertEquals("Dernière cargaison", localized.messages().get("cargo[5]").defaultMessage());
          assertEquals("Sac mutable", localized.messages().get("bag.lanes[0]").defaultMessage());
          assertEquals("Sac ouvert", localized.messages().get("bag.lanes[1]").defaultMessage());
          assertEquals(
              "{arg0} signal calme", localized.messages().get("signals").variants().get("one"));
          assertEquals(
              Map.of(
                  "0", "neutral.flags.visible",
                  "1", "neutral.flags.mutable",
                  "2", "!neutral.flags.mutable",
                  "3", "neutral.flags.visible",
                  "5", "neutral.flags.mutable"),
              localized.messages().get("lanes[1]").metadata().get("androidArrayFeatureFlags"));
          assertEquals(
              Map.of("1", "read_write", "2", "read_write", "5", "read_write"),
              localized.messages().get("lanes[1]").metadata().get("androidArrayFeatureFlagModes"));
          assertFalse(localized.messages().containsKey("private"));
        } else if (id.contains("values-directory-locale") || id.contains("gated-directory")) {
          String suffix = id.contains("gated-directory") ? "@flag=neutral.flags.path" : "";
          assertEquals("fr", localized.locale());
          assertEquals(
              "Ancre française",
              localized.messages().get("anchor.route" + suffix).defaultMessage());
          assertEquals(
              "Passage français", localized.messages().get("route" + suffix).defaultMessage());
          assertEquals(
              "Passage tablette français",
              localized.messages().get("route@product=tablet" + suffix).defaultMessage());
          assertEquals(
              "Balise française", localized.messages().get("beacon" + suffix).defaultMessage());
          assertEquals(
              "Nord <clair> & sûr",
              localized.messages().get("lanes" + suffix + "[0]").defaultMessage());
          assertEquals(
              "Sud français", localized.messages().get("lanes" + suffix + "[2]").defaultMessage());
          assertEquals(
              "{arg0} signal français",
              localized.messages().get("signals" + suffix).variants().get("one"));
          assertFalse(localized.messages().containsKey("private" + suffix));
        } else if (id.contains("read-write-feature-flag-alternatives")) {
          assertEquals("Ancre ouverte", localized.messages().get("anchor").defaultMessage());
          assertEquals("Passage ouvert", localized.messages().get("gate.route").defaultMessage());
          assertEquals(
              "Premier passage mutable",
              localized.messages().get("gate.route@flag=neutral.flags.first").defaultMessage());
          assertEquals(
              "Inverse <calme> & sûr",
              localized.messages().get("gate.route@flag=!neutral.flags.first").defaultMessage());
          assertEquals(
              "Passage tablette mutable",
              localized
                  .messages()
                  .get("gate.route@product=tablet@flag=neutral.flags.second")
                  .defaultMessage());
          assertEquals(
              "Signal premier mutable",
              localized.messages().get("gate.signal@flag=neutral.flags.first").defaultMessage());
          assertEquals(
              "{arg0} balise mutable",
              localized
                  .messages()
                  .get("gate.count@flag=neutral.flags.first")
                  .variants()
                  .get("one"));
          assertEquals(
              "Voie <nord> mutable",
              localized.messages().get("gate.lanes@flag=neutral.flags.second[0]").defaultMessage());
          assertFalse(localized.messages().containsKey("gate.disabled"));
        } else if (id.startsWith("android-source-skeleton-preserves-default-and-product")
            || id.startsWith("android-source-skeleton-preserves-product-identity")) {
          assertEquals("Ancre sûre", localized.messages().get("anchor").defaultMessage());
          assertEquals(
              "Passage commun sûr", localized.messages().get("harbor.route").defaultMessage());
          assertEquals(
              "Passage tablette & calme",
              localized.messages().get("harbor.route@product=tablet").defaultMessage());
          assertEquals(
              "Montre <calme> & claire",
              localized.messages().get("harbor.route@product=watch").defaultMessage());
          assertEquals(
              "Signal tablette & prêt",
              localized.messages().get("harbor.signal@product=tablet").defaultMessage());
          assertEquals(
              "Tablette <nord> & sûre",
              localized.messages().get("harbor.lanes@product=tablet[0]").defaultMessage());
          assertEquals(
              "Tablette sud douce",
              localized.messages().get("harbor.lanes@product=tablet[2]").defaultMessage());
          assertEquals(
              "{arg0} balise commune",
              localized.messages().get("tide.count").variants().get("one"));
          assertEquals(
              "{arg0} balise tablette",
              localized.messages().get("tide.count@product=tablet").variants().get("one"));
        } else if (id.startsWith(
                "android-source-skeleton-preserves-validated-nontranslatable-resources")
            || id.startsWith(
                "android-source-skeleton-preserves-transparent-protected-namespaces")) {
          assertEquals(
              translations.get("visible"), localized.messages().get("visible").defaultMessage());
          assertEquals(
              "Protected resources must remain untranslatable", 1, localized.messages().size());
        } else if (id.startsWith(
            "android-source-skeleton-preserves-namespaced-inline-attributes")) {
          assertEquals("Nord ouest", localized.messages().get("scalar_space").defaultMessage());
          assertEquals(
              "Nord est", localized.messages().get("scalar_default_space").defaultMessage());
          assertEquals(
              "'<'font color=\"#112233\">Lumière du port'<'/font>",
              localized.messages().get("routes[1]").defaultMessage());
          assertEquals(
              "'<'font size=\"9\">{arg0} balise du port'<'/font>",
              localized.messages().get("signals").variants().get("one"));
          assertEquals(
              "'<'annotation key=\"beacon\">{arg0} balises du port'<'/annotation>",
              localized.messages().get("signals").variants().get("other"));
        } else if (id.contains("portable-xml-encoding-boundary")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-xml-long-declaration")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-xml-name-boundary")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-xml-legacy-name")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-android-intrinsic-xml-namespace")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
          assertFalse(localized.messages().containsKey("hidden"));
        } else if (id.contains("portable-android-bomless-utf16")) {
          assertEquals("Côte sûre", localized.messages().get("signal").defaultMessage());
          assertEquals("Rive 🚢 calme", localized.messages().get("route").defaultMessage());
        } else if (id.startsWith("android-source-skeleton-preserves-native-unicode-whitespace")) {
          assertEquals(
              "sud\u2003 ouest", localized.messages().get("entity_em_space").defaultMessage());
          assertEquals(
              "sud\u00a0 ouest", localized.messages().get("escaped_no_break").defaultMessage());
          assertEquals(
              "quai & \u2029 ouest \u2029",
              localized.messages().get("boundary_paragraph_separator").defaultMessage());
          assertEquals(
              "  \u2003   ouest \u00a0  ",
              localized.messages().get("quoted_unicode").defaultMessage());
        } else if (id.startsWith("android-source-skeleton-preserves-processing-instructions-")) {
          assertEquals("Quiet marina", localized.messages().get("plain").defaultMessage());
          assertEquals("Clear inlet", localized.messages().get("mixed").defaultMessage());
          assertEquals("South  bay", localized.messages().get("quoted").defaultMessage());
          assertEquals("Beacon", localized.messages().get("escaped").defaultMessage());
          assertEquals("Welcome {pilot}.", localized.messages().get("pilot").defaultMessage());
          assertEquals("Inner quay", localized.messages().get("routes[0]").defaultMessage());
          assertEquals("Outer quay", localized.messages().get("routes[1]").defaultMessage());
          assertEquals("{arg0} beacon", localized.messages().get("signals").variants().get("one"));
          assertEquals(
              "{arg0} beacons", localized.messages().get("signals").variants().get("other"));
        } else if (id.startsWith(
            "android-source-skeleton-preserves-well-formed-document-envelope-")) {
          assertEquals("Quai nord", localized.messages().get("route").defaultMessage());
          assertEquals("Havre sud", localized.messages().get("bay").defaultMessage());
        } else if (id.startsWith(
            "android-source-skeleton-preserves-safe-xml11-character-boundary-")) {
          assertEquals("Havre nord", localized.messages().get("signal").defaultMessage());
        } else if (id.startsWith("android-source-skeleton-preserves-empty-elements")) {
          assertEquals("Nouveau & quai", localized.messages().get("empty_scalar").defaultMessage());
          assertEquals(
              "Voie douce & claire", localized.messages().get("empty_generic").defaultMessage());
          assertEquals("Sud abri", localized.messages().get("commented").defaultMessage());
          assertEquals("Port ]]> <doux> & sûr", localized.messages().get("cdata").defaultMessage());
          assertEquals(
              "Sud <doux> & sûr quai", localized.messages().get("cdata_split").defaultMessage());
          assertEquals("Première route", localized.messages().get("routes[0]").defaultMessage());
          assertEquals("Ouest calme", localized.messages().get("routes[1]").defaultMessage());
          assertEquals(
              "Signal <bleu> & doux", localized.messages().get("routes[2]").defaultMessage());
          assertEquals("{arg0} visite", localized.messages().get("visits").variants().get("one"));
          assertEquals(
              "{arg0} visites", localized.messages().get("visits").variants().get("other"));
        } else if (id.equals(
            "android-source-skeleton-preserves-entity-escaped-unicode-resource-identities")) {
          assertEquals("Canal discret", localized.messages().get("_route").defaultMessage());
          assertEquals("Lumière côtière", localized.messages().get("Éclat").defaultMessage());
          assertEquals("Écho du port", localized.messages().get("e\u0301cho").defaultMessage());
          assertEquals("Route du nord", localized.messages().get("route·north").defaultMessage());
          assertEquals(
              "Voie générique", localized.messages().get("generic·route").defaultMessage());
          assertEquals("Passage côtier", localized.messages().get("渡り.route-2").defaultMessage());
        } else if (id.equals(
            "android-source-skeleton-preserves-whitespace-padded-plural-quantity-entities")) {
          assertEquals("Signal visible", localized.messages().get("signals").variants().get("one"));
          assertEquals(
              "Signaux visibles", localized.messages().get("signals").variants().get("other"));
        } else {
          assertEquals("Quai & abri sûr", localized.messages().get("harbor").defaultMessage());
          assertEquals("Quai ouest", localized.messages().get("routes[2]").defaultMessage());
          assertEquals(
              "{arg0} visites", localized.messages().get("visits").variants().get("other"));
        }
      } else if (format == LocalizationFileFormat.APPLE_STRINGSDICT) {
        if (fixture.path("appleStringsdictHiddenArgumentSlots").asBoolean(false)) {
          assertEquals(
              "{count} {arg2} balise",
              localized.messages().get("harbor.after").variants().get("one"));
          assertEquals(
              "{count} {arg3} balises",
              localized.messages().get("harbor.repeated").variants().get("other"));
          assertEquals(
              "{count}%n {arg1} balise",
              localized.messages().get("harbor.escaped").variants().get("one"));
          assertEquals(
              2,
              ((Map<?, ?>)
                      ((List<?>)
                              ((Map<?, ?>)
                                      ((Map<?, ?>)
                                              localized
                                                  .messages()
                                                  .get("harbor.after")
                                                  .metadata()
                                                  .get("applePluralDisabledPrintfConversions"))
                                          .get("count"))
                                  .get("one"))
                          .getFirst())
                  .get("argumentPosition"));
        } else if (fixture.path("appleDeviceHiddenArgumentSlots").asBoolean(false)) {
          LocalizationMessage message = localized.messages().get("device.after🧭");
          assertEquals("{count} {arg2} mobile repère", message.variants().get("one"));
          assertEquals(
              "{count} {arg3} mobile repères",
              localized.messages().get("device.repeated").variants().get("other"));
          assertEquals(
              "{count}%n {arg1} mobile repère",
              localized.messages().get("device.escaped").variants().get("one"));
          Map<?, ?> devices = (Map<?, ?>) message.metadata().get("devicePluralVariants");
          assertEquals(
              "%lld%n %@ bureau repère",
              ((Map<?, ?>) ((Map<?, ?>) devices.get("mac")).get("count")).get("one"));
          assertEquals(
              2,
              ((Map<?, ?>)
                      ((List<?>)
                              ((Map<?, ?>)
                                      ((Map<?, ?>)
                                              message
                                                  .metadata()
                                                  .get("applePluralDisabledPrintfConversions"))
                                          .get("count"))
                                  .get("one"))
                          .getFirst())
                  .get("argumentPosition"));
        } else if (fixture.path("appleWidthHiddenArgumentSlots").asBoolean(false)) {
          LocalizationMessage standalone = localized.messages().get("width.after🧭");
          assertEquals(" {arg1} large repère", standalone.defaultMessage());
          assertEquals("040", standalone.metadata().get("defaultWidthKey"));
          assertEquals(
              1,
              ((Map<?, ?>)
                      ((List<?>) standalone.metadata().get("appleDisabledPrintfConversions"))
                          .getFirst())
                  .get("argumentPosition"));
          assertEquals(
              "{arg0}  {arg2} mobile large repère",
              localized.messages().get("device.middle").defaultMessage());
          assertEquals(
              "%n {arg0} large repère", localized.messages().get("width.escaped").defaultMessage());
          Map<?, ?> devices =
              (Map<?, ?>)
                  localized.messages().get("device.after🧭").metadata().get("deviceWidthVariants");
          Map<?, ?> widths =
              (Map<?, ?>) ((Map<?, ?>) devices.get("mac")).get("NSStringVariableWidthRuleType");
          assertEquals("%n %@ bureau proche repère", widths.get("5"));
          assertEquals("%n %@ bureau large repère", widths.get("040"));
        } else if (fixture.path("appleDeviceWidthSlots").asBoolean(false)
            && fixture.path("appleDevicePluralSlots").asBoolean(false)) {
          LocalizationMessage scalarPlural = localized.messages().get("neutral.scalar-plural🧭");
          assertEquals("Touchez & la rive", scalarPlural.defaultMessage());
          Map<?, ?> mixedPlural = (Map<?, ?>) scalarPlural.metadata().get("deviceMixedVariants");
          Map<?, ?> desktop = (Map<?, ?>) mixedPlural.get("mac");
          Map<?, ?> lights = (Map<?, ?>) desktop.get("lights");
          assertEquals("%lld balise bureau", lights.get("one"));
          assertEquals("%lld balises bureau", lights.get("other"));
          LocalizationMessage pluralScalar = localized.messages().get("neutral.plural-scalar🧭");
          assertEquals("{lights} lampe mobile", pluralScalar.variants().get("one"));
          Map<?, ?> reversePlural = (Map<?, ?>) pluralScalar.metadata().get("deviceMixedVariants");
          assertEquals("Cliquez sur la jetée", reversePlural.get("mac"));
          LocalizationMessage scalarWidth = localized.messages().get("neutral.scalar-width🧭");
          Map<?, ?> mixedWidths = (Map<?, ?>) scalarWidth.metadata().get("deviceMixedVariants");
          Map<?, ?> desktopWidths =
              (Map<?, ?>) ((Map<?, ?>) mixedWidths.get("mac")).get("NSStringVariableWidthRuleType");
          assertEquals("Sud%n doux", desktopWidths.get("5"));
          assertEquals("Sud\nouest calme", desktopWidths.get("040"));
          LocalizationMessage widthScalar = localized.messages().get("neutral.width-scalar🧭");
          assertEquals("Nord%n vaste rive", widthScalar.defaultMessage());
          Map<?, ?> reverseWidths = (Map<?, ?>) widthScalar.metadata().get("deviceMixedVariants");
          assertEquals("Cliquez sur le port", reverseWidths.get("mac"));
          LocalizationMessage pluralWidth = localized.messages().get("neutral.plural-width🧭");
          assertEquals("{lights} signal mobile", pluralWidth.variants().get("one"));
          Map<?, ?> heterogeneous = (Map<?, ?>) pluralWidth.metadata().get("deviceMixedVariants");
          Map<?, ?> westWidths =
              (Map<?, ?>)
                  ((Map<?, ?>) heterogeneous.get("mac")).get("NSStringVariableWidthRuleType");
          assertEquals("Nord%n quai", westWidths.get("5"));
          assertEquals("Sud\nrive ouverte", westWidths.get("040"));
          LocalizationMessage widthPlural = localized.messages().get("neutral.width-plural🧭");
          assertEquals("Nord%n baie vaste", widthPlural.defaultMessage());
          Map<?, ?> reverseHeterogeneous =
              (Map<?, ?>) widthPlural.metadata().get("deviceMixedVariants");
          Map<?, ?> desktopLights =
              (Map<?, ?>) ((Map<?, ?>) reverseHeterogeneous.get("mac")).get("lights");
          assertEquals("%lld bouée bureau", desktopLights.get("one"));
          assertEquals("%lld bouées bureau", desktopLights.get("other"));
          LocalizationMessage allShapes = localized.messages().get("neutral.three-shapes🧭");
          Map<?, ?> allBranches = (Map<?, ?>) allShapes.metadata().get("deviceMixedVariants");
          assertEquals("Port de repli", allBranches.get("other"));
        } else if (fixture.path("appleDeviceWidthSlots").asBoolean(false)) {
          LocalizationMessage message = localized.messages().get("neutral.width🧭");
          assertEquals("Sud%n vaste rive", message.defaultMessage());
          assertEquals("040", message.metadata().get("defaultWidthKey"));
          Map<?, ?> devices = (Map<?, ?>) message.metadata().get("deviceWidthVariants");
          Map<?, ?> mac = (Map<?, ?>) devices.get("mac");
          Map<?, ?> widths = (Map<?, ?>) mac.get("NSStringVariableWidthRuleType");
          assertEquals("Sud%n doux", widths.get("5"));
          assertEquals("Sud\nouest calme", widths.get("040"));
        } else if (fixture.path("appleDevicePluralSlots").asBoolean(false)) {
          LocalizationMessage message = localized.messages().get("neutral.harbor🧭");
          assertEquals("{lights} lanterne mobile", message.variants().get("one"));
          assertEquals("{lights} lanternes mobiles", message.variants().get("other"));
          Map<?, ?> devices = (Map<?, ?>) message.metadata().get("devicePluralVariants");
          Map<?, ?> mac = (Map<?, ?>) devices.get("mac");
          Map<?, ?> lights = (Map<?, ?>) mac.get("lights");
          assertEquals("%lld balise bureau", lights.get("one"));
          assertEquals("%lld balises bureau", lights.get("other"));
        } else if (fixture.path("appleAllVariationSlots").asBoolean(false)) {
          assertEquals(
              "Touchez {arg0} quai tranquille",
              localized.messages().get("harbor.device.🧭").defaultMessage());
          assertEquals(
              "Touchez {arg0}%n quai",
              localized.messages().get("harbor.device.literal").defaultMessage());
          assertEquals(
              "Suivez la vaste rive claire",
              localized.messages().get("harbor.width").defaultMessage());
          assertEquals(
              "Eau\ncalme devant", localized.messages().get("harbor.width.line").defaultMessage());
          assertEquals(
              "B%n baie calme", localized.messages().get("harbor.width.literal").defaultMessage());
          Map<?, ?> devices =
              (Map<?, ?>)
                  localized.messages().get("harbor.device.🧭").metadata().get("deviceVariants");
          assertEquals("Cliquez %@%n quai profond", devices.get("mac"));
          Map<?, ?> widths =
              (Map<?, ?>) localized.messages().get("harbor.width").metadata().get("widthVariants");
          assertEquals("Suivez%n%n la rive calme", widths.get("040"));
        } else if (id.contains("device-and-width-owned-disabled-printf")) {
          assertEquals(
              "Touchez {arg0} quai tranquille",
              localized.messages().get("harbor.device.🧭").defaultMessage());
          assertEquals(
              "Touchez {arg0}%n quai",
              localized.messages().get("harbor.device.literal").defaultMessage());
          assertEquals(
              "Suivez la vaste rive claire",
              localized.messages().get("harbor.width").defaultMessage());
          assertEquals(
              "Eau\ncalme devant", localized.messages().get("harbor.width.line").defaultMessage());
          assertEquals(
              "A%n baie calme", localized.messages().get("harbor.width.literal").defaultMessage());
        } else if (id.contains("disabled-printf")) {
          assertEquals(
              "{count} balise", localized.messages().get("harbor.after").variants().get("one"));
          assertEquals(
              "{count} balises",
              localized.messages().get("harbor.repeated").variants().get("other"));
          assertEquals(
              "{count}%n balises",
              localized.messages().get("harbor.literal").variants().get("other"));
          assertEquals(
              "{count}\nbalises",
              localized.messages().get("harbor.mixed.🧭").variants().get("other"));
        } else if (id.contains("character-reference")) {
          assertEquals(
              "{signals} balise claire",
              localized.messages().get("dock.entities").variants().get("one"));
          assertEquals(
              "{signals} balises claires",
              localized.messages().get("dock.entities").variants().get("other"));
          Map<?, ?> extras =
              (Map<?, ?>)
                  localized.messages().get("dock.entities").metadata().get("applePlistExtras");
          assertEquals("Protected &#00000000065;", extras.get("futureLiteral"));
          assertEquals(List.of(true, false), extras.get("futureFlags"));
          assertEquals("3ff8000000000000", ((Map<?, ?>) extras.get("futureRatio")).get("bits"));
          Map<?, ?> rules =
              (Map<?, ?>)
                  localized.messages().get("dock.entities").metadata().get("applePluralRules");
          Map<?, ?> ruleExtras =
              (Map<?, ?>) ((Map<?, ?>) rules.get("signals")).get("applePlistExtras");
          assertEquals(
              "3ff4000000000000", ((Map<?, ?>) ruleExtras.get("futureRuleRatio")).get("bits"));
        } else if (id.contains("namespaces")) {
          assertEquals(
              "{signals} balise calme",
              localized.messages().get("dock.namespace").variants().get("one"));
          assertEquals(
              "{signals} balises calmes",
              localized.messages().get("dock.namespace").variants().get("other"));
          Map<?, ?> extras =
              (Map<?, ?>)
                  localized.messages().get("dock.namespace").metadata().get("applePlistExtras");
          assertEquals(7, extras.get("futurePriority"));
          assertEquals(List.of(true, false, "Protected & stable"), extras.get("futureFlags"));
          Map<?, ?> rules =
              (Map<?, ?>)
                  localized.messages().get("dock.namespace").metadata().get("applePluralRules");
          assertEquals(
              11,
              ((Map<?, ?>) ((Map<?, ?>) rules.get("signals")).get("applePlistExtras"))
                  .get("futureRulePriority"));
        } else if (id.contains("empty-typed")) {
          assertEquals(
              "{signals} balise visible",
              localized.messages().get("dock.empty").variants().get("one"));
          assertEquals(
              "{signals} balises visibles",
              localized.messages().get("dock.empty").variants().get("other"));
          Map<?, ?> extras =
              (Map<?, ?>) localized.messages().get("dock.empty").metadata().get("applePlistExtras");
          assertEquals("", ((Map<?, ?>) extras.get("futureEmptyData")).get("base64"));
          assertEquals("", ((Map<?, ?>) extras.get("futureWhitespaceData")).get("base64"));
          assertEquals("YQ==", ((Map<?, ?>) extras.get("futurePayload")).get("base64"));
          assertEquals("Literal <data/> remains visible", extras.get("futureLiteralMarker"));
          assertEquals(List.of(), extras.get("futureEmptyArray"));
          assertEquals(Map.of(), extras.get("futureEmptyDictionary"));
          assertEquals(Boolean.TRUE, extras.get("futureTrue"));
          assertEquals(Boolean.FALSE, extras.get("futureFalse"));
        } else if (id.contains("strict-scalar")) {
          assertEquals(
              "{signals} balise claire",
              localized.messages().get("dock&signals").variants().get("one"));
          assertEquals(
              "{signals} balises claires",
              localized.messages().get("dock&signals").variants().get("other"));
          Map<?, ?> extras =
              (Map<?, ?>)
                  localized.messages().get("dock&signals").metadata().get("applePlistExtras");
          assertEquals(
              List.of(true, false, "Protected <north> & steady", 7), extras.get("futureFlags"));
          assertEquals("3ff8000000000000", ((Map<?, ?>) extras.get("futureRatio")).get("bits"));
        } else if (id.contains("processing-instructions")) {
          assertEquals(
              "{signals} balise sûre",
              localized.messages().get("dock.signals").variants().get("one"));
          assertEquals(
              "{signals} balises sûres",
              localized.messages().get("dock.signals").variants().get("other"));
          assertEquals(
              7,
              ((Map<?, ?>)
                      localized.messages().get("dock.signals").metadata().get("applePlistExtras"))
                  .get("futureRulePriority"));
        } else {
          assertEquals(
              "Aucun signal & sûr",
              localized.messages().get("dock&count🧭").variants().get("zero"));
          assertEquals(
              "{signals} lueur ]]> & sûre",
              localized.messages().get("dock&count🧭").variants().get("one"));
          assertEquals(
              "{signals} signaux & clairs",
              localized.messages().get("dock&count🧭").variants().get("other"));
          assertEquals(
              "{lights} lueur", localized.messages().get("solo.count").variants().get("one"));
          assertEquals(
              "{lights} lueurs", localized.messages().get("solo.count").variants().get("other"));
          assertEquals(
              "{beacons, plural, one {{beacons} balise sûre} other {{beacons} balises sûres}}"
                  + " across {lanes, plural, one {{lanes} voie claire}"
                  + " other {{lanes} voies claires}}",
              localized.messages().get("paired.route").defaultMessage());
          assertEquals(
              "Large {arg0} & calme", localized.messages().get("width.route").defaultMessage());
          assertEquals(
              "Touchez {arg0} & continuez",
              localized.messages().get("device.route").defaultMessage());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsTargetSubstitutionSlots").asBoolean(false)) {
        assertEquals("ru", skeleton.appleTargetLocale());
        LocalizationMessage scalar =
            localized.messages().get("harbor.target.substitution.scalar🧭");
        Map<?, ?> scalarTarget =
            (Map<?, ?>) ((Map<?, ?>) scalar.metadata().get("appleLocalizationSources")).get("ru");
        assertEquals(
            "Маршрут %3$@: %2$#@lights@ впереди %#@lanes@",
            ((Map<?, ?>) scalarTarget.get("stringUnit")).get("value"));
        assertEquals("future_review", ((Map<?, ?>) scalarTarget.get("stringUnit")).get("state"));

        Map<?, ?> selectors = (Map<?, ?>) scalarTarget.get("substitutions");
        assertEquals(Set.of("lanes", "lights"), selectors.keySet());
        Map<?, ?> lanes = (Map<?, ?>) selectors.get("lanes");
        assertEquals(1, lanes.get("argNum"));
        assertEquals("lld", lanes.get("formatSpecifier"));
        Map<?, ?> laneCategories = (Map<?, ?>) ((Map<?, ?>) lanes.get("variations")).get("plural");
        assertEquals(Set.of("one", "few", "many", "other"), laneCategories.keySet());
        assertEquals(
            "%1$lld %4$n обновлённый полосы",
            ((Map<?, ?>) ((Map<?, ?>) laneCategories.get("few")).get("stringUnit")).get("value"));
        assertEquals(
            "new",
            ((Map<?, ?>) ((Map<?, ?>) laneCategories.get("few")).get("stringUnit")).get("state"));

        LocalizationMessage device =
            localized.messages().get("harbor.target.substitution.device🧭");
        Map<?, ?> target =
            (Map<?, ?>) ((Map<?, ?>) device.metadata().get("appleLocalizationSources")).get("ru");
        Map<?, ?> devices = (Map<?, ?>) ((Map<?, ?>) target.get("variations")).get("device");
        assertEquals(
            "Рабочий стол %3$@: %#@lanes@ после %2$#@lights@",
            ((Map<?, ?>) ((Map<?, ?>) devices.get("mac")).get("stringUnit")).get("value"));
        assertEquals(
            "future_review",
            ((Map<?, ?>) ((Map<?, ?>) devices.get("mac")).get("stringUnit")).get("state"));
        Map<?, ?> lights = (Map<?, ?>) ((Map<?, ?>) target.get("substitutions")).get("lights");
        Map<?, ?> lightCategories =
            (Map<?, ?>) ((Map<?, ?>) lights.get("variations")).get("plural");
        assertEquals(
            "%2$d %4$n обновлённый огней",
            ((Map<?, ?>) ((Map<?, ?>) lightCategories.get("many")).get("stringUnit")).get("value"));
        assertEquals(
            "future_review",
            ((Map<?, ?>) ((Map<?, ?>) lightCategories.get("many")).get("stringUnit")).get("state"));
        assertFalse(
            localized.messages().containsKey("Private target Russian substitution branches"));
        if (fixture.has("xcstringsSourceAliasTargetSubstitutions")) {
          JsonNode source = JSON.readTree(skeleton.source());
          String declared = source.path("sourceLanguage").asText();
          String owned = scalar.metadata().get("appleSourceLocalizationIdentifier").toString();
          assertNotEquals(declared, owned);
          assertEquals(owned, device.metadata().get("appleSourceLocalizationIdentifier"));
          assertFalse(((Map<?, ?>) scalar.metadata().get("localizations")).containsKey(owned));
          JsonNode normalized =
              JSON.readTree(
                  LocalizationFileConverters.write(
                      LocalizationFileFormat.APPLE_XCSTRINGS, localized));
          assertTrue(
              normalized
                  .path("strings")
                  .path("harbor.target.substitution.device🧭")
                  .path("localizations")
                  .has(owned));
          try {
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(original, owned);
            fail(id + ": compiler-equivalent substitution source was accepted as a target");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_XCSTRINGS_LOCALE", rejected.code());
          }
        }

        JsonNode missingOther = JSON.readTree(original);
        ((ObjectNode)
                missingOther
                    .path("strings")
                    .path("harbor.target.substitution.scalar🧭")
                    .path("localizations")
                    .path("ru")
                    .path("substitutions")
                    .path("lanes")
                    .path("variations")
                    .path("plural"))
            .remove("other");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingOther), "ru");
          fail(id + ": target substitution accepted a missing other category");
        } catch (LocalizationParseException rejected) {
          assertEquals("MISSING_OTHER_VARIANT", rejected.code());
        }

        JsonNode inventedCategory = JSON.readTree(original);
        ObjectNode categories =
            (ObjectNode)
                inventedCategory
                    .path("strings")
                    .path("harbor.target.substitution.scalar🧭")
                    .path("localizations")
                    .path("ru")
                    .path("substitutions")
                    .path("lanes")
                    .path("variations")
                    .path("plural");
        categories.set("several", categories.remove("few"));
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(inventedCategory), "ru");
          fail(id + ": target substitution accepted an invented category");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_PLURAL_CATEGORY", rejected.code());
        }

        JsonNode missingSelector = JSON.readTree(original);
        ((ObjectNode)
                missingSelector
                    .path("strings")
                    .path("harbor.target.substitution.scalar🧭")
                    .path("localizations")
                    .path("ru")
                    .path("substitutions"))
            .remove("lights");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingSelector), "ru");
          fail(id + ": target substitution accepted a missing selector");
        } catch (LocalizationParseException rejected) {
          assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
        }

        JsonNode missingDevice = JSON.readTree(original);
        ((ObjectNode)
                missingDevice
                    .path("strings")
                    .path("harbor.target.substitution.device🧭")
                    .path("localizations")
                    .path("ru")
                    .path("variations")
                    .path("device"))
            .remove("mac");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingDevice), "ru");
          fail(id + ": target substitution accepted a missing source-owned device");
        } catch (LocalizationParseException rejected) {
          assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
        }

        JsonNode missingTarget = JSON.readTree(original);
        ((ObjectNode)
                missingTarget
                    .path("strings")
                    .path("harbor.target.substitution.scalar🧭")
                    .path("localizations"))
            .putNull("ru");
        assertTrue(
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                    JSON.writeValueAsBytes(missingTarget), "ru")
                .slots()
                .stream()
                .anyMatch(
                    slot ->
                        "harbor.target.substitution.scalar🧭".equals(slot.id())
                            && slot.selector() == null
                            && slot.variant() == null));

        JsonNode missingEvidence = JSON.readTree(original);
        for (String existing :
            List.of("harbor.target.substitution.scalar🧭", "harbor.target.substitution.device🧭")) {
          ((ObjectNode) missingEvidence.path("strings").path(existing).path("localizations"))
              .putNull("ru");
        }
        assertTrue(
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                    JSON.writeValueAsBytes(missingEvidence), "ru")
                .slots()
                .stream()
                .anyMatch(slot -> "harbor.target.substitution.scalar🧭".equals(slot.id())));
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingEvidence), "zz");
          fail(id + ": unsupported first-locale substitution categories were invented");
        } catch (LocalizationParseException rejected) {
          assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
        }

        for (String invalidRoot : List.of("Маршрут {arg2}: {lights}", "{lanes} {lanes} {lights}")) {
          try {
            LocalizationFileConverters.renderSkeleton(
                skeleton, Map.of("harbor.target.substitution.scalar🧭", invalidRoot));
            fail(id + ": target substitution accepted missing or duplicated root markers");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON_SUBSTITUTION", rejected.code());
          }
        }

        LocalizationSourceSkeleton.LocalizationSourceSlot actualCategory =
            skeleton.slots().stream()
                .filter(slot -> "lanes".equals(slot.selector()))
                .findFirst()
                .orElseThrow();
        LocalizationSourceSkeleton forged =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        actualCategory.id(),
                        "invented",
                        actualCategory.variant(),
                        actualCategory.start(),
                        actualCategory.end())));
        try {
          LocalizationFileConverters.renderSkeleton(
              forged,
              Map.of(
                  actualCategory.id() + "#invented#" + actualCategory.variant(), "{lanes} forged"));
          fail(id + ": target substitution selector ownership was forged");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }

        int protectedEntry =
            skeleton.source().indexOf("\"Private target Russian substitution branches\"");
        int targetOffset = skeleton.source().indexOf("\"ru\"", protectedEntry);
        int beginning =
            skeleton.source().indexOf("\"value\": \"", targetOffset) + "\"value\": \"".length();
        int end = skeleton.source().indexOf('"', beginning);
        SourceSkeletonEncoding sourceEncoding = SourceSkeletonEncoding.named(skeleton.encoding());
        LocalizationSourceSkeleton protectedForgery =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        "harbor.target.substitution.scalar🧭",
                        "lanes",
                        "one",
                        sourceEncoding.offset(skeleton.source(), beginning),
                        sourceEncoding.offset(skeleton.source(), end))));
        try {
          LocalizationFileConverters.renderSkeleton(
              protectedForgery,
              Map.of("harbor.target.substitution.scalar🧭#lanes#one", "{lanes} forged"));
          fail(id + ": protected target substitution ownership was forged");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }

        if (fixture.path("xcstringsTargetSubstitutionInsertion").asBoolean(false)) {
          for (String insertedId :
              List.of(
                  "harbor.target.substitution.missing.scalar🧭",
                  "harbor.target.substitution.null.scalar🧭",
                  "harbor.target.substitution.missing.device🧭",
                  "harbor.target.substitution.null.device🧭")) {
            LocalizationMessage inserted = localized.messages().get(insertedId);
            if (fixture.has("xcstringsSourceAliasAtomicSubstitutions")) {
              JsonNode originalCatalog = JSON.readTree(original);
              JsonNode localizedCatalog = JSON.readTree(actual);
              String declared = originalCatalog.path("sourceLanguage").asText();
              String owned =
                  inserted.metadata().get("appleSourceLocalizationIdentifier").toString();
              assertNotEquals(declared, owned);
              assertEquals(
                  originalCatalog
                      .path("strings")
                      .path(insertedId)
                      .path("localizations")
                      .path(owned),
                  localizedCatalog
                      .path("strings")
                      .path(insertedId)
                      .path("localizations")
                      .path(owned));
              if (insertedId.contains(".null.")) {
                assertTrue(
                    originalCatalog
                        .path("strings")
                        .path(insertedId)
                        .path("localizations")
                        .path("ru")
                        .isNull());
              } else {
                assertFalse(
                    originalCatalog
                        .path("strings")
                        .path(insertedId)
                        .path("localizations")
                        .has("ru"));
              }
            }
            Map<?, ?> insertedTarget =
                (Map<?, ?>)
                    ((Map<?, ?>) inserted.metadata().get("appleLocalizationSources")).get("ru");
            Map<?, ?> insertedSelectors = (Map<?, ?>) insertedTarget.get("substitutions");
            assertEquals(Set.of("lanes", "lights"), insertedSelectors.keySet());
            for (Object definition : insertedSelectors.values()) {
              Map<?, ?> insertedCategories =
                  (Map<?, ?>)
                      ((Map<?, ?>) ((Map<?, ?>) definition).get("variations")).get("plural");
              assertEquals(Set.of("one", "few", "many", "other"), insertedCategories.keySet());
              assertTrue(
                  insertedCategories.values().stream()
                      .allMatch(
                          value ->
                              "translated"
                                  .equals(
                                      ((Map<?, ?>) ((Map<?, ?>) value).get("stringUnit"))
                                          .get("state"))));
              assertTrue(
                  insertedCategories.values().stream()
                      .allMatch(
                          value ->
                              ((Map<?, ?>) ((Map<?, ?>) value).get("stringUnit"))
                                  .get("value")
                                  .toString()
                                  .contains("%4$n")));
            }
            if (insertedId.contains(".device")) {
              Map<?, ?> insertedDevices =
                  (Map<?, ?>) ((Map<?, ?>) insertedTarget.get("variations")).get("device");
              assertEquals(Set.of("iphone", "mac"), insertedDevices.keySet());
              assertTrue(
                  insertedDevices.values().stream()
                      .allMatch(
                          value ->
                              "translated"
                                  .equals(
                                      ((Map<?, ?>) ((Map<?, ?>) value).get("stringUnit"))
                                          .get("state"))));
            } else {
              assertEquals(
                  "translated", ((Map<?, ?>) insertedTarget.get("stringUnit")).get("state"));
            }
          }
          assertFalse(
              localized.messages().containsKey("Private missing Russian substitution tree"));
          assertFalse(localized.messages().containsKey("Private null Russian substitution tree"));

          String scalarId = "harbor.target.substitution.missing.scalar🧭";
          String deviceId = "harbor.target.substitution.missing.device🧭";
          Map<String, String> malformed = new LinkedHashMap<>();
          malformed.put("missing selector", "Добавлен {arg2}: {lanes}");
          malformed.put("invented category", translations.get(scalarId).replace("few {", "zero {"));
          malformed.put(
              "unknown placeholder",
              translations.get(scalarId).replaceFirst("\\{lights\\}  обновлённый", "{invented}"));
          for (Map.Entry<String, String> invalidTranslation : malformed.entrySet()) {
            try {
              LocalizationFileConverters.renderSkeleton(
                  skeleton, Map.of(scalarId, invalidTranslation.getValue()));
              fail(id + ": invalid atomic substitution " + invalidTranslation.getKey());
            } catch (LocalizationParseException rejected) {
              assertEquals(
                  "unknown placeholder".equals(invalidTranslation.getKey())
                      ? "INVALID_PLACEHOLDER"
                      : "missing selector".equals(invalidTranslation.getKey())
                          ? "INVALID_SKELETON_SUBSTITUTION"
                          : "INVALID_SKELETON",
                  rejected.code());
            }
          }
          try {
            LocalizationFileConverters.renderSkeleton(
                skeleton,
                Map.of(
                    scalarId,
                    translations.get(scalarId).replace("other {{lights}  обновлённый огня}", "")));
            fail(id + ": atomic target substitution accepted a missing other category");
          } catch (LocalizationParseException rejected) {
            assertEquals("MISSING_OTHER_VARIANT", rejected.code());
          }
          for (String invalidDevice :
              List.of(
                  translations.get(deviceId).replaceFirst("обновлённый огонь", "иной огонь"),
                  translations.get(deviceId).replace("other {Экран", "other {Другое"),
                  translations.get(deviceId).replace("mac {", "watch {"))) {
            try {
              LocalizationFileConverters.renderSkeleton(skeleton, Map.of(deviceId, invalidDevice));
              fail(id + ": mismatched source-owned/shared target device substitution was accepted");
            } catch (LocalizationParseException rejected) {
              assertEquals("INVALID_SKELETON", rejected.code());
            }
          }

          int protectedNull =
              skeleton.source().indexOf("\"Private null Russian substitution tree\"");
          int protectedLocale = skeleton.source().indexOf("\"ru\"", protectedNull);
          int nullStart = skeleton.source().indexOf("null", protectedLocale);
          LocalizationSourceSkeleton forgedNull =
              new LocalizationSourceSkeleton(
                  skeleton.schemaVersion(),
                  skeleton.sourceFormat(),
                  skeleton.encoding(),
                  skeleton.source(),
                  null,
                  null,
                  skeleton.appleTargetLocale(),
                  List.of(
                      new LocalizationSourceSkeleton.LocalizationSourceSlot(
                          scalarId,
                          null,
                          null,
                          sourceEncoding.offset(skeleton.source(), nullStart),
                          sourceEncoding.offset(skeleton.source(), nullStart + 4))));
          try {
            LocalizationFileConverters.renderSkeleton(
                forgedNull, Map.of(scalarId, translations.get(scalarId)));
            fail(id + ": protected null substitution-tree ownership was forged");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsTargetDeviceSlots").asBoolean(false)) {
        assertEquals("ru", skeleton.appleTargetLocale());
        LocalizationMessage scalar = localized.messages().get("harbor.target.device.scalar🧭");
        Map<?, ?> scalarDevices =
            (Map<?, ?>)
                ((Map<?, ?>) ((Map<?, ?>) scalar.metadata().get("localizations")).get("ru"))
                    .get("variationAxes");
        Map<?, ?> scalarBranches = (Map<?, ?>) scalarDevices.get("device");
        assertEquals(
            "На iPhone %1$@ %2$n у маяка",
            ((Map<?, ?>) ((Map<?, ?>) scalarBranches.get("iphone")).get("stringUnit"))
                .get("value"));
        assertEquals(
            "future_review",
            ((Map<?, ?>) ((Map<?, ?>) scalarBranches.get("mac")).get("stringUnit")).get("state"));

        LocalizationMessage plural = localized.messages().get("harbor.target.device.plural🧭");
        Map<?, ?> pluralDevices =
            (Map<?, ?>)
                ((Map<?, ?>) ((Map<?, ?>) plural.metadata().get("localizations")).get("ru"))
                    .get("variationAxes");
        Map<?, ?> mac = (Map<?, ?>) ((Map<?, ?>) pluralDevices.get("device")).get("mac");
        Map<?, ?> categories = (Map<?, ?>) ((Map<?, ?>) mac.get("variations")).get("plural");
        assertEquals(Set.of("one", "few", "many", "other"), categories.keySet());
        assertEquals(
            "%2$@ %3$n %1$lld обновлённый настольный маяка",
            ((Map<?, ?>) ((Map<?, ?>) categories.get("few")).get("stringUnit")).get("value"));
        assertEquals(
            "new",
            ((Map<?, ?>) ((Map<?, ?>) categories.get("few")).get("stringUnit")).get("state"));
        assertFalse(localized.messages().containsKey("Private target Russian device branches"));

        JsonNode missingOther = JSON.readTree(original);
        ((ObjectNode)
                missingOther
                    .path("strings")
                    .path("harbor.target.device.plural🧭")
                    .path("localizations")
                    .path("ru")
                    .path("variations")
                    .path("device")
                    .path("mac")
                    .path("variations")
                    .path("plural"))
            .remove("other");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingOther), "ru");
          fail(id + ": target device plural accepted a missing other category");
        } catch (LocalizationParseException rejected) {
          assertEquals("MISSING_OTHER_VARIANT", rejected.code());
        }

        JsonNode inventedCategory = JSON.readTree(original);
        ObjectNode targetCategories =
            (ObjectNode)
                inventedCategory
                    .path("strings")
                    .path("harbor.target.device.plural🧭")
                    .path("localizations")
                    .path("ru")
                    .path("variations")
                    .path("device")
                    .path("mac")
                    .path("variations")
                    .path("plural");
        targetCategories.set("several", targetCategories.remove("few"));
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(inventedCategory), "ru");
          fail(id + ": target device plural accepted an invented category");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_PLURAL_CATEGORY", rejected.code());
        }

        JsonNode mismatchedDevice = JSON.readTree(original);
        ObjectNode scalarBranch =
            (ObjectNode)
                mismatchedDevice
                    .path("strings")
                    .path("harbor.target.device.scalar🧭")
                    .path("localizations")
                    .path("ru")
                    .path("variations")
                    .path("device")
                    .path("mac");
        scalarBranch.remove("stringUnit");
        scalarBranch
            .putObject("variations")
            .putObject("plural")
            .putObject("other")
            .putObject("stringUnit")
            .put("state", "translated")
            .put("value", "%1$lld unexpected scalar category");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(mismatchedDevice), "ru");
          fail(id + ": mismatched target device scalar/plural ownership was accepted");
        } catch (LocalizationParseException rejected) {
          assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
        }

        JsonNode missingTarget = JSON.readTree(original);
        ((ObjectNode)
                missingTarget
                    .path("strings")
                    .path("harbor.target.device.scalar🧭")
                    .path("localizations"))
            .putNull("ru");
        assertTrue(
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                    JSON.writeValueAsBytes(missingTarget), "ru")
                .slots()
                .stream()
                .anyMatch(
                    slot ->
                        "harbor.target.device.scalar🧭".equals(slot.id())
                            && slot.selector() == null
                            && slot.variant() == null));

        LocalizationSourceSkeleton.LocalizationSourceSlot actualTargetDeviceSlot =
            skeleton.slots().stream()
                .filter(slot -> "@device=iphone".equals(slot.selector()))
                .findFirst()
                .orElseThrow();
        LocalizationSourceSkeleton forgedDevice =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        actualTargetDeviceSlot.id(),
                        "@device=watch",
                        "one",
                        actualTargetDeviceSlot.start(),
                        actualTargetDeviceSlot.end())));
        try {
          LocalizationFileConverters.renderSkeleton(
              forgedDevice,
              Map.of("harbor.target.device.plural🧭#@device=watch#one", "Forged watch"));
          fail(id + ": nonexistent target device ownership was forged");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }

        int protectedEntry =
            skeleton.source().indexOf("\"Private target Russian device branches\"");
        int target = skeleton.source().indexOf("\"ru\"", protectedEntry);
        int declaration = skeleton.source().indexOf("\"value\": \"", target);
        int beginning = declaration + "\"value\": \"".length();
        int end = skeleton.source().indexOf('"', beginning);
        SourceSkeletonEncoding sourceEncoding = SourceSkeletonEncoding.named(skeleton.encoding());
        LocalizationSourceSkeleton forgedProtected =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        "harbor.target.device.plural🧭",
                        "@device=iphone",
                        "one",
                        sourceEncoding.offset(skeleton.source(), beginning),
                        sourceEncoding.offset(skeleton.source(), end))));
        try {
          LocalizationFileConverters.renderSkeleton(
              forgedProtected,
              Map.of("harbor.target.device.plural🧭#@device=iphone#one", "Forged protected"));
          fail(id + ": protected target device category ownership was forged");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }

        if (fixture.path("xcstringsTargetDeviceInsertion").asBoolean(false)) {
          for (String insertedId :
              List.of(
                  "harbor.target.device.missing.scalar🧭",
                  "harbor.target.device.null.scalar🧭",
                  "harbor.target.device.missing.plural🧭",
                  "harbor.target.device.null.plural🧭")) {
            LocalizationMessage insertedMessage = localized.messages().get(insertedId);
            Map<?, ?> insertedDevices =
                (Map<?, ?>)
                    ((Map<?, ?>)
                            ((Map<?, ?>)
                                    ((Map<?, ?>) insertedMessage.metadata().get("localizations"))
                                        .get("ru"))
                                .get("variationAxes"))
                        .get("device");
            assertEquals(Set.of("iphone", "mac"), insertedDevices.keySet());
            for (Object branch : insertedDevices.values()) {
              Map<?, ?> value = (Map<?, ?>) branch;
              if (value.containsKey("stringUnit")) {
                assertEquals("translated", ((Map<?, ?>) value.get("stringUnit")).get("state"));
              } else {
                Map<?, ?> insertedCategories =
                    (Map<?, ?>) ((Map<?, ?>) value.get("variations")).get("plural");
                assertEquals(Set.of("one", "few", "many", "other"), insertedCategories.keySet());
                assertTrue(
                    insertedCategories.values().stream()
                        .allMatch(
                            category ->
                                "translated"
                                    .equals(
                                        ((Map<?, ?>) ((Map<?, ?>) category).get("stringUnit"))
                                            .get("state"))));
              }
            }
          }
          assertFalse(localized.messages().containsKey("Private missing Russian device tree"));
          assertFalse(localized.messages().containsKey("Private null Russian device tree"));

          String missingScalar = "harbor.target.device.missing.scalar🧭";
          String missingPlural = "harbor.target.device.missing.plural🧭";
          Map<String, String> malformedScalars =
              Map.of(
                  "plain scalar", "not a select",
                  "missing fallback", "{device, select, iphone {{arg0}} mac {{arg0}}}",
                  "mismatched fallback",
                      "{device, select, iphone {{arg0}} mac {{arg0}} other {different {arg0}}}",
                  "unknown device",
                      "{device, select, iphone {{arg0}} mac {{arg0}} watch {{arg0}} other {{arg0}}}",
                  "unknown argument",
                      "{device, select, iphone {{unknown}} mac {{unknown}} other {{unknown}}}");
          for (Map.Entry<String, String> malformed : malformedScalars.entrySet()) {
            try {
              LocalizationFileConverters.renderSkeleton(
                  skeleton, Map.of(missingScalar, malformed.getValue()));
              fail(id + ": malformed target-device select accepted " + malformed.getKey());
            } catch (LocalizationParseException rejected) {
              assertEquals(
                  "unknown argument".equals(malformed.getKey())
                      ? "INVALID_PLACEHOLDER"
                      : "INVALID_SKELETON",
                  rejected.code());
            }
          }

          String incompletePlural =
              "{device, select, iphone {{count, plural, one {{count}} other {{count}}}}"
                  + " mac {{count, plural, one {{count}} other {{count}}}}"
                  + " other {{count, plural, one {{count}} other {{count}}}}}";
          try {
            LocalizationFileConverters.renderSkeleton(
                skeleton, Map.of(missingPlural, incompletePlural));
            fail(id + ": missing Russian target-device plural categories were accepted");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }

          JsonNode noEvidence = JSON.readTree(original);
          ((ObjectNode)
                  noEvidence
                      .path("strings")
                      .path("harbor.target.device.plural🧭")
                      .path("localizations"))
              .putNull("ru");
          try {
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                JSON.writeValueAsBytes(noEvidence), "ru");
            fail(id + ": target device plurals guessed categories without native evidence");
          } catch (LocalizationParseException rejected) {
            assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
          }

          int protectedNull = skeleton.source().indexOf("\"Private null Russian device tree\"");
          int protectedTarget = skeleton.source().indexOf("\"ru\": null", protectedNull);
          int nullStart = protectedTarget + "\"ru\": ".length();
          LocalizationSourceSkeleton forgedNull =
              new LocalizationSourceSkeleton(
                  skeleton.schemaVersion(),
                  skeleton.sourceFormat(),
                  skeleton.encoding(),
                  skeleton.source(),
                  null,
                  null,
                  skeleton.appleTargetLocale(),
                  List.of(
                      new LocalizationSourceSkeleton.LocalizationSourceSlot(
                          missingPlural,
                          null,
                          null,
                          sourceEncoding.offset(skeleton.source(), nullStart),
                          sourceEncoding.offset(skeleton.source(), nullStart + 4))));
          try {
            LocalizationFileConverters.renderSkeleton(
                forgedNull, Map.of(missingPlural, translations.get(missingPlural)));
            fail(id + ": protected target-device null ownership was forged");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsFirstLocaleDevices").asBoolean(false)) {
        assertEquals("ru", skeleton.appleTargetLocale());
        for (String first :
            List.of(
                "harbor.first.device.missing.scalar🧭",
                "harbor.first.device.null.scalar🧭",
                "harbor.first.device.missing.plural🧭",
                "harbor.first.device.null.plural🧭")) {
          LocalizationMessage inserted = localized.messages().get(first);
          Map<?, ?> target =
              (Map<?, ?>) ((Map<?, ?>) inserted.metadata().get("localizations")).get("ru");
          Map<?, ?> devices = (Map<?, ?>) ((Map<?, ?>) target.get("variationAxes")).get("device");
          Set<String> expectedDevices =
              fixture.path("xcstringsFirstLocaleFutureDevices").asBoolean(false)
                  ? first.contains(".plural")
                      ? Set.of("iphone", "mac", "futurecar", "\ue000raft", "🧭raft")
                      : Set.of("iphone", "mac", "futurecar", "\ue000raft", "🧭raft", "other")
                  : Set.of("iphone", "mac");
          assertEquals(expectedDevices, devices.keySet());
          if (fixture.path("xcstringsFirstLocaleFutureDevices").asBoolean(false)
              && !first.contains(".plural")) {
            Map<?, ?> fallback = (Map<?, ?>) devices.get("other");
            assertTrue(
                ((Map<?, ?>) fallback.get("stringUnit"))
                    .get("value")
                    .toString()
                    .contains("На other"));
          }
          for (Object value : devices.values()) {
            Map<?, ?> device = (Map<?, ?>) value;
            if (first.contains(".plural")) {
              Map<?, ?> categories =
                  (Map<?, ?>) ((Map<?, ?>) device.get("variations")).get("plural");
              assertEquals(Set.of("one", "few", "many", "other"), categories.keySet());
              assertTrue(
                  categories.values().stream()
                      .allMatch(
                          category -> {
                            Map<?, ?> unit = (Map<?, ?>) ((Map<?, ?>) category).get("stringUnit");
                            return "translated".equals(unit.get("state"))
                                && unit.get("value").toString().contains("%3$n");
                          }));
            } else {
              Map<?, ?> unit = (Map<?, ?>) device.get("stringUnit");
              assertEquals("translated", unit.get("state"));
              assertTrue(unit.get("value").toString().contains("%2$n"));
            }
          }
        }
        assertFalse(localized.messages().containsKey("Private first missing Russian device"));
        assertFalse(localized.messages().containsKey("Private first null Russian device"));
        String scalar = "harbor.first.device.missing.scalar🧭";
        String plural = "harbor.first.device.missing.plural🧭";
        String divergentFallback =
            fixture.path("xcstringsFirstLocaleFutureDevices").asBoolean(false)
                ? translations.get(plural).replace("other {{count", "other {{arg1")
                : translations.get(scalar).replace("other {На iphone", "other {Иной iphone");
        for (String invalid :
            List.of(
                translations.get(scalar).replace("mac {", "watch {"),
                divergentFallback,
                translations.get(plural).replace("few {", "zero {"))) {
          try {
            LocalizationFileConverters.renderSkeleton(
                skeleton,
                Map.of(
                    invalid.contains("zero {")
                            || fixture.path("xcstringsFirstLocaleFutureDevices").asBoolean(false)
                                && invalid.equals(divergentFallback)
                        ? plural
                        : scalar,
                    invalid));
            fail(id + ": unsafe first-locale device ownership was accepted");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsFirstLocaleSubstitutions").asBoolean(false)) {
        assertEquals("ru", skeleton.appleTargetLocale());
        for (String first :
            List.of(
                "harbor.first.substitution.missing.scalar🧭",
                "harbor.first.substitution.null.scalar🧭",
                "harbor.first.substitution.missing.device🧭",
                "harbor.first.substitution.null.device🧭")) {
          LocalizationMessage inserted = localized.messages().get(first);
          if (fixture.has("xcstringsSourceAliasFirstLocaleSubstitutions")) {
            JsonNode source = JSON.readTree(skeleton.source());
            JsonNode localizedCatalog = JSON.readTree(actual);
            String declared = source.path("sourceLanguage").asText();
            String owned = inserted.metadata().get("appleSourceLocalizationIdentifier").toString();
            assertNotEquals(declared, owned);
            assertEquals(
                source.path("strings").path(first).path("localizations").path(owned),
                localizedCatalog.path("strings").path(first).path("localizations").path(owned));
            assertEquals(
                source.path("strings").path(first).path("localizations").path("de"),
                localizedCatalog.path("strings").path(first).path("localizations").path("de"));
            if (first.contains(".null.")) {
              assertTrue(
                  source.path("strings").path(first).path("localizations").path("ru").isNull());
            } else {
              assertFalse(source.path("strings").path(first).path("localizations").has("ru"));
            }
            try {
              LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(original, owned);
              fail(id + ": first-locale development alias was accepted as its own target");
            } catch (LocalizationParseException rejected) {
              assertEquals("INVALID_XCSTRINGS_LOCALE", rejected.code());
            }
          }
          Map<?, ?> target =
              (Map<?, ?>)
                  ((Map<?, ?>) inserted.metadata().get("appleLocalizationSources")).get("ru");
          Map<?, ?> selectors = (Map<?, ?>) target.get("substitutions");
          assertEquals(Set.of("lanes", "lights"), selectors.keySet());
          for (Object selector : selectors.values()) {
            Map<?, ?> categories =
                (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) selector).get("variations")).get("plural");
            assertEquals(Set.of("one", "few", "many", "other"), categories.keySet());
            assertTrue(
                categories.values().stream()
                    .allMatch(
                        category -> {
                          Map<?, ?> unit = (Map<?, ?>) ((Map<?, ?>) category).get("stringUnit");
                          return "translated".equals(unit.get("state"))
                              && !unit.get("value").toString().contains("%4$n");
                        }));
          }
          if (first.contains(".device")) {
            Map<?, ?> devices = (Map<?, ?>) ((Map<?, ?>) target.get("variations")).get("device");
            assertEquals(Set.of("iphone", "mac"), devices.keySet());
            assertTrue(
                devices.values().stream()
                    .allMatch(
                        device ->
                            "translated"
                                .equals(
                                    ((Map<?, ?>) ((Map<?, ?>) device).get("stringUnit"))
                                        .get("state"))));
          } else {
            assertEquals("translated", ((Map<?, ?>) target.get("stringUnit")).get("state"));
          }
        }
        assertFalse(localized.messages().containsKey("Private first missing Russian substitution"));
        assertFalse(localized.messages().containsKey("Private first null Russian substitution"));
        String scalar = "harbor.first.substitution.missing.scalar🧭";
        String device = "harbor.first.substitution.missing.device🧭";
        for (String invalid :
            List.of(
                translations.get(scalar).replace("few {", "zero {"),
                translations.get(scalar).replace("other {{lights} первый огня}", ""),
                translations.get(scalar).replace("{lights} первый", "{invented} первый"))) {
          try {
            LocalizationFileConverters.renderSkeleton(skeleton, Map.of(scalar, invalid));
            fail(id + ": invalid first-locale substitution categories were accepted");
          } catch (LocalizationParseException rejected) {
            assertTrue(
                Set.of("INVALID_SKELETON", "MISSING_OTHER_VARIANT", "INVALID_PLACEHOLDER")
                    .contains(rejected.code()));
          }
        }
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton, Map.of(device, translations.get(device).replace("mac {", "watch {")));
          fail(id + ": first-locale substitution invented a device");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsSourceLocaleAlias")) {
        JsonNode source = JSON.readTree(skeleton.source());
        String declared = source.path("sourceLanguage").asText();
        String scalarId = "harbor.development.source.scalar🧭";
        String pluralId = "harbor.development.source.plural🧭";
        LocalizationMessage scalar = localized.messages().get(scalarId);
        LocalizationMessage plural = localized.messages().get(pluralId);
        String owned = scalar.metadata().get("appleSourceLocalizationIdentifier").toString();
        assertNotEquals(declared, owned);
        assertEquals(owned, plural.metadata().get("appleSourceLocalizationIdentifier"));
        assertEquals("{arg1} {arg0} translated beacon", scalar.defaultMessage());
        assertEquals("{count} translated beacon {arg1}", plural.variants().get("one"));
        assertEquals("{count} translated beacons {arg1}", plural.variants().get("other"));
        assertEquals(3, skeleton.slots().size());
        assertFalse(((Map<?, ?>) scalar.metadata().get("localizations")).containsKey(owned));
        assertFalse(localized.messages().containsKey("Private development-source harbor"));
        JsonNode normalized =
            JSON.readTree(
                LocalizationFileConverters.write(
                    LocalizationFileFormat.APPLE_XCSTRINGS, localized));
        assertTrue(normalized.path("strings").path(scalarId).path("localizations").has(owned));
        assertTrue(normalized.path("strings").path(pluralId).path("localizations").has(owned));
        if (!"region-separator".equals(fixture.path("xcstringsSourceLocaleAlias").asText())) {
          try {
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(original, owned);
            fail(id + ": compiler-equivalent development locale was accepted as a target");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_XCSTRINGS_LOCALE", rejected.code());
          }
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsRegionSeparator")) {
        String separator = fixture.path("xcstringsRegionSeparator").asText();
        String expectedLocale = "underscore".equals(separator) ? "pt_BR" : "pt-BR";
        String untouchedLocale = "underscore".equals(separator) ? "pt-BR" : "pt_BR";
        assertEquals(expectedLocale, skeleton.appleTargetLocale());
        LocalizationMessage message =
            localized.messages().get("harbor.portuguese.region.separator🧭");
        Map<?, ?> localizations = (Map<?, ?>) message.metadata().get("localizations");
        assertTrue(localizations.containsKey("pt_BR"));
        assertTrue(localizations.containsKey("pt-BR"));
        assertTrue(
            ((Map<?, ?>) localizations.get(expectedLocale))
                .get("value")
                .toString()
                .contains("traduzido"));
        assertFalse(
            ((Map<?, ?>) localizations.get(untouchedLocale))
                .get("value")
                .toString()
                .contains("traduzido"));
        assertEquals("needs_review", ((Map<?, ?>) localizations.get("de")).get("state"));
        assertFalse(localized.messages().containsKey("Private independent Portuguese separator"));
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsDeprecatedLocale")) {
        String variation = fixture.path("xcstringsDeprecatedLocale").asText();
        String expectedLocale = "language".equals(variation) ? "iw" : "iw-IL";
        assertEquals(expectedLocale, skeleton.appleTargetLocale());
        for (String hebrew :
            List.of(
                "harbor.first.hebrew.deprecated🧭", "harbor.first.hebrew.deprecated.region🧭")) {
          LocalizationMessage inserted = localized.messages().get(hebrew);
          Map<?, ?> target =
              (Map<?, ?>)
                  ((Map<?, ?>) inserted.metadata().get("localizations")).get(expectedLocale);
          Map<?, ?> categories = (Map<?, ?>) target.get("variants");
          assertEquals(Set.of("one", "two", "other"), categories.keySet());
          assertTrue(
              categories.values().stream().allMatch(value -> value.toString().contains("%3$n")));
          assertTrue(
              ((Map<?, ?>) target.get("variantStates"))
                  .values().stream().allMatch("translated"::equals));
        }
        assertFalse(localized.messages().containsKey("Private deprecated Hebrew harbor"));
        assertEquals(Set.of("one", "two", "other"), IcuCardinalCategories.forLocale("he"));
        assertEquals(Set.of("one", "two", "other"), IcuCardinalCategories.forLocale("iw"));
        assertEquals(Set.of("one", "two", "other"), IcuCardinalCategories.forLocale("iw-IL"));
        assertEquals(Set.of("other"), IcuCardinalCategories.forLocale("in"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("ji"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("kok-Latn"));
        String hebrew = "harbor.first.hebrew.deprecated🧭";
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton,
              Map.of(hebrew, "{count, plural, one {{arg1} {count}} other {{arg1} {count}}}"));
          fail(id + ": Hebrew silently omitted its two category");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsTerritoryLocale")) {
        String variation = fixture.path("xcstringsTerritoryLocale").asText();
        String expectedLocale = "british".equals(variation) ? "en-UK" : "en-001";
        assertEquals(expectedLocale, skeleton.appleTargetLocale());
        for (String territory :
            List.of(
                "harbor.first.english.british.obsolete🧭",
                "harbor.first.english.world.numeric🧭")) {
          LocalizationMessage inserted = localized.messages().get(territory);
          Map<?, ?> target =
              (Map<?, ?>)
                  ((Map<?, ?>) inserted.metadata().get("localizations")).get(expectedLocale);
          Map<?, ?> categories = (Map<?, ?>) target.get("variants");
          assertEquals(Set.of("one", "other"), categories.keySet());
          assertTrue(
              categories.values().stream().allMatch(value -> value.toString().contains("%3$n")));
          assertTrue(
              ((Map<?, ?>) target.get("variantStates"))
                  .values().stream().allMatch("translated"::equals));
        }
        assertFalse(localized.messages().containsKey("Private English territory harbor"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("en-UK"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("en-GB"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("en-001"));
        String territory = "harbor.first.english.british.obsolete🧭";
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton, Map.of(territory, "{count, plural, one {{arg1} {count}}}"));
          fail(id + ": English territory silently omitted its other category");
        } catch (LocalizationParseException rejected) {
          assertTrue(Set.of("INVALID_SKELETON", "MISSING_OTHER_VARIANT").contains(rejected.code()));
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsGrandfatheredLocale")) {
        String variation = fixture.path("xcstringsGrandfatheredLocale").asText();
        String expectedLocale = "bokmal".equals(variation) ? "no-bok" : "no-nyn";
        assertEquals(expectedLocale, skeleton.appleTargetLocale());
        for (String norwegian :
            List.of(
                "harbor.first.norwegian.bokmal.grandfathered🧭",
                "harbor.first.norwegian.nynorsk.grandfathered🧭")) {
          LocalizationMessage inserted = localized.messages().get(norwegian);
          Map<?, ?> target =
              (Map<?, ?>)
                  ((Map<?, ?>) inserted.metadata().get("localizations")).get(expectedLocale);
          Map<?, ?> categories = (Map<?, ?>) target.get("variants");
          assertEquals(Set.of("one", "other"), categories.keySet());
          assertTrue(
              categories.values().stream().allMatch(value -> value.toString().contains("%3$n")));
          assertTrue(
              ((Map<?, ?>) target.get("variantStates"))
                  .values().stream().allMatch("translated"::equals));
        }
        assertFalse(localized.messages().containsKey("Private Norwegian language harbor"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("nb"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("nn"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("no"));
        String norwegian = "harbor.first.norwegian.bokmal.grandfathered🧭";
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton, Map.of(norwegian, "{count, plural, one {{arg1} {count}}}"));
          fail(id + ": Norwegian silently omitted its other category");
        } catch (LocalizationParseException rejected) {
          assertTrue(Set.of("INVALID_SKELETON", "MISSING_OTHER_VARIANT").contains(rejected.code()));
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsScriptLocale")) {
        String script = fixture.path("xcstringsScriptLocale").asText();
        String expectedLocale = "latin".equals(script) ? "sr_Latn" : "sr-Cyrl";
        assertEquals(expectedLocale, skeleton.appleTargetLocale());
        for (String scripted :
            List.of("harbor.first.serbian.latin🧭", "harbor.first.serbian.cyrillic🧭")) {
          LocalizationMessage inserted = localized.messages().get(scripted);
          Map<?, ?> target =
              (Map<?, ?>)
                  ((Map<?, ?>) inserted.metadata().get("localizations"))
                      .get(expectedLocale.replace('_', '-'));
          Map<?, ?> categories = (Map<?, ?>) target.get("variants");
          assertEquals(Set.of("few", "one", "other"), categories.keySet());
          assertTrue(
              categories.values().stream().allMatch(value -> value.toString().contains("%3$n")));
          assertTrue(
              ((Map<?, ?>) target.get("variantStates"))
                  .values().stream().allMatch("translated"::equals));
        }
        assertFalse(localized.messages().containsKey("Private Serbian script harbor"));
        assertEquals(Set.of("few", "one", "other"), IcuCardinalCategories.forLocale("sr-Latn"));
        assertEquals(Set.of("few", "one", "other"), IcuCardinalCategories.forLocale("sr-Cyrl"));
        assertEquals(Set.of("few", "one", "other"), IcuCardinalCategories.forLocale("sr-Cyrl-RS"));
        assertEquals(Set.of("other"), IcuCardinalCategories.forLocale("zh-Hans"));
        assertEquals(Set.of("other"), IcuCardinalCategories.forLocale("zh-Hant"));
        assertEquals(Set.of("one", "other"), IcuCardinalCategories.forLocale("kok-Latn"));
        String scripted = "harbor.first.serbian.latin🧭";
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton,
              Map.of(scripted, "{count, plural, one {{arg1} {count}} other {{arg1} {count}}}"));
          fail(id + ": Serbian script silently omitted its few category");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsRegionalLocale")) {
        String region = fixture.path("xcstringsRegionalLocale").asText();
        String expectedLocale = "brazil".equals(region) ? "pt_BR" : "pt-PT";
        assertEquals(expectedLocale, skeleton.appleTargetLocale());
        for (String regional :
            List.of("harbor.first.brazilian.portuguese🧭", "harbor.first.european.portuguese🧭")) {
          LocalizationMessage inserted = localized.messages().get(regional);
          Map<?, ?> target =
              (Map<?, ?>)
                  ((Map<?, ?>) inserted.metadata().get("localizations"))
                      .get(expectedLocale.replace('_', '-'));
          Map<?, ?> categories = (Map<?, ?>) target.get("variants");
          assertEquals(Set.of("one", "many", "other"), categories.keySet());
          assertTrue(
              categories.values().stream().allMatch(value -> value.toString().contains("%3$n")));
          assertTrue(
              ((Map<?, ?>) target.get("variantStates"))
                  .values().stream().allMatch("translated"::equals));
        }
        assertFalse(localized.messages().containsKey("Private regional Portuguese harbor"));
        assertEquals(Set.of("one", "many", "other"), IcuCardinalCategories.forLocale("pt-BR"));
        assertEquals(Set.of("one", "many", "other"), IcuCardinalCategories.forLocale("pt_BR"));
        assertEquals(Set.of("one", "many", "other"), IcuCardinalCategories.forLocale("pt-PT"));
        assertEquals(Set.of("other"), IcuCardinalCategories.forLocale("zh-Hans"));
        assertEquals(Set.of("few", "one", "other"), IcuCardinalCategories.forLocale("sr-Latn"));
        assertEquals(
            Set.of("few", "many", "one", "other"), IcuCardinalCategories.forLocale("ru-RU"));
        assertEquals("one", PluralRules.forLocale(ULocale.forLanguageTag("pt-BR")).select(0));
        assertEquals("other", PluralRules.forLocale(ULocale.forLanguageTag("pt-PT")).select(0));
        String regional = "harbor.first.brazilian.portuguese🧭";
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton,
              Map.of(regional, "{count, plural, one {{arg1} {count}} other {{arg1} {count}}}"));
          fail(id + ": regional Portuguese silently omitted its many category");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsFirstLocaleCategories").asBoolean(false)) {
        assertEquals("ru", skeleton.appleTargetLocale());
        for (String first :
            List.of("harbor.first.russian.missing🧭", "harbor.first.russian.null🧭")) {
          LocalizationMessage inserted = localized.messages().get(first);
          Map<?, ?> russian =
              (Map<?, ?>) ((Map<?, ?>) inserted.metadata().get("localizations")).get("ru");
          Map<?, ?> categories = (Map<?, ?>) russian.get("variants");
          assertEquals(Set.of("one", "few", "many", "other"), categories.keySet());
          assertTrue(
              categories.values().stream().allMatch(value -> value.toString().contains("%3$n")));
          assertTrue(
              ((Map<?, ?>) russian.get("variantStates"))
                  .values().stream().allMatch("translated"::equals));
        }
        assertFalse(localized.messages().containsKey("Private first missing Russian plural"));
        assertFalse(localized.messages().containsKey("Private first null Russian plural"));
        assertEquals(Set.of("one", "few", "many", "other"), IcuCardinalCategories.forLocale("ru"));
        assertEquals(Set.of("other"), IcuCardinalCategories.forLocale("ja"));
        assertEquals(
            Set.of("zero", "one", "two", "few", "many", "other"),
            IcuCardinalCategories.forLocale("ar"));
        assertTrue(IcuCardinalCategories.forLocale("zz").isEmpty());
        assertTrue(IcuCardinalCategories.forLocale("und").isEmpty());
        for (String supported : List.of("cv", "ie", "kok", "kok-Latn", "sgs")) {
          assertEquals(
              "ICU-supported locale was rejected: " + supported,
              PluralRules.forLocale(ULocale.forLanguageTag(supported)).getKeywords(),
              IcuCardinalCategories.forLocale(supported));
          assertEquals(
              supported,
              LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                      original, supported)
                  .appleTargetLocale());
        }
        for (String unsupported : List.of("zz", "und")) {
          try {
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                original, unsupported);
            fail(id + ": unsupported first-locale categories were invented");
          } catch (LocalizationParseException rejected) {
            assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
          }
        }
        try {
          LocalizationFileConverters.renderSkeleton(
              skeleton,
              Map.of(
                  "harbor.first.russian.missing🧭",
                  "{count, plural, one {{count}} other {{count}}}"));
          fail(id + ": incomplete ICU first-locale categories were accepted");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsTargetPlural").asBoolean(false)) {
        assertEquals("ru", skeleton.appleTargetLocale());
        LocalizationMessage plural = localized.messages().get("harbor.target.russian🧭");
        assertEquals(
            "{count, plural, one {{count} beacon {arg1}}" + " other {{count} beacons {arg1}}}",
            plural.defaultMessage());
        Map<?, ?> russian =
            (Map<?, ?>) ((Map<?, ?>) plural.metadata().get("localizations")).get("ru");
        Map<?, ?> variants = (Map<?, ?>) russian.get("variants");
        assertEquals("%2$@ %3$n %1$lld маяк у причала", variants.get("one"));
        assertEquals("%2$@ %3$n %1$lld маяка у причала", variants.get("few"));
        assertEquals("%2$@ %3$n %1$lld маяков у причала", variants.get("many"));
        assertEquals("%2$@ %3$n %1$lld маяка у причала", variants.get("other"));
        Map<?, ?> states = (Map<?, ?>) russian.get("variantStates");
        assertEquals("needs_review", states.get("one"));
        assertEquals("new", states.get("few"));
        assertEquals("future_review", states.get("many"));
        assertEquals("translated", states.get("other"));
        assertFalse(localized.messages().containsKey("Private target Russian plural"));

        JsonNode missingOther = JSON.readTree(original);
        ((ObjectNode)
                missingOther
                    .path("strings")
                    .path("harbor.target.russian🧭")
                    .path("localizations")
                    .path("ru")
                    .path("variations")
                    .path("plural"))
            .remove("other");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingOther), "ru");
          fail(id + ": a Russian target plural without other was accepted");
        } catch (LocalizationParseException rejected) {
          assertEquals("MISSING_OTHER_VARIANT", rejected.code());
        }

        JsonNode missingTarget = JSON.readTree(original);
        ((ObjectNode)
                missingTarget.path("strings").path("harbor.target.russian🧭").path("localizations"))
            .putNull("ru");
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
              JSON.writeValueAsBytes(missingTarget), "ru");
          fail(id + ": an absent Russian target plural was silently flattened");
        } catch (LocalizationParseException rejected) {
          assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
        }

        LocalizationSourceSkeleton.LocalizationSourceSlot actualFew =
            skeleton.slots().stream()
                .filter(slot -> "few".equals(slot.variant()))
                .findFirst()
                .orElseThrow();
        LocalizationSourceSkeleton forgedCategory =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        actualFew.id(), null, "zero", actualFew.start(), actualFew.end())));
        try {
          LocalizationFileConverters.renderSkeleton(
              forgedCategory, Map.of("harbor.target.russian🧭#zero", "Forged target category"));
          fail(id + ": a missing target plural category was forged");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }

        int protectedEntry = skeleton.source().indexOf("\"Private target Russian plural\"");
        int target = skeleton.source().indexOf("\"ru\"", protectedEntry);
        int declaration = skeleton.source().indexOf("\"value\": \"", target);
        int beginning = declaration + "\"value\": \"".length();
        int end = skeleton.source().indexOf('"', beginning);
        SourceSkeletonEncoding sourceEncoding = SourceSkeletonEncoding.named(skeleton.encoding());
        LocalizationSourceSkeleton forgedProtected =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        "harbor.target.russian🧭",
                        null,
                        "one",
                        sourceEncoding.offset(skeleton.source(), beginning),
                        sourceEncoding.offset(skeleton.source(), end))));
        try {
          LocalizationFileConverters.renderSkeleton(
              forgedProtected, Map.of("harbor.target.russian🧭#one", "Forged protected branch"));
          fail(id + ": a protected target plural category was forged");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }

        if (fixture.path("xcstringsTargetPluralInsertion").asBoolean(false)) {
          for (String insertedId :
              List.of("harbor.target.russian.missing🧭", "harbor.target.russian.null🧭")) {
            LocalizationMessage insertedPlural = localized.messages().get(insertedId);
            Map<?, ?> insertedTarget =
                (Map<?, ?>) ((Map<?, ?>) insertedPlural.metadata().get("localizations")).get("ru");
            Map<?, ?> insertedVariants = (Map<?, ?>) insertedTarget.get("variants");
            assertEquals(Set.of("one", "few", "many", "other"), insertedVariants.keySet());
            assertTrue(
                insertedVariants.values().stream()
                    .allMatch(value -> value.toString().contains("%1$lld %3$n")));
            Map<?, ?> insertedStates = (Map<?, ?>) insertedTarget.get("variantStates");
            assertTrue(insertedStates.values().stream().allMatch("translated"::equals));
            assertEquals(
                "needs_review",
                ((Map<?, ?>) insertedPlural.metadata().get("sourcePluralStates")).get("one"));
          }
          assertFalse(localized.messages().containsKey("Private missing Russian plural"));
          assertFalse(localized.messages().containsKey("Private null Russian plural"));

          Map<String, String> invalidTranslations =
              Map.of(
                  "MISSING_OTHER_VARIANT", "{count, plural, one {{count}}}",
                  "INVALID_PLURAL_CATEGORY", "{count, plural, several {{count}} other {{count}}}",
                  "INVALID_PLACEHOLDER",
                      "{count, plural, one {{unknown}} few {{count}} many {{count}} other {{count}}}");
          for (Map.Entry<String, String> invalid : invalidTranslations.entrySet()) {
            try {
              LocalizationFileConverters.renderSkeleton(
                  skeleton, Map.of("harbor.target.russian.missing🧭", invalid.getValue()));
              fail(id + ": malformed atomic target plural was accepted: " + invalid.getKey());
            } catch (LocalizationParseException rejected) {
              assertEquals(invalid.getKey(), rejected.code());
            }
          }
          for (String malformed :
              List.of(
                  "plain scalar",
                  "{count, plural, one {{count}} other {{count}}}",
                  "{count, plural, one {{count}} one {{count}} few {{count}} many {{count}} other {{count}}}",
                  "{count, plural, one {{count}} few {{count}} many {{count}} other {{count}}")) {
            try {
              LocalizationFileConverters.renderSkeleton(
                  skeleton, Map.of("harbor.target.russian.missing🧭", malformed));
              fail(id + ": incomplete or duplicated Russian categories were accepted");
            } catch (LocalizationParseException rejected) {
              assertEquals("INVALID_SKELETON", rejected.code());
            }
          }

          JsonNode noEvidence = JSON.readTree(original);
          ((ObjectNode)
                  noEvidence.path("strings").path("harbor.target.russian🧭").path("localizations"))
              .putNull("ru");
          try {
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                JSON.writeValueAsBytes(noEvidence), "ru");
            fail(id + ": atomic target insertion guessed categories without native evidence");
          } catch (LocalizationParseException rejected) {
            assertEquals("UNSUPPORTED_SKELETON_SOURCE", rejected.code());
          }

          int protectedNull = skeleton.source().indexOf("\"Private null Russian plural\"");
          int protectedDeclaration = skeleton.source().indexOf("\"ru\": null", protectedNull);
          int protectedStart = protectedDeclaration + "\"ru\": ".length();
          LocalizationSourceSkeleton forgedNull =
              new LocalizationSourceSkeleton(
                  skeleton.schemaVersion(),
                  skeleton.sourceFormat(),
                  skeleton.encoding(),
                  skeleton.source(),
                  null,
                  null,
                  skeleton.appleTargetLocale(),
                  List.of(
                      new LocalizationSourceSkeleton.LocalizationSourceSlot(
                          "harbor.target.russian.null🧭",
                          null,
                          null,
                          sourceEncoding.offset(skeleton.source(), protectedStart),
                          sourceEncoding.offset(skeleton.source(), protectedStart + 4))));
          try {
            LocalizationFileConverters.renderSkeleton(
                forgedNull,
                Map.of(
                    "harbor.target.russian.null🧭",
                    translations.get("harbor.target.russian.null🧭")));
            fail(id + ": a protected null target plural was forged");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }

          int protectedMissing = skeleton.source().indexOf("\"Private missing Russian plural\"");
          int protectedMap = skeleton.source().indexOf("\"localizations\"", protectedMissing);
          int protectedClosing = skeleton.source().indexOf("\n      }", protectedMap) + 7;
          while (protectedClosing > 0
              && Character.isWhitespace(skeleton.source().charAt(protectedClosing - 1))) {
            protectedClosing--;
          }
          int protectedOffset = sourceEncoding.offset(skeleton.source(), protectedClosing);
          LocalizationSourceSkeleton forgedMissingPlural =
              new LocalizationSourceSkeleton(
                  skeleton.schemaVersion(),
                  skeleton.sourceFormat(),
                  skeleton.encoding(),
                  skeleton.source(),
                  null,
                  null,
                  skeleton.appleTargetLocale(),
                  List.of(
                      new LocalizationSourceSkeleton.LocalizationSourceSlot(
                          "harbor.target.russian.missing🧭",
                          null,
                          null,
                          protectedOffset,
                          protectedOffset)));
          try {
            LocalizationFileConverters.renderSkeleton(
                forgedMissingPlural,
                Map.of(
                    "harbor.target.russian.missing🧭",
                    translations.get("harbor.target.russian.missing🧭")));
            fail(id + ": a protected missing target plural was forged");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsOpaqueReviewStates").asBoolean(false)) {
        assertEquals("fr_CA", skeleton.appleTargetLocale());
        List<String> states =
            List.of(
                "new",
                "needs_review",
                "translated",
                "machine_translated",
                "stale",
                "future_review",
                "untranslated",
                "invalid_future_state");
        for (int index = 0; index < states.size(); index++) {
          String state = states.get(index);
          LocalizationMessage message =
              localized.messages().get("harbor.review." + index + "." + state + "🧭");
          assertEquals(state, message.metadata().get("sourceState"));
          assertEquals(
              index % 2 == 0 ? "manual" : "stale", message.metadata().get("extractionState"));
          Map<?, ?> languages = (Map<?, ?>) message.metadata().get("localizations");
          assertEquals(
              states.get(states.size() - index - 1),
              ((Map<?, ?>) languages.get("fr-CA")).get("state"));
          assertEquals(
              "preserved_future_state_" + index, ((Map<?, ?>) languages.get("de")).get("state"));
          assertEquals(
              "Révisé " + state + " %@", ((Map<?, ?>) languages.get("fr-CA")).get("value"));
        }
        LocalizationMessage automatic =
            localized.messages().get("harbor.review.source.new.automatic🧭");
        assertEquals("new", automatic.metadata().get("sourceState"));
        assertEquals("invalid_future_extraction", automatic.metadata().get("extractionState"));
        assertEquals(
            "invalid_future_state",
            ((Map<?, ?>) ((Map<?, ?>) automatic.metadata().get("localizations")).get("fr-CA"))
                .get("state"));
        assertFalse(localized.messages().containsKey("Private future review state"));
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.has("xcstringsTargetLocale")) {
        assertEquals("fr_CA", skeleton.appleTargetLocale());
        LocalizationMessage hidden = localized.messages().get("North %n %@ 🧭");
        assertEquals("North  {arg1} 🧭", hidden.defaultMessage());
        assertEquals("translated", hidden.metadata().get("sourceState"));
        assertEquals(
            "Ouest %n %@ 🧭",
            ((Map<?, ?>) ((Map<?, ?>) hidden.metadata().get("localizations")).get("fr-CA"))
                .get("value"));
        assertEquals(
            "needs_review",
            ((Map<?, ?>)
                    ((Map<?, ?>)
                            localized
                                .messages()
                                .get("West %2$n %1$@ pier")
                                .metadata()
                                .get("localizations"))
                        .get("fr-CA"))
                .get("state"));
        assertEquals(
            "new",
            ((Map<?, ?>)
                    ((Map<?, ?>)
                            localized
                                .messages()
                                .get("Tide %%n %@ marker")
                                .metadata()
                                .get("localizations"))
                        .get("fr-CA"))
                .get("state"));
        assertFalse(localized.messages().containsKey("Private target null pier"));
        assertFalse(localized.messages().containsKey("Private target missing pier"));
        for (String invalidLocale : List.of("", "en", "fr CA", "fr--CA", "x", "fr-123456789")) {
          try {
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                original, invalidLocale);
            fail(id + ": invalid or source-owned target locale was accepted: " + invalidLocale);
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_XCSTRINGS_LOCALE", rejected.code());
          }
        }
        try {
          LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(original, null);
          fail(id + ": a null target locale was accepted");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_XCSTRINGS_LOCALE", rejected.code());
        }
        JsonNode conflicting = JSON.readTree(original);
        ((ObjectNode)
                conflicting.path("strings").path("harbor.target.missing🧭").path("localizations"))
            .putObject("fr-CA")
            .putObject("stringUnit")
            .put("state", "translated")
            .put("value", "Conflicting physical locale");
        LocalizationSourceSkeleton physical =
            LocalizationFileConverters.extractSkeletonWithXcodeTargetInsertion(
                JSON.writeValueAsBytes(conflicting), "fr-CA");
        assertEquals("fr-CA", physical.appleTargetLocale());
        int protectedEntry = skeleton.source().indexOf("\"Private target null pier\"");
        int declaration = skeleton.source().indexOf("\"fr_CA\": null", protectedEntry);
        int value = declaration + "\"fr_CA\": ".length();
        SourceSkeletonEncoding sourceEncoding = SourceSkeletonEncoding.named(skeleton.encoding());
        LocalizationSourceSkeleton forged =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        "harbor.target.missing🧭",
                        null,
                        null,
                        sourceEncoding.offset(skeleton.source(), value),
                        sourceEncoding.offset(skeleton.source(), value + "null".length()))));
        try {
          LocalizationFileConverters.renderSkeleton(
              forged, Map.of("harbor.target.missing🧭", "Forged protected target"));
          fail(id + ": a protected null target cannot impersonate locale ownership");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
        int missingEntry = skeleton.source().indexOf("\"Private target missing pier\"");
        int localizationMap = skeleton.source().indexOf("\"localizations\"", missingEntry);
        int closing = skeleton.source().indexOf("\n      }", localizationMap) + "\n      ".length();
        int insertion = closing;
        while (insertion > 0
            && switch (skeleton.source().charAt(insertion - 1)) {
              case ' ', '\t', '\r', '\n' -> true;
              default -> false;
            }) {
          insertion--;
        }
        int offset = sourceEncoding.offset(skeleton.source(), insertion);
        LocalizationSourceSkeleton forgedMissing =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                null,
                null,
                skeleton.appleTargetLocale(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        "harbor.target.missing🧭", null, null, offset, offset)));
        try {
          LocalizationFileConverters.renderSkeleton(
              forgedMissing, Map.of("harbor.target.missing🧭", "Forged protected target"));
          fail(id + ": a protected missing target cannot impersonate locale ownership");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsInsertSourceLocale").asBoolean(false)) {
        boolean missingSource = fixture.path("xcstringsMissingSourceLocale").asBoolean(false);
        String fallbackId = missingSource ? "harbor.missing.plain🧭" : "harbor.null.plain🧭";
        LocalizationMessage inserted = localized.messages().get("North %n %@ 🧭");
        assertEquals("Ouest  {arg1} 🧭", inserted.defaultMessage());
        assertEquals("translated", inserted.metadata().get("sourceState"));
        assertEquals(
            "needs_review",
            ((Map<?, ?>)
                    ((Map<?, ?>)
                            localized.messages().get(fallbackId).metadata().get("localizations"))
                        .get("fr"))
                .get("state"));
        assertFalse(localized.messages().containsKey("Private null pier"));
        int protectedEntry = skeleton.source().indexOf("\"Private null pier\"");
        int declaration = skeleton.source().indexOf("\"en\": null", protectedEntry);
        int value = declaration + "\"en\": ".length();
        SourceSkeletonEncoding sourceEncoding = SourceSkeletonEncoding.named(skeleton.encoding());
        LocalizationSourceSkeleton forged =
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                List.of(
                    new LocalizationSourceSkeleton.LocalizationSourceSlot(
                        fallbackId,
                        null,
                        null,
                        sourceEncoding.offset(skeleton.source(), value),
                        sourceEncoding.offset(skeleton.source(), value + "null".length()))));
        try {
          LocalizationFileConverters.renderSkeleton(
              forged, Map.of(fallbackId, "Forged protected value"));
          fail(id + ": a protected null token cannot impersonate the source locale");
        } catch (LocalizationParseException rejected) {
          assertEquals("INVALID_SKELETON", rejected.code());
        }
        if (missingSource) {
          assertFalse(localized.messages().containsKey("Private missing pier"));
          int protectedMissing = skeleton.source().indexOf("\"Private missing pier\"");
          int localizationMap = skeleton.source().indexOf("\"localizations\"", protectedMissing);
          int closing =
              skeleton.source().indexOf("\n      }", localizationMap) + "\n      ".length();
          int insertion = closing;
          while (insertion > 0
              && switch (skeleton.source().charAt(insertion - 1)) {
                case ' ', '\t', '\r', '\n' -> true;
                default -> false;
              }) {
            insertion--;
          }
          int offset = sourceEncoding.offset(skeleton.source(), insertion);
          LocalizationSourceSkeleton forgedMissing =
              new LocalizationSourceSkeleton(
                  skeleton.schemaVersion(),
                  skeleton.sourceFormat(),
                  skeleton.encoding(),
                  skeleton.source(),
                  List.of(
                      new LocalizationSourceSkeleton.LocalizationSourceSlot(
                          fallbackId, null, null, offset, offset)));
          try {
            LocalizationFileConverters.renderSkeleton(
                forgedMissing, Map.of(fallbackId, "Forged protected insertion"));
            fail(id + ": a protected missing locale cannot impersonate a source insertion");
          } catch (LocalizationParseException rejected) {
            assertEquals("INVALID_SKELETON", rejected.code());
          }
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsSubstitutionSlots").asBoolean(false)) {
        if (fixture.path("xcstringsHiddenArgumentSlots").asBoolean(false)) {
          assertEquals(
              "Suive {count, plural, one {{count} {arg2} voie}" + " other {{count} {arg2} voies}}",
              localized.messages().get("harbor.after").defaultMessage());
          assertEquals(
              "Suive {count, plural, one {{count} {arg3} voie}" + " other {{count} {arg3} voies}}",
              localized.messages().get("harbor.repeated").defaultMessage());
          assertEquals(
              "Suive {arg0} {count, plural, one {{count}  {arg3} voie}"
                  + " other {{count}  {arg3} voies}}",
              localized.messages().get("harbor.explicit").defaultMessage());
        } else if (fixture.path("xcstringsSubstitutionArgumentSlots").asBoolean(false)) {
          assertEquals(
              "Pilote {arg0} suit {lights, plural, one {{lights} lueur près de {arg2}}"
                  + " other {{lights} lueurs près de {arg2}}}",
              localized.messages().get("mixed.route").defaultMessage());
          assertEquals(
              "Pilote {arg0} compte {signals, plural, one"
                  + " {{signals} signal près de {arg2} balise} other"
                  + " {{signals} signaux près de {arg2} balises}}",
              localized.messages().get("extra.numeric").defaultMessage());
          assertEquals(
              "Pilote {arg0} annonce {routes, plural, one {{arg2} protège {routes} voie}"
                  + " other {{arg2} protège {routes} voies}}",
              localized.messages().get("reversed.route").defaultMessage());
          assertEquals(
              "{arg0} suit {信号, plural, one {{信号} pulsation vers {arg2}}"
                  + " other {{信号} pulsations vers {arg2}}}",
              localized.messages().get("unicode.mixed").defaultMessage());
          assertEquals(
              "Touchez {arg0} pour {beacons, plural, one {{beacons} balise vers {arg2}}"
                  + " other {{beacons} balises vers {arg2}}}",
              localized.messages().get("device.mixed").defaultMessage());
          assertEquals(
              List.of(2, 3, 1),
              localized.messages().get("mixed.route").placeholders().stream()
                  .map(LocalizationPlaceholder::position)
                  .toList());
          assertEquals(
              List.of("lights", "arg2", "arg0"),
              localized.messages().get("mixed.route").placeholders().stream()
                  .map(LocalizationPlaceholder::name)
                  .toList());
        } else if (fixture.path("xcstringsDisabledPrintfSubstitutionSlots").asBoolean(false)) {
          assertEquals(
              "Regardez {count, plural, one {{count} balise} other {{count} balises}}",
              localized.messages().get("harbor.zero").defaultMessage());
          assertEquals(
              "Regardez {count, plural, one {{count}%n balise} other {{count}%n balises}}",
              localized.messages().get("harbor.literal").defaultMessage());
          assertEquals(
              "Regardez {count, plural, one {{count}\nbalise} other {{count}\nbalises}}",
              localized.messages().get("harbor.line").defaultMessage());
          assertEquals(
              "Touchez {count, plural, one {{count} voie} other {{count} voies}}",
              localized.messages().get("harbor.device.🧭").defaultMessage());
          if (fixture.path("xcstringsAllDeviceSlots").asBoolean(false)) {
            Map<?, ?> axes =
                (Map<?, ?>)
                    localized
                        .messages()
                        .get("harbor.device.🧭")
                        .metadata()
                        .get("sourceVariationAxes");
            Map<?, ?> mac = (Map<?, ?>) ((Map<?, ?>) axes.get("device")).get("mac");
            assertEquals(
                "Cliquez %#@count@ au port", ((Map<?, ?>) mac.get("stringUnit")).get("value"));
          }
        } else if (fixture.path("xcstringsDeviceSubstitutionSlots").asBoolean(false)) {
          assertEquals(
              "Touchez {arg2}: {lights, plural, one {{lights} lueur calme}"
                  + " other {{lights} lueurs calmes}} avant {lanes, plural, one"
                  + " {{lanes} voie légère} other {{lanes} voies légères}}",
              localized.messages().get("device.harbor🧭").defaultMessage());
          assertEquals(
              "{count, plural, one {{count} écho léger} other {{count} échos légers}}"
                  + " autour de {count, plural, one {{count} écho léger}"
                  + " other {{count} échos légers}}",
              localized.messages().get("device.echo").defaultMessage());
          assertEquals(
              "Suivez {信号, plural, one {{信号} signal doux} other {{信号} signaux doux}}",
              localized.messages().get("device.unicode").defaultMessage());
          assertEquals(
              "iphone",
              localized.messages().get("device.harbor🧭").metadata().get("defaultDevice"));
          assertEquals(
              "ipad", localized.messages().get("device.unicode").metadata().get("defaultDevice"));
          assertEquals(
              "needs_review",
              localized.messages().get("device.harbor🧭").metadata().get("sourceState"));
        } else {
          assertEquals(
              "Pilote {arg2} suit {lights, plural, one {{lights} lumière nord}"
                  + " other {{lights} lumières nord}} puis {lanes, plural, one"
                  + " {{lanes} voie légère} other {{lanes} voies légères}}",
              localized.messages().get("harbor.route🧭").defaultMessage());
          assertEquals(
              "{count, plural, one {{count} écho calme} other {{count} échos calmes}}"
                  + " autour de {count, plural, one {{count} écho calme}"
                  + " other {{count} échos calmes}}",
              localized.messages().get("echo.route").defaultMessage());
          assertEquals(
              "Suivez {信号, plural, one {{信号} pulsation douce}" + " other {{信号} pulsations douces}}",
              localized.messages().get("unicode.route").defaultMessage());
          assertEquals(
              "Regardez {amount, plural, one {{amount} place verte}"
                  + " other {{amount} places vertes}}",
              localized.messages().get("implicit.lane").defaultMessage());
          assertEquals(
              "needs_review",
              localized.messages().get("harbor.route🧭").metadata().get("sourceState"));
        }
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsDeviceHiddenArgumentSlots").asBoolean(false)) {
        LocalizationMessage message = localized.messages().get("device.after🧭");
        assertEquals("{count} {arg2} mobile repère", message.variants().get("one"));
        assertEquals(
            "{count} {arg3} mobile repères",
            localized.messages().get("device.repeated").variants().get("other"));
        assertEquals(
            "{count}%n {arg1} mobile repère",
            localized.messages().get("device.escaped").variants().get("one"));
        Map<?, ?> devices =
            (Map<?, ?>) ((Map<?, ?>) message.metadata().get("sourceVariationAxes")).get("device");
        Map<?, ?> mac = (Map<?, ?>) devices.get("mac");
        Map<?, ?> branches = (Map<?, ?>) ((Map<?, ?>) mac.get("variations")).get("plural");
        assertEquals(
            "%lld%n %@ bureau repère",
            ((Map<?, ?>) ((Map<?, ?>) branches.get("one")).get("stringUnit")).get("value"));
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsDevicePluralSlots").asBoolean(false)) {
        assertEquals(
            "Touchez {arg0} rive mobile", localized.messages().get("instruction").defaultMessage());
        assertEquals(
            "{count} signal mobile sûr",
            localized.messages().get("device.counter").variants().get("one"));
        assertEquals(
            "{count} signaux mobiles sûrs",
            localized.messages().get("device.counter").variants().get("other"));
        Map<?, ?> devices =
            (Map<?, ?>)
                ((Map<?, ?>)
                        localized
                            .messages()
                            .get("device.counter")
                            .metadata()
                            .get("sourceVariationAxes"))
                    .get("device");
        Map<?, ?> mac = (Map<?, ?>) devices.get("mac");
        Map<?, ?> categories = (Map<?, ?>) ((Map<?, ?>) mac.get("variations")).get("plural");
        assertEquals(
            "%lld balise bureau calme",
            ((Map<?, ?>) ((Map<?, ?>) categories.get("one")).get("stringUnit")).get("value"));
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && fixture.path("xcstringsFutureDevices").asBoolean(false)) {
        LocalizationMessage message = localized.messages().get("harbor.future.device🧭");
        assertEquals("Téléphone {arg0} quai", message.defaultMessage());
        assertEquals("iphone", message.metadata().get("defaultDevice"));
        Map<?, ?> devices =
            (Map<?, ?>) ((Map<?, ?>) message.metadata().get("sourceVariationAxes")).get("device");
        assertEquals(
            "Vaisseau %@ quai",
            ((Map<?, ?>) ((Map<?, ?>) devices.get("futurecar")).get("stringUnit")).get("value"));
        assertEquals(
            "Privé %@ quai",
            ((Map<?, ?>) ((Map<?, ?>) devices.get("\ue000raft")).get("stringUnit")).get("value"));
        assertEquals(
            "Boussole %@ quai",
            ((Map<?, ?>) ((Map<?, ?>) devices.get("🧭raft")).get("stringUnit")).get("value"));
        assertEquals(
            "futurecar",
            localized
                .messages()
                .get("harbor.future.device.only🧭")
                .metadata()
                .get("defaultDevice"));
        assertEquals(
            "other",
            localized
                .messages()
                .get("harbor.future.device.fallback🧭")
                .metadata()
                .get("defaultDevice"));
        assertFalse(localized.messages().containsKey("Private future-device harbor"));
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS
          && (id.contains("disabled-foundation-printf")
              || fixture.path("xcstringsAllDeviceSlots").asBoolean(false))) {
        assertEquals("Quai tranquille", localized.messages().get("harbor.zero").defaultMessage());
        assertEquals(
            "Quai%n tranquille", localized.messages().get("harbor.literal").defaultMessage());
        assertEquals("Quai\ntranquille", localized.messages().get("harbor.line").defaultMessage());
        assertEquals(
            "🧭Cap vers le quai",
            localized.messages().get("harbor.positioned.🧭").defaultMessage());
        assertEquals("Port mobile", localized.messages().get("harbor.device").defaultMessage());
        if (fixture.path("xcstringsAllDeviceSlots").asBoolean(false)) {
          Map<?, ?> axes =
              (Map<?, ?>)
                  localized.messages().get("harbor.device").metadata().get("sourceVariationAxes");
          Map<?, ?> mac = (Map<?, ?>) ((Map<?, ?>) axes.get("device")).get("mac");
          assertEquals("Bureau%%n paisible", ((Map<?, ?>) mac.get("stringUnit")).get("value"));
        }
        assertEquals(
            "{count} balise", localized.messages().get("harbor.plural").variants().get("one"));
        assertEquals(
            "{count} balises", localized.messages().get("harbor.plural").variants().get("other"));
      } else if (format == LocalizationFileFormat.APPLE_XCSTRINGS) {
        if (id.contains("hidden-foundation-argument-slots")) {
          assertEquals(
              "Ouest  {arg1}", localized.messages().get("harbor.implicit").defaultMessage());
          assertEquals(
              "{arg0} quai  {arg2}", localized.messages().get("harbor.middle").defaultMessage());
          assertEquals(
              " {arg1} balises", localized.messages().get("harbor.integer").defaultMessage());
          assertEquals(
              "🧭 {arg1} baie", localized.messages().get("harbor.unicode.🧭").defaultMessage());
        } else {
          assertEquals(
              "Sud \"quai\" 🙂 {arg0}",
              localized.messages().get("escaped.route_🧭").defaultMessage());
          assertEquals(
              "{count} signal léger",
              localized.messages().get("plural.count").variants().get("one"));
          assertEquals(
              "{count} signaux légers",
              localized.messages().get("plural.count").variants().get("other"));
          assertEquals("Touchez {arg0}", localized.messages().get("device.route").defaultMessage());
          assertEquals(
              "{count} route mobile",
              localized.messages().get("device.count").variants().get("one"));
          assertEquals(
              "{count} routes mobiles",
              localized.messages().get("device.count").variants().get("other"));
        }
      } else if (format == LocalizationFileFormat.APPLE_STRINGS) {
        if (id.contains("hidden-foundation-argument-slots")) {
          assertEquals(
              "Ouest  {arg1}", localized.messages().get("harbor.implicit").defaultMessage());
          assertEquals(
              "{arg0} quai  {arg2}", localized.messages().get("harbor.middle").defaultMessage());
          assertEquals(
              " {arg1} balises", localized.messages().get("harbor.integer").defaultMessage());
          assertEquals(
              "🧭 {arg1} baie", localized.messages().get("harbor.unicode.🧭").defaultMessage());
        } else if (id.contains("portable-xml-encoding-boundary")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-xml-long-declaration")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-xml-name-boundary")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("portable-xml-legacy-name")) {
          assertEquals("Marée calme", localized.messages().get("signal").defaultMessage());
        } else if (id.contains("character-reference")) {
          assertEquals(
              "Guide {arg0} vers '<'abri> & calme",
              localized.messages().get("harbor.route&east").defaultMessage());
          assertEquals(
              "Texte &#00000000065; protégé",
              localized.messages().get("literal&#000000065;").defaultMessage());
          assertEquals(
              "Nord\nSud\tEst\rOuest", localized.messages().get("line.break").defaultMessage());
          assertEquals(
              "A&#00000000065;Z", localized.messages().get("escaped.literal").defaultMessage());
        } else if (id.contains("namespaces")) {
          assertEquals(
              "Guide {arg0} vers l’abri",
              localized.messages().get("harbor.route").defaultMessage());
          assertEquals("Calme & sûr", localized.messages().get("quiet.empty").defaultMessage());
          assertEquals(
              "'<'doux> & clair", localized.messages().get("signal&calm").defaultMessage());
        } else if (id.contains("processing-instructions")) {
          assertEquals(
              "Guide {arg0} vers l’abri",
              localized.messages().get("harbor.route").defaultMessage());
          assertEquals("Calme & sûr", localized.messages().get("quiet.empty").defaultMessage());
          assertEquals(
              "'<'doux> & clair", localized.messages().get("wrapped&signal").defaultMessage());
        } else if (id.contains("xml-property-list") && id.contains("direct-dictionary")) {
          assertEquals("Quai & abri", localized.messages().get("direct&route").defaultMessage());
          assertEquals(
              "Ouest '<'doux> & sûr", localized.messages().get("direct<&>cdata").defaultMessage());
          assertEquals("Valeur ajoutée", localized.messages().get("direct.empty").defaultMessage());
        } else if (id.contains("xml-property-list")) {
          assertEquals("Quai & abri sûr", localized.messages().get("escaped&key").defaultMessage());
          assertEquals(
              "Port ]]> '<'doux> & sûr", localized.messages().get("cdata<&>key").defaultMessage());
          assertEquals(
              "Sud '<'doux> & sûr quai", localized.messages().get("mixed.key").defaultMessage());
          assertEquals("Valeur ajoutée", localized.messages().get("empty.key").defaultMessage());
          assertEquals("Ouvert & sûr", localized.messages().get("explicit.empty").defaultMessage());
          assertEquals("Ouest\nEst", localized.messages().get("newline.key").defaultMessage());
          assertEquals("Prêt {arg0}", localized.messages().get("placeholder.key").defaultMessage());
          assertEquals("NordSud", localized.messages().get("percent.newline").defaultMessage());
          assertEquals("Prêt 75%", localized.messages().get("percent.literal").defaultMessage());
          assertEquals("Équipe 🙂", localized.messages().get("unicode.🧭").defaultMessage());
        } else {
          assertEquals("Sud \"quai\" 🙂", localized.messages().get("double.key").defaultMessage());
          assertEquals(
              "L'équipage \"calme\"", localized.messages().get("single.key").defaultMessage());
          assertEquals(
              "Raccourci traduit", localized.messages().get("shorthand.key").defaultMessage());
          assertEquals("Chemin {arg0}", localized.messages().get("escaped.key").defaultMessage());
          assertEquals("NordSud", localized.messages().get("line.break").defaultMessage());
        }
      } else if (format == LocalizationFileFormat.GETTEXT_PO
          && fixture.has("gettextDomainCompiled")) {
        assertEquals(
            "Main harbor",
            localized.messages().get("shared.route@domain=messages").defaultMessage());
        assertEquals(
            "Quai ouvert",
            localized.messages().get("shared.route@domain=north.fr").defaultMessage());
        assertEquals(
            "Южный проход",
            localized.messages().get("shared.route@domain=stock%25ru").defaultMessage());
        assertEquals(
            "{arg0} home beacon",
            localized.messages().get("%d signal@domain=messages").variants().get("one"));
        assertEquals(
            "{arg0} home beacons",
            localized.messages().get("%d signal@domain=messages").variants().get("other"));
        assertEquals(
            "{arg0} balise claire",
            localized.messages().get("%d signal@domain=north.fr").variants().get("one"));
        assertEquals(
            "{arg0} balises claires",
            localized.messages().get("%d signal@domain=north.fr").variants().get("many"));
        assertEquals(
            "{arg0} южный маяк",
            localized.messages().get("%d signal@domain=stock%25ru").variants().get("one"));
        assertEquals(
            "{arg0} южных маяка",
            localized.messages().get("%d signal@domain=stock%25ru").variants().get("few"));
        assertEquals(
            "{arg0} южных маяков",
            localized.messages().get("%d signal@domain=stock%25ru").variants().get("many"));
        assertEquals(
            "en-GB",
            JSON.valueToTree(localized.messages().get("%d signal@domain=messages").metadata())
                .at("/gettextDomainHeader/locale")
                .asText());
        assertEquals(
            "fr-FR",
            JSON.valueToTree(localized.messages().get("%d signal@domain=north.fr").metadata())
                .at("/gettextDomainHeader/locale")
                .asText());
        assertEquals(
            "ru-RU",
            JSON.valueToTree(localized.messages().get("%d signal@domain=stock%25ru").metadata())
                .at("/gettextDomainHeader/locale")
                .asText());
      } else if (format == LocalizationFileFormat.GETTEXT_PO && "CP1252".equals(encoding)) {
        assertEquals("“Facture” — € {arg0}", localized.messages().get("prix.€").defaultMessage());
        assertEquals(
            "“une” – {arg0}", localized.messages().get("caisse.count").variants().get("one"));
        assertEquals(
            "“plusieurs” — {arg0}",
            localized.messages().get("caisse.count").variants().get("many"));
        assertEquals("Total € — “sûr”", localized.messages().get("escaped.euro").defaultMessage());
        assertEquals("Route – ouverte", localized.messages().get("empty.route").defaultMessage());
      } else if (format == LocalizationFileFormat.GETTEXT_PO && "US-ASCII".equals(encoding)) {
        assertEquals(
            "Safe signal {arg0}", localized.messages().get("plain.signal").defaultMessage());
        assertEquals(
            "One safe crate {arg0}", localized.messages().get("plain.count").variants().get("one"));
        assertEquals(
            "Several safe crates {arg0}",
            localized.messages().get("plain.count").variants().get("other"));
        assertEquals("Open harbor route", localized.messages().get("plain.empty").defaultMessage());
      } else if (id.startsWith("gettext-source-skeleton-preserves-metadata-whitespace-")) {
        LocalizationMessage message = localized.messages().get("Quiet bay");
        assertEquals("Marée sûre", message.defaultMessage());
        if (id.contains("control-notes")) {
          assertEquals("neutral", message.description());
          assertEquals(List.of("translator"), message.metadata().get("translatorComments"));
          assertEquals(List.of("first\u001clast"), message.metadata().get("references"));
          assertEquals(List.of("no-c-format"), message.metadata().get("flags"));
        } else {
          assertEquals(id.contains("latin1") ? "\u00a0" : "\u202f", message.description());
          assertEquals(
              List.of(id.contains("latin1") ? "\u0085" : "\u2007"),
              message.metadata().get("translatorComments"));
          assertEquals(List.of("first\u00a0last"), message.metadata().get("references"));
          assertEquals(List.of("\u0085no-c-format\u0085"), message.metadata().get("flags"));
        }
      } else if (id.startsWith("gettext-source-skeleton-preserves-domain-whitespace-")) {
        LocalizationMessage message = localized.messages().get("Quiet bay");
        String domain = fixture.path("gettextSourceDomain").asText();
        String separator = domain.substring(domain.length() - 1);
        assertEquals("Marée sûre", message.defaultMessage());
        assertEquals(domain, message.metadata().get("gettextDomain"));
        assertEquals(List.of("north" + separator + "dock"), message.metadata().get("references"));
        assertEquals(List.of(separator), message.metadata().get("flags"));
      } else if (format == LocalizationFileFormat.GETTEXT_PO && "ISO-8859-1".equals(encoding)) {
        assertEquals("crème {arg0}", localized.messages().get("café.signal").defaultMessage());
        assertEquals("quai animé", localized.messages().get("Harbor route").defaultMessage());
        assertEquals("été", localized.messages().get("empty.signal").defaultMessage());
      } else if ("gettext-source-skeleton-preserves-horizontal-plural-formula".equals(id)
          || "gettext-source-skeleton-preserves-leading-zero-plural-decimals".equals(id)) {
        assertEquals(
            "{arg0} port beacon",
            localized.messages().get("harbor.beacon_count").variants().get("one"));
        assertEquals(
            "{arg0} port beacons",
            localized.messages().get("harbor.beacon_count").variants().get("other"));
      } else if (format == LocalizationFileFormat.GETTEXT_PO) {
        assertEquals("Lueur {arg0}", localized.messages().get("harbor.signal").defaultMessage());
        assertEquals(
            "{arg0} caisse légère", localized.messages().get("crate.count").variants().get("one"));
        assertEquals(
            "{arg0} caisses lourdes",
            localized.messages().get("crate.count").variants().get("many"));
        assertEquals("Quai \"Sud\"", localized.messages().get("Dock \"North\"").defaultMessage());
        assertEquals("Ouest\nEst", localized.messages().get("route.lines").defaultMessage());
        assertEquals(
            "Vague activée", localized.messages().get("untranslated.wave").defaultMessage());
      } else if (id.startsWith("properties-source-skeleton-preserves-terminal-backslash-")
          || id.startsWith("properties-source-skeleton-preserves-comment-whitespace-")) {
        for (Map.Entry<String, String> entry : translations.entrySet()) {
          assertEquals(
              id + ": translated terminal property identity " + entry.getKey(),
              entry.getValue(),
              localized.messages().get(entry.getKey()).defaultMessage());
        }
        if (id.contains("comment-whitespace")) {
          if (id.contains("java-control")) {
            assertEquals("note", localized.messages().get("route").description());
            assertNull(localized.messages().get("anchor").description());
            assertEquals("note", localized.messages().get("pier").description());
          } else if (id.contains("crlf-mixed")) {
            assertEquals("\u2007 north\u2007", localized.messages().get("route").description());
            assertEquals("clear", localized.messages().get("anchor").description());
          } else {
            assertEquals("\u0085", localized.messages().get("route").description());
            assertEquals("\u00a0", localized.messages().get("anchor").description());
          }
        }
      } else if (format == LocalizationFileFormat.YAML) {
        assertEquals("Bienvenue : amie", localized.messages().get("welcome").defaultMessage());
        assertEquals(
            "Première ligne\nDeuxième ligne",
            localized.messages().get("group/block").defaultMessage());
      } else if ("ISO-8859-1".equals(encoding)) {
        assertEquals("prix 5 € 🙂", localized.messages().get("café").defaultMessage());
        assertEquals("crème {arg0}", localized.messages().get("escaped.key").defaultMessage());
        assertEquals("café brûlant", localized.messages().get("continued").defaultMessage());
        assertEquals("été", localized.messages().get("empty.key").defaultMessage());
      } else {
        assertEquals(
            "quai = abri : # calme", localized.messages().get("escaped key:=").defaultMessage());
        assertEquals("Prêt {arg0}", localized.messages().get("unicode.key").defaultMessage());
        assertEquals(
            "route ouest calme", localized.messages().get("continued.value").defaultMessage());
        assertEquals("valeur ajoutée", localized.messages().get("key.only").defaultMessage());
        assertEquals("Ouest\nSud", localized.messages().get("line.break").defaultMessage());
      }

      try {
        LocalizationFileConverters.renderSkeleton(skeleton, Map.of("missing", "value"));
        fail(id + ": unknown source slot must fail");
      } catch (LocalizationParseException exception) {
        assertEquals(id, "UNKNOWN_SKELETON_SLOT", exception.code());
      }
      if (format == LocalizationFileFormat.ANDROID && localized.messages().containsKey("rich")) {
        try {
          LocalizationFileConverters.renderSkeleton(skeleton, Map.of("rich", "Unstyled target"));
          fail(id + ": removing inline markup must fail");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_SKELETON_MARKUP", exception.code());
        }
      }
      for (JsonNode rejected : fixture.path("androidSkeletonReject")) {
        Map<String, String> invalidTranslations = new LinkedHashMap<>();
        rejected
            .path("translations")
            .fields()
            .forEachRemaining(
                entry -> invalidTranslations.put(entry.getKey(), entry.getValue().asText()));
        try {
          LocalizationFileConverters.renderSkeleton(skeleton, invalidTranslations);
          fail(id + ": unsafe inline-token mutation must fail");
        } catch (LocalizationParseException exception) {
          assertEquals(id, rejected.path("error").asText(), exception.code());
        }
      }
      for (JsonNode rejected : fixture.path("xcstringsSkeletonReject")) {
        Map<String, String> invalidTranslations = new LinkedHashMap<>();
        rejected
            .path("translations")
            .fields()
            .forEachRemaining(
                entry -> invalidTranslations.put(entry.getKey(), entry.getValue().asText()));
        try {
          LocalizationFileConverters.renderSkeleton(skeleton, invalidTranslations);
          fail(id + ": missing/duplicated Xcode substitution must fail");
        } catch (LocalizationParseException exception) {
          assertEquals(id, rejected.path("error").asText(), exception.code());
        }
      }
      if (format == LocalizationFileFormat.GETTEXT_PO && "ISO-8859-1".equals(encoding)) {
        try {
          String legacyIdentity =
              id.startsWith("gettext-source-skeleton-preserves-metadata-whitespace-")
                      || id.startsWith("gettext-source-skeleton-preserves-domain-whitespace-")
                  ? "Quiet bay"
                  : "café.signal";
          String invalidValue = "Quiet bay".equals(legacyIdentity) ? "euro €" : "euro € {arg0}";
          LocalizationFileConverters.renderSkeleton(skeleton, Map.of(legacyIdentity, invalidValue));
          fail(id + ": unrepresentable legacy target must fail");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_GETTEXT_ENCODING", exception.code());
        }
      }
      if (format == LocalizationFileFormat.GETTEXT_PO && "CP1252".equals(encoding)) {
        try {
          LocalizationFileConverters.renderSkeleton(skeleton, Map.of("prix.€", "signal 🙂"));
          fail(id + ": unmappable Windows code-page target must fail");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_GETTEXT_ENCODING", exception.code());
        }
      }
      if (format == LocalizationFileFormat.GETTEXT_PO && "US-ASCII".equals(encoding)) {
        try {
          LocalizationFileConverters.renderSkeleton(skeleton, Map.of("plain.empty", "café"));
          fail(id + ": non-ASCII target must fail");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_GETTEXT_ENCODING", exception.code());
        }
      }
      LocalizationSourceSkeleton.LocalizationSourceSlot first = skeleton.slots().get(0);
      List<LocalizationSourceSkeleton.LocalizationSourceSlot> slots =
          new ArrayList<>(skeleton.slots());
      slots.set(
          0,
          new LocalizationSourceSkeleton.LocalizationSourceSlot(
              first.id(), first.variant(), first.start(), original.length + 1));
      LocalizationSourceSkeleton invalid =
          new LocalizationSourceSkeleton(
              skeleton.schemaVersion(),
              skeleton.sourceFormat(),
              skeleton.encoding(),
              skeleton.source(),
              skeleton.androidResourcePath(),
              skeleton.androidFeatureFlags(),
              slots);
      try {
        LocalizationFileConverters.renderSkeleton(invalid, Map.of());
        fail(id + ": out-of-range source ownership must fail");
      } catch (LocalizationParseException exception) {
        assertEquals(id, "INVALID_SKELETON", exception.code());
      }
      for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : skeleton.slots()) {
        if (slot.appleObjectIndex() != null) {
          LocalizationCatalog originalCatalog = LocalizationFileConverters.parse(format, original);
          LocalizationCatalog partial =
              LocalizationFileConverters.parse(
                  format,
                  LocalizationFileConverters.renderSkeleton(
                      skeleton,
                      Map.of(slot.translationKey(), translations.get(slot.translationKey()))));
          if (format == LocalizationFileFormat.APPLE_STRINGS) {
            for (Map.Entry<String, LocalizationMessage> message :
                originalCatalog.messages().entrySet()) {
              if (!message.getKey().equals(slot.id())) {
                assertEquals(
                    id + ": untranslated shared string alias",
                    message.getValue(),
                    partial.messages().get(message.getKey()));
              }
            }
          } else {
            for (Map.Entry<String, String> category :
                originalCatalog.messages().get(slot.id()).variants().entrySet()) {
              if (!category.getKey().equals(slot.variant())) {
                assertEquals(
                    id + ": untranslated shared plural alias",
                    category.getValue(),
                    partial.messages().get(slot.id()).variants().get(category.getKey()));
              }
            }
          }
          List<LocalizationSourceSkeleton.LocalizationSourceSlot> forged =
              new ArrayList<>(skeleton.slots());
          forged.set(
              forged.indexOf(slot),
              new LocalizationSourceSkeleton.LocalizationSourceSlot(
                  slot.id(),
                  slot.selector(),
                  slot.variant(),
                  slot.start(),
                  slot.end(),
                  slot.appleObjectIndex() + 1));
          try {
            LocalizationFileConverters.renderSkeleton(
                new LocalizationSourceSkeleton(
                    skeleton.schemaVersion(),
                    skeleton.sourceFormat(),
                    skeleton.encoding(),
                    skeleton.source(),
                    forged),
                translations);
            fail(id + ": forged shared binary object identity must fail");
          } catch (LocalizationParseException exception) {
            assertEquals(id, "INVALID_SKELETON", exception.code());
          }
          break;
        }
      }
      checked++;
    }
    assertEquals(
        "Every portable source skeleton must be exercised",
        manifest.path("sourceSkeletons").size(),
        checked);
    for (JsonNode fixture : manifest.path("sourceSkeletonErrors")) {
      String id = fixture.path("id").asText();
      try {
        byte[] source =
            fixture.has("encoding")
                ? encode(
                    root.resolve(fixture.path("input").asText()),
                    fixture.path("encoding").asText(),
                    fixture.path("lineEndings").asText())
                : Files.readAllBytes(root.resolve(fixture.path("input").asText()));
        LocalizationFileConverters.extractSkeleton(
            LocalizationFileFormat.fromId(fixture.path("format").asText()), source);
        fail(id + ": unsupported source ownership must fail closed");
      } catch (LocalizationParseException exception) {
        assertEquals(id, fixture.path("error").asText(), exception.code());
      }
    }
  }

  @Test
  public void allSharedBinaryAppleSourceSkeletons() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("appleBinarySourceSkeletons")) {
      String id = fixture.path("id").asText();
      LocalizationFileFormat format =
          LocalizationFileFormat.fromId(fixture.path("format").asText());
      byte[] original = Files.readAllBytes(root.resolve(fixture.path("input").asText()));
      LocalizationSourceSkeleton skeleton =
          LocalizationFileConverters.extractSkeleton(format, original);
      JsonNode expected = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
      assertEquals(id + ": portable binary object ownership", expected, JSON.valueToTree(skeleton));
      assertArrayEquals(
          id + ": untranslated binary bytes",
          original,
          LocalizationFileConverters.renderSkeleton(skeleton, Map.of()));

      Map<String, String> translations = new LinkedHashMap<>();
      JSON.readTree(root.resolve(fixture.path("translations").asText()).toFile())
          .fields()
          .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
      byte[] localized = LocalizationFileConverters.renderSkeleton(skeleton, translations);
      assertArrayEquals(
          id + ": lossless binary object-table replacement",
          Files.readAllBytes(root.resolve(fixture.path("localized").asText())),
          localized);
      LocalizationFileConverters.parse(format, localized);
      try {
        LocalizationFileConverters.renderSkeleton(skeleton, Map.of("missing", "translation"));
        fail(id + ": unknown binary object ownership must fail");
      } catch (LocalizationParseException exception) {
        assertEquals(id, "UNKNOWN_SKELETON_SLOT", exception.code());
      }
      List<LocalizationSourceSkeleton.LocalizationSourceSlot> invalid =
          new ArrayList<>(skeleton.slots());
      LocalizationSourceSkeleton.LocalizationSourceSlot first = invalid.remove(0);
      invalid.add(
          0,
          new LocalizationSourceSkeleton.LocalizationSourceSlot(
              first.id(), first.selector(), first.variant(), first.start(), first.end() + 1));
      try {
        LocalizationFileConverters.renderSkeleton(
            new LocalizationSourceSkeleton(
                skeleton.schemaVersion(),
                skeleton.sourceFormat(),
                skeleton.encoding(),
                skeleton.source(),
                invalid),
            Map.of());
        fail(id + ": forged binary object ownership must fail");
      } catch (LocalizationParseException exception) {
        assertEquals(id, "INVALID_SKELETON", exception.code());
      }
      checked++;
    }
    assertTrue("Binary Foundation source templates must not be silently skipped", checked > 0);
    assertEquals(manifest.path("appleBinarySourceSkeletons").size(), checked);
  }

  @Test
  public void allSharedAndroidOverlaySourceSkeletons() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("androidOverlaySourceSkeletons")) {
      String id = fixture.path("id").asText();
      List<AndroidResourceInput> inputs = new ArrayList<>();
      for (JsonNode input : fixture.path("inputs")) {
        inputs.add(
            new AndroidResourceInput(
                input.path("sourceSet").asText(),
                input.path("resourcePath").asText(),
                input.has("encoding")
                    ? encode(
                        root.resolve(input.path("input").asText()),
                        input.path("encoding").asText(),
                        input.path("lineEndings").asText())
                    : Files.readAllBytes(root.resolve(input.path("input").asText()))));
      }
      List<String> selectedProducts = androidSelectedProducts(fixture);
      boolean externalMacros = fixture.path("androidExternalMacros").asBoolean(false);
      String applicationPackage =
          fixture.has("androidApplicationPackage")
              ? fixture.path("androidApplicationPackage").asText()
              : null;
      AndroidOverlaySourceSkeleton skeleton =
          selectedProducts == null && applicationPackage == null
              ? LocalizationFileConverters.extractAndroidOverlaySkeleton(inputs)
              : LocalizationFileConverters.extractAndroidOverlaySkeleton(
                  inputs, List.of(), selectedProducts, applicationPackage);
      JsonNode expected = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
      assertEquals(
          id + ": source-owned Android overlay skeleton", expected, JSON.valueToTree(skeleton));
      List<AndroidResourceInput> unchanged =
          LocalizationFileConverters.renderAndroidOverlaySkeleton(skeleton, Map.of());
      for (int index = 0; index < inputs.size(); index++) {
        assertArrayEquals(
            id + ": untouched original source " + index,
            inputs.get(index).source(),
            unchanged.get(index).source());
      }

      Map<String, String> translations = new LinkedHashMap<>();
      JSON.readTree(root.resolve(fixture.path("translations").asText()).toFile())
          .fields()
          .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
      List<AndroidResourceInput> localized =
          LocalizationFileConverters.renderAndroidOverlaySkeleton(skeleton, translations);
      for (int index = 0; index < inputs.size(); index++) {
        JsonNode input = fixture.path("inputs").get(index);
        assertEquals(input.path("sourceSet").asText(), localized.get(index).sourceSet());
        assertEquals(input.path("resourcePath").asText(), localized.get(index).resourcePath());
        assertArrayEquals(
            id + ": localized source " + index,
            input.has("encoding")
                ? encode(
                    root.resolve(input.path("localized").asText()),
                    input.path("encoding").asText(),
                    input.path("lineEndings").asText())
                : Files.readAllBytes(root.resolve(input.path("localized").asText())),
            localized.get(index).source());
      }
      if (id.startsWith("android-overlay-source-portable-android-product-unicode-whitespace-")) {
        assertArrayEquals(
            id + ": fully shadowed library stays byte-identical",
            inputs.get(0).source(),
            localized.get(0).source());
        assertEquals(
            id + ": original winner catalog",
            JSON.readTree(root.resolve(fixture.path("catalog").asText()).toFile()),
            JSON.valueToTree(
                LocalizationFileConverters.parseAndroidOverlay(
                    inputs, Map.of(), selectedProducts, null)));
        LocalizationCatalog localizedCatalog =
            LocalizationFileConverters.parseAndroidOverlay(
                localized, Map.of(), selectedProducts, null);
        assertEquals("Marée choisie", localizedCatalog.messages().get("signal").defaultMessage());
        assertEquals("Ancre sûre", localizedCatalog.messages().get("anchor").defaultMessage());
        assertEquals(selectedProducts, skeleton.androidSelectedProducts());
        assertEquals(
            "signal@product=" + selectedProducts.getFirst(),
            skeleton.androidRuntimeSlotOwners().get("signal"));

        for (JsonNode rejected : fixture.path("rejectBuilds")) {
          List<String> products = new ArrayList<>();
          rejected.path("androidSelectedProducts").forEach(value -> products.add(value.asText()));
          try {
            LocalizationFileConverters.extractAndroidOverlaySkeleton(
                inputs, List.of(), products, null);
            fail(id + ": invalid selected-product build must fail closed");
          } catch (LocalizationParseException exception) {
            assertEquals(id, rejected.path("error").asText(), exception.code());
          }
        }
        for (JsonNode rejected : fixture.path("reject")) {
          Map<String, String> invalid = new LinkedHashMap<>();
          rejected
              .path("translations")
              .fields()
              .forEachRemaining(entry -> invalid.put(entry.getKey(), entry.getValue().asText()));
          try {
            LocalizationFileConverters.renderAndroidOverlaySkeleton(skeleton, invalid);
            fail(id + ": internal selected-product source identity must fail closed");
          } catch (LocalizationParseException exception) {
            assertEquals(id, rejected.path("error").asText(), exception.code());
          }
        }
        Map<String, String> owners = new LinkedHashMap<>(skeleton.androidRuntimeSlotOwners());
        owners.put("signal", owners.get("anchor"));
        try {
          LocalizationFileConverters.renderAndroidOverlaySkeleton(
              new AndroidOverlaySourceSkeleton(
                  1,
                  "android",
                  skeleton.sources(),
                  selectedProducts,
                  owners,
                  null,
                  skeleton.androidMacroOwners()),
              translations);
          fail(id + ": duplicated Unicode-product source ownership must fail closed");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_ANDROID_OVERLAY_SKELETON", exception.code());
        }
        checked++;
        continue;
      }
      AndroidResourceInput library =
          localized.stream()
              .filter(source -> "library".equals(source.sourceSet()))
              .findFirst()
              .orElseThrow();
      assertArrayEquals(
          id + ": fully shadowed library stays byte-identical",
          inputs.get(0).source(),
          library.source());
      AndroidResourceInput main =
          localized.stream()
              .filter(source -> "main".equals(source.sourceSet()))
              .findFirst()
              .orElseThrow();
      String lower =
          LocalizationFileConverters.decode(
              main.source(),
              LocalizationFileConverters.xmlCharset(LocalizationFileFormat.ANDROID, main.source()));
      if (!externalMacros) {
        assertTrue(
            id + ": overridden scalar remains byte-identical",
            lower.contains(
                "<string name=\"shared_signal\">Main <marker:g id=\"pilot\" example=\"M-1\">%1$s</marker:g></string>"));
        assertTrue(
            id + ": overridden product remains byte-identical",
            lower.contains(
                "<string name=\"product_signal\" product=\"tablet\">Main tablet beacon</string>"));
        assertTrue(
            id + ": nontranslatable tombstone preserves lower source",
            lower.contains("Lower coast"));
        assertTrue(
            id + ": overridden array preserves lower source",
            lower.contains("<item>Main north</item>"));
        assertTrue(
            id + ": overridden plural preserves lower source",
            lower.contains("<item quantity=\"one\">%1$d main light</item>"));
      } else {
        assertTrue(
            id + ": expanded macro style remains protected", lower.contains("<b tone=\"bright\">"));
        assertTrue(
            id + ": expanded macro placeholder keeps winning definition example",
            lower.contains("<xliff:g id=\"pilot\" example=\"D&amp;7\">%1$s</xliff:g>"));
      }
      AndroidResourceInput buildType =
          localized.stream()
              .filter(source -> "build_type".equals(source.sourceSet()))
              .findFirst()
              .orElseThrow();
      String upper =
          LocalizationFileConverters.decode(
              buildType.source(),
              LocalizationFileConverters.xmlCharset(
                  LocalizationFileFormat.ANDROID, buildType.source()));
      if (externalMacros) {
        assertArrayEquals(
            id + ": winning macro declarations remain byte-identical",
            inputs.get(2).source(),
            buildType.source());
        assertEquals(applicationPackage, skeleton.androidApplicationPackage());
        assertEquals("build_type", skeleton.androidMacroOwners().get("harbor_phrase").sourceSet());
      } else {
        assertTrue(
            id + ": protected placeholder preserves original lexical attributes",
            upper.contains("<marker:g id=\"pilot\" example=\"D&amp;2\">%1$s</marker:g>"));
      }
      assertEquals(
          id + ": original winner catalog",
          JSON.readTree(root.resolve(fixture.path("catalog").asText()).toFile()),
          JSON.valueToTree(
              LocalizationFileConverters.parseAndroidOverlay(
                  inputs, Map.of(), selectedProducts, applicationPackage)));
      for (JsonNode rejected : fixture.path("rejectBuilds")) {
        List<String> products = new ArrayList<>();
        rejected.path("androidSelectedProducts").forEach(value -> products.add(value.asText()));
        try {
          LocalizationFileConverters.extractAndroidOverlaySkeleton(
              inputs, List.of(), products, null);
          fail(id + ": invalid selected-product build must fail closed");
        } catch (LocalizationParseException exception) {
          assertEquals(id, rejected.path("error").asText(), exception.code());
        }
      }
      LocalizationCatalog localizedCatalog =
          LocalizationFileConverters.parseAndroidOverlay(
              localized, Map.of(), selectedProducts, applicationPackage);
      String productId = externalMacros ? "macro_product" : "product_signal";
      assertEquals(
          translations.get(productId), localizedCatalog.messages().get(productId).defaultMessage());
      if (selectedProducts == null) {
        assertEquals(
            translations.get(productId + "@product=tablet"),
            localizedCatalog.messages().get(productId + "@product=tablet").defaultMessage());
      } else {
        assertFalse(localizedCatalog.messages().containsKey(productId + "@product=tablet"));
        if (!externalMacros) {
          assertEquals(
              translations.get("product_routes[0]"),
              localizedCatalog.messages().get("product_routes[0]").defaultMessage());
          assertEquals(
              translations.get("product_lights#one"),
              localizedCatalog.messages().get("product_lights").variants().get("one"));
        }
        assertEquals(selectedProducts, skeleton.androidSelectedProducts());
        assertEquals(
            selectedProducts.contains("tablet") ? productId + "@product=tablet" : productId,
            skeleton.androidRuntimeSlotOwners().get(productId));
        Map<String, String> missing = new LinkedHashMap<>(skeleton.androidRuntimeSlotOwners());
        missing.remove(productId);
        try {
          LocalizationFileConverters.renderAndroidOverlaySkeleton(
              new AndroidOverlaySourceSkeleton(
                  1,
                  "android",
                  skeleton.sources(),
                  selectedProducts,
                  missing,
                  applicationPackage,
                  skeleton.androidMacroOwners()),
              translations);
          fail(id + ": incomplete selected-product ownership must fail closed");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_ANDROID_OVERLAY_SKELETON", exception.code());
        }
        Map<String, String> duplicate = new LinkedHashMap<>(skeleton.androidRuntimeSlotOwners());
        duplicate.put(productId, duplicate.get(externalMacros ? "macro_signal" : "main_anchor"));
        try {
          LocalizationFileConverters.renderAndroidOverlaySkeleton(
              new AndroidOverlaySourceSkeleton(
                  1,
                  "android",
                  skeleton.sources(),
                  selectedProducts,
                  duplicate,
                  applicationPackage,
                  skeleton.androidMacroOwners()),
              translations);
          fail(id + ": duplicated selected-product source ownership must fail closed");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_ANDROID_OVERLAY_SKELETON", exception.code());
        }
      }
      if (!externalMacros) {
        assertFalse(localizedCatalog.messages().containsKey("masked_signal"));
      } else {
        Map<String, AndroidOverlaySourceSkeleton.AndroidMacroOwner> forged =
            new LinkedHashMap<>(skeleton.androidMacroOwners());
        forged.put(
            "harbor_phrase",
            new AndroidOverlaySourceSkeleton.AndroidMacroOwner(
                "library", "src/library/res/values/definitions.xml"));
        try {
          LocalizationFileConverters.renderAndroidOverlaySkeleton(
              new AndroidOverlaySourceSkeleton(
                  1,
                  "android",
                  skeleton.sources(),
                  selectedProducts,
                  skeleton.androidRuntimeSlotOwners(),
                  applicationPackage,
                  forged),
              translations);
          fail(id + ": forged external macro definition ownership must fail closed");
        } catch (LocalizationParseException exception) {
          assertEquals(id, "INVALID_ANDROID_OVERLAY_SKELETON", exception.code());
        }
      }
      for (JsonNode rejected : fixture.path("reject")) {
        Map<String, String> invalid = new LinkedHashMap<>();
        rejected
            .path("translations")
            .fields()
            .forEachRemaining(entry -> invalid.put(entry.getKey(), entry.getValue().asText()));
        try {
          LocalizationFileConverters.renderAndroidOverlaySkeleton(skeleton, invalid);
          fail(id + ": expected rejected overlay source ownership");
        } catch (LocalizationParseException exception) {
          assertEquals(id, rejected.path("error").asText(), exception.code());
        }
      }
      for (JsonNode rejected : fixture.path("rejectMarkup")) {
        Map<String, String> invalid = new LinkedHashMap<>();
        rejected
            .path("translations")
            .fields()
            .forEachRemaining(entry -> invalid.put(entry.getKey(), entry.getValue().asText()));
        try {
          LocalizationFileConverters.renderAndroidOverlaySkeleton(skeleton, invalid);
          fail(id + ": macro-expanded style/protected ownership must fail closed");
        } catch (LocalizationParseException exception) {
          assertEquals(id, rejected.path("error").asText(), exception.code());
        }
      }
      checked++;
    }
    assertEquals(
        "Every shared multi-file Android source template must be executed",
        manifest.path("androidOverlaySourceSkeletons").size(),
        checked);
  }

  @Test
  public void allSharedAndroidResourceOverlays() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("androidOverlays")) {
      String id = fixture.path("id").asText();
      List<AndroidResourceInput> sources = new ArrayList<>();
      for (JsonNode input : fixture.path("inputs")) {
        sources.add(
            new AndroidResourceInput(
                input.path("sourceSet").asText(),
                input.path("resourcePath").asText(),
                input.has("encoding")
                    ? encode(
                        root.resolve(input.path("input").asText()),
                        input.path("encoding").asText(),
                        input.path("lineEndings").asText())
                    : Files.readAllBytes(root.resolve(input.path("input").asText()))));
      }
      if (fixture.has("error")) {
        try {
          parseOverlayFixture(sources, fixture);
          fail(id + ": expected stable overlay error " + fixture.path("error").asText());
        } catch (LocalizationParseException exception) {
          assertEquals(id, fixture.path("error").asText(), exception.code());
        }
      } else {
        JsonNode expected = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
        assertEquals(id, expected, JSON.valueToTree(parseOverlayFixture(sources, fixture)));
      }
      checked++;
    }
    assertEquals(
        "Every language-neutral Android overlay must be executed",
        manifest.path("androidOverlays").size(),
        checked);
  }

  private static List<String> androidSelectedProducts(JsonNode fixture) {
    if (!fixture.has("androidSelectedProducts")) {
      return null;
    }
    List<String> products = new ArrayList<>();
    fixture.path("androidSelectedProducts").forEach(product -> products.add(product.asText()));
    return products;
  }

  private static LocalizationCatalog parseFixture(
      LocalizationFileFormat format,
      byte[] bytes,
      Charset charset,
      String resourcePath,
      Map<String, Boolean> flags,
      List<AndroidFeatureFlag> definitions,
      List<String> products,
      String applicationPackage) {
    return definitions == null
        ? LocalizationFileConverters.parse(
            format, bytes, charset, resourcePath, flags, products, applicationPackage)
        : LocalizationFileConverters.parseWithAndroidFeatureFlags(
            format, bytes, charset, resourcePath, definitions, products, applicationPackage);
  }

  private static LocalizationCatalog parseOverlayFixture(
      List<AndroidResourceInput> sources, JsonNode fixture) {
    List<AndroidFeatureFlag> definitions = androidFeatureFlagDefinitions(fixture);
    String applicationPackage =
        fixture.has("androidApplicationPackage")
            ? fixture.get("androidApplicationPackage").asText()
            : null;
    return definitions == null
        ? LocalizationFileConverters.parseAndroidOverlay(
            sources,
            androidFeatureFlags(fixture),
            androidSelectedProducts(fixture),
            applicationPackage)
        : LocalizationFileConverters.parseAndroidOverlayWithFeatureFlags(
            sources, definitions, androidSelectedProducts(fixture), applicationPackage);
  }

  private static List<AndroidFeatureFlag> androidFeatureFlagDefinitions(JsonNode fixture) {
    if (!fixture.has("androidFeatureFlagDefinitions")) {
      return null;
    }
    List<AndroidFeatureFlag> result = new ArrayList<>();
    for (JsonNode definition : fixture.path("androidFeatureFlagDefinitions")) {
      JsonNode value = definition.get("value");
      result.add(
          new AndroidFeatureFlag(
              definition.get("name").asText(),
              "read_only".equals(definition.get("mode").asText()),
              value.isNull() ? null : value.asBoolean()));
    }
    return result;
  }

  @Test
  public void gettextPluralMessagesMatchRealIcuRuntimeSelection() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    for (JsonNode fixture : manifest.get("cases")) {
      if (!"gettext_po".equals(fixture.path("format").asText()) || !fixture.has("expected")) {
        continue;
      }
      JsonNode catalog = JSON.readTree(root.resolve(fixture.get("expected").asText()).toFile());
      var messages = catalog.path("messages").fields();
      while (messages.hasNext()) {
        Map.Entry<String, JsonNode> message = messages.next();
        JsonNode descriptor = message.getValue();
        JsonNode metadata = descriptor.path("metadata");
        if (!metadata.has("gettextPluralForms")) {
          continue;
        }
        ULocale locale =
            ULocale.forLanguageTag(
                metadata
                    .path("gettextDomainHeader")
                    .path("locale")
                    .asText(catalog.path("locale").asText("und")));
        LongUnaryOperator expression =
            GettextPluralExpression.parse(
                metadata.path("gettextPluralForms").path("expression").asText());
        MessageFormat formatted =
            new MessageFormat(descriptor.path("defaultMessage").asText(), locale);
        List<Integer> samples = new ArrayList<>();
        for (int sample = 0; sample <= 1000; sample++) {
          samples.add(sample);
        }
        samples.addAll(EXTENDED_GETTEXT_SAMPLES);
        for (JsonNode sample : fixture.path("gettextRuntimeSamples")) {
          int value = sample.asInt();
          if (!samples.contains(value)) {
            samples.add(value);
          }
        }
        for (int sample : samples) {
          assertGettextRuntimeSelection(
              fixture,
              message.getKey(),
              descriptor,
              locale,
              formatted,
              sample,
              expression.applyAsLong(sample));
        }
        for (JsonNode sample : fixture.path("gettextFractionalSamples")) {
          assertGettextRuntimeSelection(
              fixture,
              message.getKey(),
              descriptor,
              locale,
              formatted,
              sample.path("value").asDouble(),
              sample.path("index").asLong());
        }
        for (JsonNode sample : fixture.path("gettextDomainRuntimeSamples")) {
          if (!metadata
                  .path("gettextDomain")
                  .asText("messages")
                  .equals(sample.path("domain").asText())
              || !metadata.path("sourceMessage").asText().equals(sample.path("message").asText())) {
            continue;
          }
          Map<String, Object> arguments = new LinkedHashMap<>();
          int value = sample.path("value").asInt();
          arguments.put("count", value);
          for (JsonNode placeholder : descriptor.path("placeholders")) {
            arguments.put(placeholder.path("name").asText(), value);
          }
          String nativePattern = descriptor.path("defaultMessage").asText();
          for (JsonNode placeholder : descriptor.path("placeholders")) {
            if ("integer".equals(placeholder.path("kind").asText())) {
              String name = placeholder.path("name").asText();
              nativePattern =
                  nativePattern.replace("{" + name + "}", "{" + name + ", number, ::group-off}");
            }
          }
          assertEquals(
              fixture.path("id").asText() + "/" + message.getKey() + " n=" + value,
              sample.path("expected").asText(),
              new MessageFormat(nativePattern, locale).format(arguments));
        }
      }
    }
  }

  @Test
  public void applePluralSubstitutionMessagesMatchRealIcuRuntimeSelection() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int expected = 0;
    for (JsonNode fixture : manifest.get("cases")) {
      expected += fixture.path("xcstringsRuntimeSamples").size();
      expected += fixture.path("appleStringsRuntimeSamples").size();
      expected += fixture.path("appleStringsdictRuntimeSamples").size();
      expected += fixture.path("androidRuntimeSamples").size();
    }
    int checked = 0;
    for (JsonNode fixture : manifest.get("cases")) {
      JsonNode samples =
          fixture.has("xcstringsRuntimeSamples")
              ? fixture.get("xcstringsRuntimeSamples")
              : fixture.has("appleStringsRuntimeSamples")
                  ? fixture.get("appleStringsRuntimeSamples")
                  : fixture.has("appleStringsdictRuntimeSamples")
                      ? fixture.get("appleStringsdictRuntimeSamples")
                      : fixture.get("androidRuntimeSamples");
      if (samples == null) {
        continue;
      }
      JsonNode catalog = JSON.readTree(root.resolve(fixture.get("expected").asText()).toFile());
      ULocale locale = ULocale.forLanguageTag(catalog.path("locale").asText("en"));
      for (JsonNode sample : samples) {
        String id = sample.get("message").asText();
        MessageFormat message =
            new MessageFormat(
                catalog.path("messages").path(id).path("defaultMessage").asText(), locale);
        JsonNode metadata = catalog.path("messages").path(id).path("metadata");
        if ("icu-quoted-angle".equals(metadata.path("appleMarkupEscaping").asText())
            || "icu-quoted-angle".equals(metadata.path("androidMarkupEscaping").asText())) {
          message.applyPattern(
              catalog.path("messages").path(id).path("defaultMessage").asText(),
              ApostropheMode.DOUBLE_REQUIRED);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> arguments = sample.path("values").fields();
        while (arguments.hasNext()) {
          Map.Entry<String, JsonNode> argument = arguments.next();
          JsonNode value = argument.getValue();
          if (value.isTextual()) {
            values.put(argument.getKey(), value.asText());
          } else if (value.isIntegralNumber()) {
            values.put(argument.getKey(), value.asLong());
          } else {
            values.put(argument.getKey(), value.asDouble());
          }
        }
        assertEquals(
            fixture.path("id").asText() + "/" + id + " " + values,
            sample.path("expected").asText(),
            message.format(values));
        checked++;
      }
    }
    assertEquals(
        "Every shared Xcode and Foundation localization sample must execute", expected, checked);
  }

  private static void assertGettextRuntimeSelection(
      JsonNode fixture,
      String id,
      JsonNode descriptor,
      ULocale locale,
      MessageFormat formatted,
      Number sample,
      long index) {
    String selector =
        descriptor
            .path("metadata")
            .path("gettextPluralIndexes")
            .path(Long.toString(index))
            .asText();
    MessageFormat expected =
        new MessageFormat(descriptor.path("variants").path(selector).asText(), locale);
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("count", sample);
    for (JsonNode placeholder : descriptor.path("placeholders")) {
      arguments.put(placeholder.path("name").asText(), sample);
    }
    assertEquals(
        fixture.path("id").asText() + "/" + id + " n=" + sample,
        expected.format(arguments),
        formatted.format(arguments));
  }

  @Test
  public void everyCanonicalMessageParsesWithRealIcu() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    for (JsonNode fixture : manifest.get("cases")) {
      if (!fixture.has("expected")) {
        continue;
      }
      JsonNode catalog = JSON.readTree(root.resolve(fixture.get("expected").asText()).toFile());
      ULocale locale = ULocale.forLanguageTag(catalog.path("locale").asText("und"));
      var messages = catalog.path("messages").fields();
      while (messages.hasNext()) {
        Map.Entry<String, JsonNode> message = messages.next();
        JsonNode descriptor = message.getValue();
        MessageFormat formatted =
            new MessageFormat(descriptor.path("defaultMessage").asText(), locale);
        boolean androidMarkup =
            "icu-quoted-angle"
                .equals(descriptor.path("metadata").path("androidMarkupEscaping").asText());
        boolean appleMarkup =
            "icu-quoted-angle"
                .equals(descriptor.path("metadata").path("appleMarkupEscaping").asText());
        if (!androidMarkup && !appleMarkup) {
          continue;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("count", 2);
        for (JsonNode placeholder : descriptor.path("placeholders")) {
          arguments.put(
              placeholder.path("name").asText(),
              "integer".equals(placeholder.path("kind").asText())
                      || "number".equals(placeholder.path("kind").asText())
                  ? 2
                  : "sample");
        }
        MessageFormat original =
            new MessageFormat(
                descriptor.path("defaultMessage").asText().replace("'<'", "<").replace("''", "'"),
                locale);
        formatted.applyPattern(
            descriptor.path("defaultMessage").asText(), ApostropheMode.DOUBLE_REQUIRED);
        assertEquals(
            fixture.path("id").asText() + "/" + message.getKey(),
            original.format(arguments),
            formatted.format(arguments));
      }
    }
  }

  private static Map<String, Boolean> androidFeatureFlags(JsonNode fixture) {
    Map<String, Boolean> flags = new LinkedHashMap<>();
    fixture
        .path("androidFeatureFlags")
        .fields()
        .forEachRemaining(entry -> flags.put(entry.getKey(), entry.getValue().booleanValue()));
    return flags;
  }

  private static byte[] encode(Path input, String encoding, String lineEndings) throws Exception {
    String source = Files.readString(input, StandardCharsets.UTF_8);
    if ("CR".equals(lineEndings)) {
      source = source.replace("\r\n", "\n").replace("\n", "\r");
    } else if ("CRLF".equals(lineEndings)) {
      source = source.replace("\r\n", "\n").replace("\n", "\r\n");
    }
    if ("INVALID_UTF8".equals(encoding)) {
      return new byte[] {(byte) 0xc3, 0x28};
    }
    if ("ODD_UTF16LE_BOM".equals(encoding)) {
      return new byte[] {(byte) 0xff, (byte) 0xfe, 0x41};
    }
    if ("UNPAIRED_UTF16LE_BOM".equals(encoding)) {
      return new byte[] {(byte) 0xff, (byte) 0xfe, 0x3d, (byte) 0xd8};
    }
    if ("ODD_UTF16LE".equals(encoding) || "ODD_UTF16BE".equals(encoding)) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      result.write(
          source.getBytes(
              "ODD_UTF16LE".equals(encoding)
                  ? StandardCharsets.UTF_16LE
                  : StandardCharsets.UTF_16BE));
      result.write(0x41);
      return result.toByteArray();
    }
    if ("UNPAIRED_UTF16LE".equals(encoding) || "UNPAIRED_UTF16BE".equals(encoding)) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      boolean littleEndian = "UNPAIRED_UTF16LE".equals(encoding);
      result.write(
          source.getBytes(littleEndian ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE));
      result.write(littleEndian ? 0x00 : 0xd8);
      result.write(littleEndian ? 0xd8 : 0x00);
      return result.toByteArray();
    }
    if ("UTF-8-BOM".equals(encoding)) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      result.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
      result.write(source.getBytes(StandardCharsets.UTF_8));
      return result.toByteArray();
    }
    if ("UTF-16LE-BOM".equals(encoding)) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      result.write(0xff);
      result.write(0xfe);
      result.write(source.getBytes(StandardCharsets.UTF_16LE));
      return result.toByteArray();
    }
    if ("UTF-16LE".equals(encoding)) {
      return source.getBytes(StandardCharsets.UTF_16LE);
    }
    if ("UTF-16BE-BOM".equals(encoding)) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      result.write(0xfe);
      result.write(0xff);
      result.write(source.getBytes(StandardCharsets.UTF_16BE));
      return result.toByteArray();
    }
    if ("UTF-16BE".equals(encoding)) {
      return source.getBytes(StandardCharsets.UTF_16BE);
    }
    if ("ISO-8859-1".equals(encoding)) {
      return source.getBytes(StandardCharsets.ISO_8859_1);
    }
    if ("CP1252".equals(encoding)) {
      return source.getBytes(Charset.forName("windows-1252"));
    }
    if ("US-ASCII".equals(encoding)) {
      return source.getBytes(StandardCharsets.US_ASCII);
    }
    return source.getBytes(StandardCharsets.UTF_8);
  }

  private static Path findFixtureRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int attempt = 0;
        current != null && attempt < 6;
        attempt++, current = current.getParent()) {
      Path candidate = current.resolve("file-formats/conformance");
      if (Files.isRegularFile(candidate.resolve("manifest.json"))) {
        return candidate;
      }
    }
    throw new IllegalStateException("Could not locate file-formats/conformance/manifest.json");
  }
}
