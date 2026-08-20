package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import com.box.l10n.mojito.okapi.ExtractUsagesFromTextUnitComments;
import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import com.box.l10n.mojito.okapi.TextUnitUtils;
import com.box.l10n.mojito.okapi.asset.AssetPathToFilterConfigMapper;
import com.box.l10n.mojito.okapi.asset.FilterConfigurationMappers;
import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.okapi.extractor.AssetExtractor;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.okapi.filters.AndroidFilter;
import com.box.l10n.mojito.okapi.filters.CopyFormsOnImport;
import com.box.l10n.mojito.okapi.filters.FilterOptions;
import com.box.l10n.mojito.okapi.filters.MacStringsdictFilter;
import com.box.l10n.mojito.okapi.filters.POFilter;
import com.box.l10n.mojito.okapi.filters.PluralFormAnnotation;
import com.box.l10n.mojito.okapi.filters.UnescapeUtils;
import com.box.l10n.mojito.okapi.steps.OutputDocumentPostProcessingAnnotation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.LocaleId;
import net.sf.okapi.common.filters.IFilter;
import net.sf.okapi.common.filters.IFilterConfigurationMapper;
import net.sf.okapi.common.filterwriter.IFilterWriter;
import net.sf.okapi.common.resource.RawDocument;
import net.sf.okapi.common.resource.TextContainer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.aspectj.EnableSpringConfigured;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/** Compares portable fixture catalogs against Mojito's actual legacy Okapi extraction boundary. */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {
      AssetExtractor.class,
      AssetPathToFilterConfigMapper.class,
      FilterConfigurationMappers.class,
      TextUnitUtils.class,
      UnescapeUtils.class,
      ExtractUsagesFromTextUnitComments.class,
      OkapiLocalizationFileConvertersConformanceTest.class
    })
