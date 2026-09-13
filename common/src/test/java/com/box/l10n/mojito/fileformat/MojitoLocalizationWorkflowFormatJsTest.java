package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MojitoLocalizationWorkflowFormatJsTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String HOME =
      "{\"home\":{\"defaultMessage\":\"Home\",\"description\":\"Navigation label\"}}";

  @Parameterized.Parameters(name = "filterOptions={0}")
  public static Object[][] defaultOptions() {
    return new Object[][] {{null}, {List.of()}};
  }

  @Parameterized.Parameter public List<String> filterOptions;

  @Test
  public void descriptorRoundTripUsesExtractedMessageIdentity() {
    LocalizationCatalog catalog = parse(HOME, filterOptions);
    assertEquals(Set.of("home"), catalog.messages().keySet());
    assertEquals("Home", catalog.messages().get("home").defaultMessage());
    assertEquals("Navigation label", catalog.messages().get("home").description());

    String localized = localize(HOME, Map.of("home", "Accueil"), filterOptions, false);

    assertEquals(HOME.replace("\"Home\"", "\"Accueil\""), localized);
    LocalizationCatalog reparsed = parse(localized, filterOptions);
    assertEquals(catalog.messages().keySet(), reparsed.messages().keySet());
    assertEquals("Accueil", reparsed.messages().get("home").defaultMessage());
    assertEquals("Navigation label", reparsed.messages().get("home").description());
    assertEquals(HOME, localize(HOME, Map.of(), filterOptions, false));
    assertEquals(HOME, localize(HOME, Map.of("home", "Home"), filterOptions, false));
  }

  @Test
  public void flatRoundTripPreservesLiteralKeysEscapingAndSourceLayout() throws Exception {
    String source =
        "{\n  \"home/defaultMessage\" : \"Home\", \"url\": \"https://example.test/a/*b*/\"\n}\n";
    assertEquals(
        Set.of("home/defaultMessage", "url"), parse(source, filterOptions).messages().keySet());
    String translation = "Accueil \"{name}\"\n\\";

    String localized =
        localize(source, Map.of("home/defaultMessage", translation), filterOptions, false);

    assertEquals(source.replace("\"Home\"", JSON.writeValueAsString(translation)), localized);
    assertEquals(
        translation,
        parse(localized, filterOptions).messages().get("home/defaultMessage").defaultMessage());
    assertEquals(source, localize(source, Map.of(), filterOptions, false));
  }

  @Test
  public void descriptorAndFlatKeysRemainLiteralRatherThanPathsOrVariantSlots() {
    String source =
        """
        {
          "nav.home" : {"description":"Keep this", "defaultMessage":"One"},
          "nav/home": {"defaultMessage":"Two", "metadata":{"defaultMessage":"Untouched"}},
          "nav~home" : "Three",
          "items#one": "Four",
          "defaultMessage": {"defaultMessage":"Five"},
          "metadata": "Caf\u00e9"
        }
        """;
    Map<String, String> translations =
        Map.of(
            "nav.home", "Un",
            "nav/home", "Deux",
            "nav~home", "Trois",
            "items#one", "Quatre",
            "defaultMessage", "Cinq",
            "metadata", "Entr\u00e9e");
    assertEquals(translations.keySet(), parse(source, filterOptions).messages().keySet());

    String localized = localize(source, translations, filterOptions, false);

    assertEquals(
        source
            .replace("\"One\"", "\"Un\"")
            .replace("\"Two\"", "\"Deux\"")
            .replace("\"Three\"", "\"Trois\"")
            .replace("\"Four\"", "\"Quatre\"")
            .replace("\"Five\"", "\"Cinq\"")
            .replace("Caf\u00e9", "Entr\u00e9e"),
        localized);
    LocalizationCatalog reparsed = parse(localized, filterOptions);
    assertEquals(translations.keySet(), reparsed.messages().keySet());
    translations.forEach(
        (key, translation) ->
            assertEquals(translation, reparsed.messages().get(key).defaultMessage()));
  }

  @Test
  public void descriptorKeepsMetadataPlaceholdersAndVariantsWhileReplacingWholeIcuMessage()
      throws Exception {
    String source =
        """
{
  "items": {
    "description": "Cart count",
    "metadata": {"owner": "checkout", "defaultMessage": "not a message"},
    "defaultMessage": "{count, plural, one {# item for {name}} other {# items for {name}}}",
    "variants": {"one": "# item for {name}", "other": "# items for {name}"},
    "placeholders": [{"name": "name", "source": "{name}", "kind": "argument"}],
    "file": "cart.tsx", "line": 7
  }
}
""";
    String translation =
        "{count, plural, one {# article pour {name}} other {# articles pour {name}}}";
    LocalizationMessage original = parse(source, filterOptions).messages().get("items");
    assertEquals("Cart count", original.description());
    assertEquals("# items for {name}", original.variants().get("other"));
    assertEquals("name", original.placeholders().get(0).name());
    assertEquals("checkout", original.metadata().get("owner"));
    assertEquals(Map.of("file", "cart.tsx", "line", 7), original.metadata().get("formatjs"));

    String localized = localize(source, Map.of("items", translation), filterOptions, false);

    assertEquals(source.replace(original.defaultMessage(), translation), localized);
    LocalizationMessage actual = parse(localized, filterOptions).messages().get("items");
    assertEquals(translation, actual.defaultMessage());
    assertEquals(original.description(), actual.description());
    assertEquals(original.variants(), actual.variants());
    assertEquals(original.placeholders(), actual.placeholders());
    assertEquals(original.metadata(), actual.metadata());
    assertEquals(
        JSON.readTree(source).path("items").path("variants"),
        JSON.readTree(localized).path("items").path("variants"));
  }

  @Test
  public void removeUntranslatedDropsWholeDescriptorsAndFlatEntriesButKeepsExcludedMessages()
      throws Exception {
    String source =
        """
        {
          "home": {"defaultMessage": "Home", "description": "Navigation label", "line": 1},
          "missing": {"defaultMessage": "Missing", "description": "Do not leave an orphan"},
          "flatMissing": "Missing too",
          "empty": "Empty translation",
          "sentinel": "Literal marker",
          "protected": {"defaultMessage": "Brand", "description": "DO NOT TRANSLATE"}
        }
        """;
    assertFalse(parse(source, filterOptions).messages().containsKey("protected"));
    Map<String, String> translations =
        Map.of("home", "Accueil", "empty", "", "sentinel", "@#$untranslated$#@");

    String localized = localize(source, translations, filterOptions, true);

    JsonNode expected = JSON.readTree(source);
    var expectedObject = (com.fasterxml.jackson.databind.node.ObjectNode) expected;
    expectedObject.remove(List.of("missing", "flatMissing"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("home"))
        .put("defaultMessage", "Accueil");
    expectedObject.put("empty", "");
    expectedObject.put("sentinel", "@#$untranslated$#@");
    assertEquals(expected, JSON.readTree(localized));
    assertEquals(translations.keySet(), parse(localized, filterOptions).messages().keySet());
    assertEquals('\n', localized.charAt(localized.length() - 1));
    assertEquals(JSON.readTree("{}"), JSON.readTree(localize(HOME, Map.of(), filterOptions, true)));
    assertEquals(HOME, localize(HOME, Map.of("home", "Home"), filterOptions, true));
  }

  @Test
  public void canonicalWrapperKeepsEnvelopeAndUsesMessageIds() throws Exception {
    String source =
        """
        {"schemaVersion":1,"locale":"en","messages":{
          "home":{"defaultMessage":"Home","description":"Navigation label"},"missing":"Missing"
        },"metadata":{"owner":"checkout"}}
        """;
    assertEquals(Set.of("home", "missing"), parse(source, filterOptions).messages().keySet());
    assertEquals("en", parse(source, filterOptions).locale());
    assertEquals(
        source.replace("\"Home\"", "\"Accueil\""),
        localize(source, Map.of("home", "Accueil"), filterOptions, false));

    JsonNode actual =
        JSON.readTree(localize(source, Map.of("home", "Accueil"), filterOptions, true));

    assertEquals(1, actual.path("schemaVersion").asInt());
    assertEquals("en", actual.path("locale").asText());
    assertEquals(JSON.readTree(source).path("metadata"), actual.path("metadata"));
    assertEquals(1, actual.path("messages").size());
    assertEquals("Accueil", actual.path("messages").path("home").path("defaultMessage").asText());
  }

  @Test
  public void unknownDescriptorPathsAndSyntheticVariantSlotsAreRejected() {
    for (String source : List.of(HOME, "{\"schemaVersion\":1,\"messages\":" + HOME + "}")) {
      for (String key :
          List.of(
              "missing", "home/defaultMessage", "home/description", "home#one", "messages/home")) {
        for (boolean removeUntranslated : List.of(false, true)) {
          LocalizationParseException invalid =
              assertThrows(
                  LocalizationParseException.class,
                  () ->
                      localize(source, Map.of(key, "Accueil"), filterOptions, removeUntranslated));
          assertEquals("UNKNOWN_SKELETON_SLOT", invalid.code());
        }
      }
    }
  }

  @Test
  public void defaultParsingAndRenderingBothRejectGenericNestedObjects() {
    String source = "{\"group\":{\"label\":\"Home\"}}";
    assertEquals(
        "INVALID_FORMATJS",
        assertThrows(LocalizationParseException.class, () -> parse(source, filterOptions)).code());
    assertEquals(
        "INVALID_FORMATJS",
        assertThrows(
                LocalizationParseException.class,
                () -> localize(source, Map.of(), filterOptions, false))
            .code());
  }

  @Test
  public void defaultParsingAndRenderingRejectDuplicateFields() {
    for (String source :
        List.of(
            "{\"schemaVersion\":1,\"messages\":{},\"messages\":{\"home\":\"Home\"}}",
            "{\"home\":\"Old\",\"home\":\"Home\"}",
            "{\"home\":{\"defaultMessage\":\"Old\",\"defaultMessage\":\"Home\"}}",
            "{\"home\":{\"defaultMessage\":\"Home\",\"metadata\":{\"key\":1,\"key\":2}}}")) {
      assertEquals(
          "INVALID_FORMATJS",
          assertThrows(LocalizationParseException.class, () -> parse(source, filterOptions))
              .code());
      for (boolean removeUntranslated : List.of(false, true)) {
        assertEquals(
            "INVALID_FORMATJS",
            assertThrows(
                    LocalizationParseException.class,
                    () ->
                        localize(
                            source, Map.of("home", "Accueil"), filterOptions, removeUntranslated))
                .code());
      }
    }
  }

  @Test
  public void utf8BomAndMultibytePrefixKeepCorrectPatchOffsets() {
    String source = "\uFEFF{\"caf\u00e9\":\"Caf\u00e9\",\"home\":{\"defaultMessage\":\"Home\"}}\n";
    assertEquals(Set.of("caf\u00e9", "home"), parse(source, filterOptions).messages().keySet());
    assertEquals(
        source.replace("\"Home\"", "\"Accueil\""),
        localize(source, Map.of("home", "Accueil"), filterOptions, false));
  }

  @Test
  public void explicitOptionsStillSelectGenericJsonIdentities() {
    List<String> options = List.of("useFullKeyPath=true");
    String duplicate = "{\"home\":\"Old\",\"home\":\"Home\"}";
    assertThrows(LocalizationParseException.class, () -> parse(duplicate, filterOptions));
    assertEquals("Home", parse(duplicate, options).messages().get("home").defaultMessage());
    assertEquals(
        "Accueil",
        parse(localize(duplicate, Map.of("home", "Accueil"), options, false), options)
            .messages()
            .get("home")
            .defaultMessage());
    assertEquals(
        Set.of("home/defaultMessage", "home/description"),
        parse(HOME, options).messages().keySet());
    assertEquals(
        HOME.replace("\"Home\"", "\"Accueil\""),
        localize(HOME, Map.of("home/defaultMessage", "Accueil"), options, false));
    assertEquals(
        "UNKNOWN_SKELETON_SLOT",
        assertThrows(
                LocalizationParseException.class,
                () -> localize(HOME, Map.of("home", "Accueil"), options, false))
            .code());
  }

  @Test
  public void explicitDescriptorSelectionStillPreservesNotesAndRemovalPolicy() throws Exception {
    List<String> options =
        List.of(
            "extractAllPairs=false",
            "exceptions=defaultMessage",
            "removeKeySuffix=/defaultMessage",
            "noteKeyPattern=description");
    assertEquals(Set.of("home"), parse(HOME, options).messages().keySet());
    assertEquals("Navigation label", parse(HOME, options).messages().get("home").description());
    assertEquals(
        HOME.replace("\"Home\"", "\"Accueil\""),
        localize(HOME, Map.of("home", "Accueil"), options, false));
    assertEquals(JSON.readTree("{}"), JSON.readTree(localize(HOME, Map.of(), options, true)));
  }

  @Test
  public void commentsStillSelectGenericJsonWithoutOptions() {
    String duplicate = "{/* Label */\"home\":\"Old\",\"home\":\"Home\"}";
    assertEquals("Home", parse(duplicate, filterOptions).messages().get("home").defaultMessage());
    assertEquals(
        "Accueil",
        parse(localize(duplicate, Map.of("home", "Accueil"), filterOptions, false), filterOptions)
            .messages()
            .get("home")
            .defaultMessage());
    String source = "{ /* Label */ \"group\": {\"label\": \"Home\"} }\n";
    assertEquals(Set.of("group/label"), parse(source, filterOptions).messages().keySet());
    assertEquals(
        source.replace("\"Home\"", "\"Accueil\""),
        localize(source, Map.of("group/label", "Accueil"), filterOptions, false));
  }

  private static LocalizationCatalog parse(String source, List<String> options) {
    return LocalizationFileConverters.parseForMojito(
        LocalizationFileFormat.FORMATJS_JSON, source.getBytes(StandardCharsets.UTF_8), options);
  }

  private static String localize(
      String source,
      Map<String, String> translations,
      List<String> options,
      boolean removeUntranslated) {
    return new String(
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.FORMATJS_JSON,
            source.getBytes(StandardCharsets.UTF_8),
            translations,
            options,
            removeUntranslated),
        StandardCharsets.UTF_8);
  }
}