@EnableSpringConfigured
@DirtiesContext
public class OkapiLocalizationFileConvertersConformanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private AssetExtractor extractor;

  @Autowired private AssetPathToFilterConfigMapper mapper;

  @Autowired private TextUnitUtils textUnitUtils;

  @Autowired private IFilterConfigurationMapper filterConfigurationMapper;

  @Test
  public void customizedHtmlAlphaPreservesContextAwareIdentityAndConfiguredImageUrls()
      throws Exception {
    Path root = findFixtureRoot();
    String source = Files.readString(root.resolve("fixtures/html/harbor.html"));
    for (List<String> options :
        List.of(
            List.<String>of(),
            List.of("processImageUrls=true"),
            List.of("emptyAndNbspNotTranslatable=false"))) {
      LocalizationCatalog portable =
          LocalizationFileConverters.parseForMojito(
              LocalizationFileFormat.HTML, source.getBytes(StandardCharsets.UTF_8), options);
      List<AssetExtractorTextUnit> legacy =
          extractor.getAssetExtractorTextUnitsForAsset(
              "harbor.html", source, FilterConfigIdOverride.HTML_ALPHA, options);
      assertEquals(
          "Customized HTML_ALPHA extraction count: " + options,
          legacy.size(),
          portable.messages().size());
      for (AssetExtractorTextUnit unit : legacy) {
        LocalizationMessage message = portable.messages().get(unit.getName());
        assertNotNull(
            "Legacy HTML unit "
                + unit.getName()
                + " / "
                + unit.getSource()
                + " was absent from portable identities "
                + portable.messages().keySet(),
            message);
        assertEquals(
            unit.getName() + ": visible HTML text", unit.getSource(), message.defaultMessage());
        assertEquals(
            unit.getName() + ": HTML description", unit.getComments(), message.description());
        assertEquals(
            unit.getName() + ": stable translation-memory identity",
            textUnitUtils.computeTextUnitMD5(unit.getName(), unit.getSource(), unit.getComments()),
            textUnitUtils.computeTextUnitMD5(
                unit.getName(), message.defaultMessage(), message.description()));
      }
    }
  }

  @Test
  public void customizedJavaScriptAndTypeScriptPreserveSourceCommentsAndStableIdentity()
      throws Exception {
    Path root = findFixtureRoot();
    for (String extension : List.of("js", "ts")) {
      String assetPath = "harbor." + extension;
      String source = Files.readString(root.resolve("fixtures/javascript/" + assetPath));
      LocalizationFileFormat format =
          "js".equals(extension)
              ? LocalizationFileFormat.JAVASCRIPT
              : LocalizationFileFormat.TYPESCRIPT;
      LocalizationCatalog portable =
          LocalizationFileConverters.parseForMojito(
              format, source.getBytes(StandardCharsets.UTF_8), List.of());
      List<AssetExtractorTextUnit> legacy =
          extractor.getAssetExtractorTextUnitsForAsset(assetPath, source, null, null);

      assertEquals(
          assetPath + ": customized filter unit count", portable.messages().size(), legacy.size());
      for (AssetExtractorTextUnit unit : legacy) {
        LocalizationMessage message = portable.messages().get(unit.getName());
        assertEquals(
            assetPath + ": customized source text", message.defaultMessage(), unit.getSource());
        assertEquals(
            assetPath + ": translator comments", message.description(), unit.getComments());
        assertEquals(
            assetPath + ": stable Mojito translation-memory identity",
            textUnitUtils.computeTextUnitMD5(
                unit.getName(), message.defaultMessage(), message.description()),
            textUnitUtils.computeTextUnitMD5(unit.getName(), unit.getSource(), unit.getComments()));
      }
    }
  }

  @Test
  public void configuredYamlLeafNamesMatchOkapiAndRejectAmbiguousDuplicates() throws Exception {
    Path root = findFixtureRoot();
    String source = Files.readString(root.resolve("fixtures/yaml/duplicate-leaf-identities.yaml"));
    List<AssetExtractorTextUnit> legacy =
        extractor.getAssetExtractorTextUnitsForAsset(
            "duplicate-leaf-identities.yaml", source, null, List.of("useFullKeyPath=false"));

    assertEquals(
        List.of("prompt", "prompt"), legacy.stream().map(AssetExtractorTextUnit::getName).toList());

    List<AssetExtractorTextUnit> legacySequence =
        extractor.getAssetExtractorTextUnitsForAsset(
            "leaf-sequence.yaml",
            "items:\n  - Alpha\n  - Beta\n",
            null,
            List.of("useFullKeyPath=false"));
    assertEquals(
        List.of("items", "tu2"),
        legacySequence.stream().map(AssetExtractorTextUnit::getName).toList());
    try {
      LocalizationFileConverters.parseForMojito(
          LocalizationFileFormat.YAML,
          "items:\n  - Alpha\n  - Beta\n".getBytes(StandardCharsets.UTF_8),
          List.of("useFullKeyPath=false"));
      fail("Portable YAML must reject legacy's generated sequence identities");
    } catch (LocalizationParseException invalid) {
      assertEquals("DUPLICATE_MESSAGE_ID", invalid.code());
    }
    try {
      LocalizationFileConverters.parseForMojito(
          LocalizationFileFormat.YAML,
          source.getBytes(StandardCharsets.UTF_8),
          List.of("useFullKeyPath=false"));
      fail("Portable YAML must reject ambiguous duplicate leaf identities");
    } catch (LocalizationParseException invalid) {
      assertEquals("DUPLICATE_MESSAGE_ID", invalid.code());
    }
  }

  @Test
  public void csvCatalogsMatchBothActualCustomizedFilterConfigurations() throws Exception {
    Path root = findFixtureRoot();
    assertCsvMatchesCustomizedFilter(
        LocalizationFileFormat.CSV,
        root.resolve("fixtures/csv/standard.csv"),
        "translations.csv",
        null);
    assertCsvMatchesCustomizedFilter(
        LocalizationFileFormat.CSV_ADOBE_MAGENTO,
        root.resolve("fixtures/csv/magento.csv"),
        "i18n/en_US.csv",
        FilterConfigIdOverride.CSV_ADOBE_MAGENTO);
  }

  private void assertCsvMatchesCustomizedFilter(
      LocalizationFileFormat format, Path source, String assetPath, FilterConfigIdOverride override)
      throws Exception {
    String content = Files.readString(source);
    LocalizationCatalog portable =
        LocalizationFileConverters.parseForMojito(
            format, content.getBytes(StandardCharsets.UTF_8), List.of());
    List<AssetExtractorTextUnit> actual =
        extractor.getAssetExtractorTextUnitsForAsset(assetPath, content, override, List.of());
    Map<String, AssetExtractorTextUnit> legacy = new LinkedHashMap<>();
    for (AssetExtractorTextUnit unit : actual) {
      legacy.put(unit.getName(), unit);
    }
    assertEquals(
        format + ": customized filter identities", portable.messages().keySet(), legacy.keySet());
    for (Map.Entry<String, LocalizationMessage> entry : portable.messages().entrySet()) {
      AssetExtractorTextUnit unit = legacy.get(entry.getKey());
      assertEquals(
          entry.getKey() + ": source", entry.getValue().defaultMessage(), unit.getSource());
      assertEquals(
          entry.getKey() + ": comments", entry.getValue().description(), unit.getComments());
      assertEquals(
          entry.getKey() + ": stable translation-memory identity",
          textUnitUtils.computeTextUnitMD5(
              entry.getKey(), entry.getValue().defaultMessage(), entry.getValue().description()),
          textUnitUtils.computeTextUnitMD5(unit.getName(), unit.getSource(), unit.getComments()));
    }
  }

  @Test
  public void allDeclaredLegacyExtractionComparisons() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    List<String> failures = new ArrayList<>();
    int checked = 0;

    for (JsonNode fixture : manifest.path("cases")) {
      JsonNode differential = fixture.path("okapi");
      if (differential.isMissingNode()) {
        continue;
      }

      String id = fixture.path("id").asText();
      String assetPath = differential.path("assetPath").asText();
      String policy = differential.path("policy").asText();
      if ("unsupported".equals(policy)) {
        try {
          mapper.getFilterConfigIdFromPath(assetPath);
          fail(id + ": expected legacy routing to reject " + assetPath);
        } catch (UnsupportedAssetFilterTypeException expected) {
          checked++;
          continue;
        }
      }

      String configuration = mapper.getFilterConfigIdFromPath(assetPath);
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      if ("rejected".equals(policy)) {
        try {
          extractor.getAssetExtractorTextUnitsForAsset(assetPath, source, null, null);
          fail(id + ": expected legacy extraction to reject valid platform resources");
        } catch (RuntimeException rejected) {
          assertEquals(
              id + ": stable legacy rejection type",
              differential.path("errorClass").asText(),
              rejected.getClass().getName());
          assertEquals(
              id + ": stable legacy rejection message",
              differential.path("errorMessage").asText(),
              rejected.getMessage());
          checked++;
          continue;
        }
      }
      List<AssetExtractorTextUnit> extracted =
          extractor.getAssetExtractorTextUnitsForAsset(assetPath, source, null, null);
      ObjectNode actual = snapshot(configuration, extracted);
      JsonNode expected =
          JSON.readTree(root.resolve(differential.path("expected").asText()).toFile());
      if (!expected.equals(actual)) {
        failures.add(
            id
                + ": legacy Okapi snapshot changed\nexpected: "
                + JSON.writeValueAsString(expected)
                + "\nactual: "
                + JSON.writeValueAsString(actual));
        System.out.println("OKAPI_SNAPSHOT " + id + " " + JSON.writeValueAsString(actual));
      }

      if ("match".equals(policy)) {
        JsonNode catalog = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
        Map<String, String> canonical = new LinkedHashMap<>();
        var descriptors = catalog.path("messages").fields();
        while (descriptors.hasNext()) {
          Map.Entry<String, JsonNode> entry = descriptors.next();
          assertFalse(
              id + ": matching policy requires plain messages", entry.getValue().has("variants"));
          assertFalse(
              id + ": matching policy cannot hide native placeholder differences",
              entry.getValue().has("placeholders"));
          canonical.put(entry.getKey(), entry.getValue().path("defaultMessage").asText());
        }
        Map<String, String> legacy = new LinkedHashMap<>();
        for (AssetExtractorTextUnit unit : extracted) {
          legacy.put(unit.getName(), unit.getSource());
        }
        assertEquals(
            id + ": plain canonical messages must match legacy extraction", canonical, legacy);
      }
      checked++;
    }

    assertEquals("Legacy Okapi differential snapshots must remain stable", List.of(), failures);
    assertFalse("The manifest must declare real legacy comparisons", checked == 0);
    System.out.println("Okapi verified " + checked + " manifest-declared extraction comparisons.");
  }

  @Test
  public void configuredMojitoWorkflowMatchesActualCustomizedFilters() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;

    for (JsonNode fixture : manifest.path("workflowCases")) {
      if (!fixture.has("legacyAssetPath")) {
        continue;
      }
      String id = fixture.path("id").asText();
      String assetPath = fixture.path("legacyAssetPath").asText();
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      List<String> options = new ArrayList<>();
      fixture.path("filterOptions").forEach(value -> options.add(value.asText()));
      List<AssetExtractorTextUnit> actual =
          extractor.getAssetExtractorTextUnitsForAsset(assetPath, source, null, options);
      JsonNode expected = JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());

      Map<String, ObjectNode> canonical = new LinkedHashMap<>();
      var messages = expected.path("messages").fields();
      while (messages.hasNext()) {
        Map.Entry<String, JsonNode> entry = messages.next();
        ObjectNode identity = JSON.createObjectNode();
        identity.put("source", entry.getValue().path("defaultMessage").asText());
        if (entry.getValue().has("description")) {
          identity.put("comments", entry.getValue().path("description").asText());
        }
        if (entry.getValue().path("metadata").has("references")) {
          ArrayNode usages = identity.putArray("usages");
          List<String> sorted = new ArrayList<>();
          entry
              .getValue()
              .path("metadata")
              .path("references")
              .forEach(value -> sorted.add(value.asText()));
          sorted.stream().sorted().forEach(usages::add);
        }
        identity.put(
            "md5",
            textUnitUtils.computeTextUnitMD5(
                entry.getKey(),
                entry.getValue().path("defaultMessage").asText(),
                entry.getValue().has("description")
                    ? entry.getValue().path("description").asText()
                    : null));
        canonical.put(entry.getKey(), identity);
      }

      Map<String, ObjectNode> configured = new LinkedHashMap<>();
      for (AssetExtractorTextUnit unit : actual) {
        ObjectNode identity = JSON.createObjectNode();
        identity.put("source", unit.getSource());
        if (unit.getComments() != null && !unit.getComments().isBlank()) {
          identity.put("comments", unit.getComments());
        }
        if (unit.getUsages() != null && !unit.getUsages().isEmpty()) {
          ArrayNode usages = identity.putArray("usages");
          unit.getUsages().stream().sorted().forEach(usages::add);
        }
        identity.put(
            "md5",
            textUnitUtils.computeTextUnitMD5(unit.getName(), unit.getSource(), unit.getComments()));
        configured.put(unit.getName(), identity);
      }
      List<String> missingUsages = new ArrayList<>();
      fixture.path("legacyMissingUsages").forEach(value -> missingUsages.add(value.asText()));
      for (String name : missingUsages) {
        org.junit.Assert.assertNotNull(
            id + ": portable retains source usage", canonical.get(name).get("usages"));
        assertFalse(
            id + ": actual customized filter drops source usage",
            configured.get(name).has("usages"));
        canonical.get(name).remove("usages");
      }
      for (JsonNode value : fixture.path("legacyEscapedComments")) {
        String name = value.asText();
        String decoded = canonical.get(name).path("comments").asText();
        String escaped = JSON.writeValueAsString(decoded);
        escaped = escaped.substring(1, escaped.length() - 1);
        assertEquals(
            id + ": actual customized filter incorrectly retains JSON string escapes",
            escaped,
            configured.get(name).path("comments").asText());
        assertFalse(
            id + ": correctly decoded comment deliberately changes the old identity",
            canonical.get(name).path("md5").equals(configured.get(name).path("md5")));
        configured.get(name).put("comments", decoded);
        configured.get(name).put("md5", canonical.get(name).path("md5").asText());
      }
      assertEquals(id + ": configured custom-filter extraction", canonical, configured);
      checked++;
    }
    int expected = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      if (fixture.has("legacyAssetPath")) {
        expected++;
      }
    }
    assertEquals("Every configured custom-filter fixture must run", expected, checked);
  }

  @Test
  public void importPluralFormsMatchActualCustomizedFilters() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      JsonNode policy = fixture.path("importPolicy");
      if (!fixture.has("legacyImportFilter")) {
        continue;
      }
      LocalizationFileFormat format =
          LocalizationFileFormat.fromId(fixture.path("legacyImportFilter").asText());
      String configuration =
          switch (format) {
            case ANDROID -> AndroidFilter.FILTER_CONFIG_ID;
            case APPLE_STRINGSDICT -> MacStringsdictFilter.FILTER_CONFIG_ID;
            case GETTEXT_PO -> POFilter.FILTER_CONFIG_ID;
            default ->
                throw new AssertionError("Unexpected plural-import fixture format: " + format);
          };
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      RawDocument document =
          new RawDocument(
              source, LocaleId.ENGLISH, LocaleId.fromBCP47(policy.path("targetLocale").asText()));
      document.setAnnotation(new CopyFormsOnImport());
      List<String> options = new ArrayList<>();
      fixture.path("filterOptions").forEach(option -> options.add(option.asText()));
      document.setAnnotation(new FilterOptions(options));
      Map<String, Set<String>> actual = new LinkedHashMap<>();
      Map<String, String> actualValues = new LinkedHashMap<>();
      try (IFilter filter = filterConfigurationMapper.createFilter(configuration)) {
        filter.open(document);
        while (filter.hasNext()) {
          Event event = filter.next();
          if (!event.isTextUnit()) {
            continue;
          }
          PluralFormAnnotation form = event.getTextUnit().getAnnotation(PluralFormAnnotation.class);
          if (form != null) {
            actual
                .computeIfAbsent(form.getOtherName(), ignored -> new java.util.TreeSet<>())
                .add(form.getName());
            String nativeValue =
                event.getTextUnit().getTarget(document.getTargetLocale()) == null
                    ? event.getTextUnit().getSource().toString()
                    : event.getTextUnit().getTarget(document.getTargetLocale()).toString();
            String value =
                format == LocalizationFileFormat.GETTEXT_PO
                    ? PlaceholderNormalizer.normalize(
                        nativeValue, PlaceholderNormalizer.placeholders())
                    : nativeValue;
            actualValues.put(form.getOtherName() + "#" + form.getName(), value);
          }
        }
      }
      JsonNode expectedCatalog =
          JSON.readTree(root.resolve(fixture.path("expected").asText()).toFile());
      Set<String> expected = new java.util.TreeSet<>();
      expectedCatalog
          .path("messages")
          .forEach(
              message -> {
                if (message.has("variants")) {
                  message.path("variants").fieldNames().forEachRemaining(expected::add);
                } else {
                  message
                      .path("metadata")
                      .path("applePluralRules")
                      .forEach(
                          rule ->
                              rule.path("variants").fieldNames().forEachRemaining(expected::add));
                }
              });
      Set<String> legacyExpected = new java.util.TreeSet<>();
      fixture
          .path("legacyPluralCategories")
          .forEach(category -> legacyExpected.add(category.asText()));
      if (legacyExpected.isEmpty()) {
        legacyExpected.addAll(expected);
      }
      assertEquals(
          fixture.path("id").asText(), Set.of(legacyExpected), Set.copyOf(actual.values()));
      Set<String> missingSelectors = new java.util.TreeSet<>();
      fixture
          .path("legacyMissingPluralSelectors")
          .forEach(value -> missingSelectors.add(value.asText()));
      expectedCatalog
          .path("messages")
          .forEach(
              message -> {
                JsonNode variables = message.path("metadata").path("pluralVariables");
                if (variables.isArray()) {
                  variables.forEach(
                      variable -> {
                        String name = variable.asText();
                        boolean emitted =
                            actual.keySet().stream()
                                .anyMatch(group -> group.contains("_" + name + "_"));
                        assertEquals(
                            fixture.path("id").asText() + ": legacy selector " + name,
                            !missingSelectors.contains(name),
                            emitted);
                      });
                }
              });
      fixture
          .path("legacyPluralValueDifferences")
          .fields()
          .forEachRemaining(
              difference -> {
                Set<String> actualForCategory = new java.util.TreeSet<>();
                actualValues.forEach(
                    (key, value) -> {
                      if (key.endsWith("#" + difference.getKey())) {
                        actualForCategory.add(value);
                      }
                    });
                assertEquals(
                    fixture.path("id").asText() + ": actual legacy " + difference.getKey(),
                    Set.of(difference.getValue().asText()),
                    actualForCategory);
                Set<String> portableForCategory = new java.util.TreeSet<>();
                expectedCatalog
                    .path("messages")
                    .forEach(
                        message -> {
                          JsonNode variant = message.path("variants").path(difference.getKey());
                          if (variant.isTextual()) {
                            portableForCategory.add(variant.asText());
                          }
                        });
                assertFalse(
                    fixture.path("id").asText() + ": portable should improve legacy fallback",
                    portableForCategory.contains(difference.getValue().asText()));
              });
      checked++;
    }
    assertEquals("Every supported legacy plural-import contract must run", 9, checked);
  }

  @Test
  public void translatedApplePostProcessingExposesActualLegacyCleanupBug() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      if (!"apple_strings".equals(fixture.path("format").asText())
          || (!fixture.has("legacyLocalizedOutputContains")
              && !fixture.has("legacyLocalizedOutputMissing"))) {
        continue;
      }
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      Map<String, String> translations = new LinkedHashMap<>();
      fixture
          .path("translations")
          .fields()
          .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
      RawDocument document = new RawDocument(source, LocaleId.ENGLISH, LocaleId.FRENCH);
      List<String> options = new ArrayList<>();
      fixture.path("filterOptions").forEach(option -> options.add(option.asText()));
      document.setAnnotation(new FilterOptions(options));
      try (IFilter filter = filterConfigurationMapper.createFilter("okf_regex@mojito-macStrings")) {
        filter.open(document);
        ByteArrayOutputStream localized = new ByteArrayOutputStream();
        try (IFilterWriter writer = filter.createFilterWriter()) {
          writer.setOptions(LocaleId.FRENCH, StandardCharsets.UTF_8.name());
          writer.setOutput(localized);
          while (filter.hasNext()) {
            Event event = filter.next();
            if (event.isTextUnit()) {
              String target =
                  translations.getOrDefault(event.getTextUnit().getName(), "@#$untranslated$#@");
              event.getTextUnit().setTarget(LocaleId.FRENCH, new TextContainer(target));
            }
            writer.handleEvent(event);
          }
        }
        var postprocessor =
            document
                .getAnnotation(OutputDocumentPostProcessingAnnotation.class)
                .getOutputDocumentPostProcessor();
        postprocessor.setRemoveUntranslated(fixture.path("removeUntranslated").asBoolean());
        String legacy = postprocessor.execute(localized.toString(StandardCharsets.UTF_8));
        String portable = Files.readString(root.resolve(fixture.path("localized").asText()));
        for (JsonNode value : fixture.path("legacyLocalizedOutputContains")) {
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": actual legacy localized output",
              legacy.contains(value.asText()));
          assertFalse(
              fixture.path("id").asText() + ": portable output must fix legacy cleanup",
              portable.contains(value.asText()));
        }
        for (JsonNode value : fixture.path("legacyLocalizedOutputMissing")) {
          assertFalse(
              fixture.path("id").asText() + ": actual legacy output corrupts translated content",
              legacy.contains(value.asText()));
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": portable output preserves translated content",
              portable.contains(value.asText()));
        }
      }
      checked++;
    }
    assertEquals("Every actual translated-output legacy defect must remain documented", 3, checked);
  }

  @Test
  public void translatedAndroidPostProcessingExposesLostGenericNativeResources() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      if (!"android".equals(fixture.path("format").asText())
          || (!fixture.has("legacyLocalizedOutput")
              && !fixture.has("legacyLocalizedOutputContains")
              && !fixture.has("legacyLocalizedOutputMissing"))) {
        continue;
      }
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      List<String> options = new ArrayList<>();
      fixture.path("filterOptions").forEach(option -> options.add(option.asText()));
      RawDocument document = new RawDocument(source, LocaleId.ENGLISH, LocaleId.FRENCH);
      document.setAnnotation(new FilterOptions(options));
      try (IFilter filter =
          filterConfigurationMapper.createFilter(AndroidFilter.FILTER_CONFIG_ID)) {
        filter.open(document);
        ByteArrayOutputStream localized = new ByteArrayOutputStream();
        try (IFilterWriter writer = filter.createFilterWriter()) {
          writer.setOptions(LocaleId.FRENCH, StandardCharsets.UTF_8.name());
          writer.setOutput(localized);
          while (filter.hasNext()) {
            Event event = filter.next();
            if (event.isTextUnit()
                && fixture.path("translations").has(event.getTextUnit().getName())) {
              event
                  .getTextUnit()
                  .setTarget(
                      LocaleId.FRENCH,
                      new TextContainer(
                          fixture
                              .path("translations")
                              .path(event.getTextUnit().getName())
                              .asText()));
            } else if (event.isTextUnit() && fixture.path("removeUntranslated").asBoolean()) {
              event
                  .getTextUnit()
                  .setTarget(LocaleId.FRENCH, new TextContainer("@#$untranslated$#@"));
            }
            writer.handleEvent(event);
          }
        }
        var postprocessor =
            document
                .getAnnotation(OutputDocumentPostProcessingAnnotation.class)
                .getOutputDocumentPostProcessor();
        postprocessor.setRemoveUntranslated(fixture.path("removeUntranslated").asBoolean());
        String legacy = postprocessor.execute(localized.toString(StandardCharsets.UTF_8));
        String portable = Files.readString(root.resolve(fixture.path("localized").asText()));
        if (fixture.has("legacyLocalizedOutput")) {
          assertEquals(
              fixture.path("id").asText() + ": actual legacy output drops every native resource",
              fixture.path("legacyLocalizedOutput").asText(),
              legacy);
          assertFalse(
              fixture.path("id").asText() + ": portable output preserves native resources",
              portable.isBlank());
        }
        for (JsonNode value : fixture.path("legacyLocalizedOutputContains")) {
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": actual legacy output retains excluded content",
              legacy.contains(value.asText()));
          assertFalse(
              fixture.path("id").asText() + ": portable output removes excluded content",
              portable.contains(value.asText()));
        }
        for (JsonNode value : fixture.path("legacyLocalizedOutputMissing")) {
          assertFalse(
              fixture.path("id").asText() + ": actual legacy output drops " + value.asText(),
              legacy.contains(value.asText()));
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": portable output retains owned content",
              portable.contains(value.asText()));
        }
      }
      checked++;
    }
    assertEquals(
        "Every actual Android translated-output defect must remain documented", 4, checked);
  }

  @Test
  public void translatedJsonPostProcessingExposesConsecutiveUntranslatedArrayObjects()
      throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      if (!"formatjs_json".equals(fixture.path("format").asText())
          || !fixture.has("legacyLocalizedOutputContains")) {
        continue;
      }
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      List<String> options = new ArrayList<>();
      fixture.path("filterOptions").forEach(option -> options.add(option.asText()));
      RawDocument document = new RawDocument(source, LocaleId.ENGLISH, LocaleId.FRENCH);
      document.setAnnotation(new FilterOptions(options));
      try (IFilter filter = filterConfigurationMapper.createFilter("okf_json@mojito")) {
        filter.open(document);
        ByteArrayOutputStream localized = new ByteArrayOutputStream();
        try (IFilterWriter writer = filter.createFilterWriter()) {
          writer.setOptions(LocaleId.FRENCH, StandardCharsets.UTF_8.name());
          writer.setOutput(localized);
          while (filter.hasNext()) {
            Event event = filter.next();
            if (event.isTextUnit()) {
              String translation =
                  fixture
                      .path("translations")
                      .path(event.getTextUnit().getName())
                      .asText("@#$untranslated$#@");
              event.getTextUnit().setTarget(LocaleId.FRENCH, new TextContainer(translation));
            }
            writer.handleEvent(event);
          }
        }
        var postprocessor =
            document
                .getAnnotation(OutputDocumentPostProcessingAnnotation.class)
                .getOutputDocumentPostProcessor();
        postprocessor.setRemoveUntranslated(true);
        String legacy = postprocessor.execute(localized.toString(StandardCharsets.UTF_8));
        Map<String, String> translations = new LinkedHashMap<>();
        fixture
            .path("translations")
            .fields()
            .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
        String portable =
            new String(
                LocalizationFileConverters.localizeForMojito(
                    LocalizationFileFormat.FORMATJS_JSON,
                    source.getBytes(StandardCharsets.UTF_8),
                    translations,
                    options,
                    true),
                StandardCharsets.UTF_8);
        for (JsonNode value : fixture.path("legacyLocalizedOutputContains")) {
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": actual legacy JSON retains adjacent content",
              legacy.contains(value.asText()));
          assertFalse(
              fixture.path("id").asText() + ": portable JSON removes adjacent content",
              portable.contains(value.asText()));
        }
        for (JsonNode value : fixture.path("legacyLocalizedOutputMissing")) {
          assertFalse(
              fixture.path("id").asText() + ": actual legacy JSON discards translated content",
              legacy.contains(value.asText()));
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": portable JSON preserves translated content",
              portable.contains(value.asText()));
        }
      }
      checked++;
    }
    assertEquals("Every actual JSON translated-output defect must remain documented", 1, checked);
  }

  @Test
  public void translatedGettextPostProcessingChecksActualCustomizedWorkflow() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    int checked = 0;
    for (JsonNode fixture : manifest.path("workflowCases")) {
      if (!"gettext_po".equals(fixture.path("format").asText())
          || (!fixture.path("legacyLocalizedOutputMatchesPortable").asBoolean()
              && !fixture.has("legacyLocalizedOutputMissing"))) {
        continue;
      }
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      Map<String, String> translations = new LinkedHashMap<>();
      fixture
          .path("translations")
          .fields()
          .forEachRemaining(entry -> translations.put(entry.getKey(), entry.getValue().asText()));
      RawDocument document = new RawDocument(source, LocaleId.ENGLISH, LocaleId.FRENCH);
      document.setAnnotation(new FilterOptions(List.of()));
      try (IFilter filter = filterConfigurationMapper.createFilter(POFilter.FILTER_CONFIG_ID)) {
        filter.open(document);
        ByteArrayOutputStream localized = new ByteArrayOutputStream();
        try (IFilterWriter writer = filter.createFilterWriter()) {
          writer.setOptions(LocaleId.FRENCH, StandardCharsets.UTF_8.name());
          writer.setOutput(localized);
          while (filter.hasNext()) {
            Event event = filter.next();
            if (event.isTextUnit()) {
              String translation =
                  translations.getOrDefault(event.getTextUnit().getName(), "@#$untranslated$#@");
              event.getTextUnit().setTarget(LocaleId.FRENCH, new TextContainer(translation));
            }
            writer.handleEvent(event);
          }
        }
        var postprocessor =
            document
                .getAnnotation(OutputDocumentPostProcessingAnnotation.class)
                .getOutputDocumentPostProcessor();
        postprocessor.setRemoveUntranslated(true);
        String legacy = postprocessor.execute(localized.toString(StandardCharsets.UTF_8));
        String portable =
            new String(
                LocalizationFileConverters.localizeForMojito(
                    LocalizationFileFormat.GETTEXT_PO,
                    source.getBytes(StandardCharsets.UTF_8),
                    translations,
                    List.of(),
                    true),
                StandardCharsets.UTF_8);
        if (fixture.path("legacyLocalizedOutputMatchesPortable").asBoolean()) {
          assertEquals(fixture.path("id").asText() + ": actual gettext output", legacy, portable);
        }
        for (JsonNode value : fixture.path("legacyLocalizedOutputMissing")) {
          assertFalse(
              fixture.path("id").asText() + ": actual legacy gettext drops translated content",
              legacy.contains(value.asText()));
          org.junit.Assert.assertTrue(
              fixture.path("id").asText() + ": portable gettext preserves translated content",
              portable.contains(value.asText()));
        }
      }
      checked++;
    }
    assertEquals("Every actual customized gettext output contract must run", 2, checked);
  }

  @Test
  public void allSharedShadowComparisonReports() throws Exception {
    Path root = findFixtureRoot();
    JsonNode manifest = JSON.readTree(root.resolve("manifest.json").toFile());
    Map<String, JsonNode> fixtures = new LinkedHashMap<>();
    for (JsonNode fixture : manifest.path("cases")) {
      fixtures.put(fixture.path("id").asText(), fixture);
    }
    List<String> failures = new ArrayList<>();
    int checked = 0;
    for (JsonNode comparison : manifest.path("shadowComparisons")) {
      String id = comparison.path("id").asText();
      JsonNode fixture = fixtures.get(comparison.path("case").asText());
      String source = Files.readString(root.resolve(fixture.path("input").asText()));
      String assetPath = fixture.path("okapi").path("assetPath").asText();
      List<AssetExtractorTextUnit> legacy =
          extractor.getAssetExtractorTextUnitsForAsset(assetPath, source, null, null);
      LocalizationFileFormat format =
          LocalizationFileFormat.fromId(fixture.path("format").asText());
      byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      String resourcePath =
          fixture.has("resourcePath") ? fixture.path("resourcePath").asText() : null;
      String applicationPackage =
          fixture.has("androidApplicationPackage")
              ? fixture.path("androidApplicationPackage").asText()
              : null;
      LocalizationCatalog catalog =
          fixture.has("androidFeatureFlagDefinitions")
              ? LocalizationFileConverters.parseWithAndroidFeatureFlags(
                  format,
                  bytes,
                  java.nio.charset.StandardCharsets.UTF_8,
                  resourcePath,
                  androidFeatureFlagDefinitions(fixture),
                  androidSelectedProducts(fixture),
                  applicationPackage)
              : LocalizationFileConverters.parse(
                  format,
                  bytes,
                  java.nio.charset.StandardCharsets.UTF_8,
                  resourcePath,
                  androidFeatureFlags(fixture),
                  androidSelectedProducts(fixture),
                  applicationPackage);
      JsonNode actual = JSON.valueToTree(LocalizationShadowComparator.compare(catalog, legacy));
      if ("shadow-android-product-identity-collisions".equals(id)) {
        JsonNode collision =
            java.util.stream.StreamSupport.stream(actual.path("differences").spliterator(), false)
                .filter(
                    difference ->
                        "legacy_projection_collision".equals(difference.path("category").asText())
                            && "button".equals(difference.path("id").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(
            JSON.valueToTree(List.of("button", "button@product=tablet", "button@product=watch")),
            collision.path("canonicalIds"));
      }
      if ("shadow-android-runtime-feature-variant-native-identity-collisions".equals(id)) {
        JsonNode collision =
            java.util.stream.StreamSupport.stream(actual.path("differences").spliterator(), false)
                .filter(
                    difference ->
                        "legacy_projection_collision".equals(difference.path("category").asText())
                            && "harbor_route".equals(difference.path("id").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(
            JSON.valueToTree(
                List.of(
                    "harbor_route",
                    "harbor_route@flag=!neutral.flags.first",
                    "harbor_route@flag=neutral.flags.first",
                    "harbor_route@flag=neutral.flags.second")),
            collision.path("canonicalIds"));
      }
      JsonNode expected =
          JSON.readTree(root.resolve(comparison.path("expected").asText()).toFile());
      if (!expected.equals(actual)) {
        failures.add(id + ": implementation-neutral shadow report changed");
        System.out.println("SHADOW_SNAPSHOT " + id + " " + JSON.writeValueAsString(actual));
      }
      checked++;
    }
    assertEquals("Shared shadow report snapshots must remain stable", List.of(), failures);
    assertEquals(
        "Every implementation-neutral shadow comparison must execute",
        manifest.path("shadowComparisons").size(),
        checked);
    System.out.println("Shadow comparison verified " + checked + " shared migration reports.");
  }

  private static ObjectNode snapshot(String filterConfigId, List<AssetExtractorTextUnit> units) {
    ObjectNode snapshot = JSON.createObjectNode();
    snapshot.put("filterConfigId", filterConfigId);
    ArrayNode actualUnits = snapshot.putArray("units");
    for (AssetExtractorTextUnit unit : units) {
      ObjectNode actual = actualUnits.addObject();
      actual.put("name", unit.getName());
      actual.put("source", unit.getSource());
      if (unit.getComments() != null) {
        actual.put("comments", unit.getComments());
      }
      if (unit.getPluralForm() != null) {
        actual.put("pluralForm", unit.getPluralForm());
      }
      if (unit.getPluralFormOther() != null) {
        actual.put("pluralFormOther", unit.getPluralFormOther());
      }
      if (unit.getUsages() != null && !unit.getUsages().isEmpty()) {
        ArrayNode usages = actual.putArray("usages");
        unit.getUsages().stream().sorted().forEach(usages::add);
      }
    }
    return snapshot;
  }

  private static List<String> androidSelectedProducts(JsonNode fixture) {
    if (!fixture.has("androidSelectedProducts")) {
      return null;
    }
    List<String> products = new ArrayList<>();
    fixture.path("androidSelectedProducts").forEach(product -> products.add(product.asText()));
    return products;
  }

  private static Map<String, Boolean> androidFeatureFlags(JsonNode fixture) {
    Map<String, Boolean> flags = new LinkedHashMap<>();
    fixture
        .path("androidFeatureFlags")
        .fields()
        .forEachRemaining(entry -> flags.put(entry.getKey(), entry.getValue().booleanValue()));
    return flags;
  }

  private static List<AndroidFeatureFlag> androidFeatureFlagDefinitions(JsonNode fixture) {
    List<AndroidFeatureFlag> result = new ArrayList<>();
    for (JsonNode definition : fixture.path("androidFeatureFlagDefinitions")) {
      JsonNode value = definition.path("value");
      result.add(
          new AndroidFeatureFlag(
              definition.path("name").asText(),
              "read_only".equals(definition.path("mode").asText()),
              value.isNull() ? null : value.asBoolean()));
    }
    return result;
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
