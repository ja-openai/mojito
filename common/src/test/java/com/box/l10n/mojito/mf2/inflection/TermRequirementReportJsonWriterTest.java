package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.ReadableTermPack;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Diagnostic;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.MessageRequirement;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Summary;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermRequirementReport;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermUsageRequirement;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermValidation;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermValidationStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TermRequirementReportJsonWriterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TermRequirementJsonLoader loader = new TermRequirementJsonLoader();
  private final TermRequirementValidator validator = new TermRequirementValidator();
  private final TermRequirementReportJsonWriter writer = new TermRequirementReportJsonWriter();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new TermRequirementReportJsonWriter(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("objectMapper");
  }

  @Test
  public void rejectsNullReport() {
    assertThatThrownBy(() -> writer.write("fr", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("report");
  }

  @Test
  public void writesPythonCompatibleRequirementReportShape() {
    TermUsageCatalog usageCatalog = loader.loadUsageCatalog(usageCatalogJson());
    ReadableTermPack termPack = loader.loadTermPack(readableTermPackJson());
    TermRequirementReport report =
        validator.validate(usageCatalog.messages(), usageCatalog.argumentTerms(), termPack.terms());

    JsonNode json = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));

    assertThat(json.get("schema").asText())
        .isEqualTo("mojito-mf2-inflection/term-requirement-report/v0");
    assertThat(json.get("locale").asText()).isEqualTo("fr");
    assertThat(json.at("/summary/messages").asInt()).isEqualTo(3);
    assertThat(json.at("/summary/termUsages").asInt()).isEqualTo(3);
    assertThat(json.at("/summary/diagnostics").asInt()).isZero();
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/span/0").asInt()).isEqualTo(19);
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/span/1").asInt()).isEqualTo(62);
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/validations/0/status").asText())
        .isEqualTo("ok");
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/validations/0/termId").asText())
        .isEqualTo("item.iron_sword");
  }

  @Test
  public void matchesCheckedInPythonReportFixture() {
    TermUsageCatalog usageCatalog = loader.loadUsageCatalog(usageCatalogJson());
    ReadableTermPack termPack = loader.loadTermPack(readableTermPackJson());
    TermRequirementReport report =
        validator.validate(usageCatalog.messages(), usageCatalog.argumentTerms(), termPack.terms());

    JsonNode actual = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));
    JsonNode expected =
        objectMapper.readTreeUnchecked(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/fr_term_requirement_report_example.json"));

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void icuExtractorMatchesCheckedInPythonReportFixture() {
    TermUsageCatalog usageCatalog = loader.loadUsageCatalog(usageCatalogJson());
    ReadableTermPack termPack = loader.loadTermPack(readableTermPackJson());
    TermRequirementValidator icuValidator =
        new TermRequirementValidator(new IcuMessage2TermUsageExtractor());
    TermRequirementReport report =
        icuValidator.validate(
            usageCatalog.messages(), usageCatalog.argumentTerms(), termPack.terms());

    JsonNode actual = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));
    JsonNode expected =
        objectMapper.readTreeUnchecked(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/fr_term_requirement_report_example.json"));

    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void icuExtractorWritesSelectMessageRequirementReport() {
    String message =
        ".input {$count :number}\n"
            + ".match $count\n"
            + "1 {{Vous avez supprim\u00e9 {$item :term article=definite count=$count}.}}\n"
            + "* {{Vous avez supprim\u00e9 {$item :term article=definite count=$count}.}}";
    ReadableTermPack termPack = loader.loadTermPack(readableTermPackJson());
    TermRequirementValidator icuValidator =
        new TermRequirementValidator(new IcuMessage2TermUsageExtractor());
    TermRequirementReport report =
        icuValidator.validate(
            Map.of("inventory.deleted.select", message),
            Map.of("inventory.deleted.select", Map.of("item", List.of("item.iron_sword"))),
            termPack.terms());

    JsonNode json = objectMapper.readTreeUnchecked(writer.write("fr", report));
    JsonNode termUsages = json.at("/messages/inventory.deleted.select/termUsages");

    assertThat(json.at("/summary/messages").asInt()).isEqualTo(1);
    assertThat(json.at("/summary/termUsages").asInt()).isEqualTo(2);
    assertThat(json.at("/summary/diagnostics").asInt()).isZero();
    assertThat(termUsages.get(0).get("argument").asText()).isEqualTo("item");
    assertThat(termUsages.get(0).at("/options/count").asText()).isEqualTo("$count");
    assertThat(termUsages.get(0).get("requirements"))
        .extracting(JsonNode::asText)
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "elision",
            "forms.definite.singular",
            "forms.definite.plural",
            "forms.count.one",
            "forms.count.other");
    assertThat(termUsages.get(1).at("/validations/0/status").asText()).isEqualTo("ok");
  }

  @Test
  public void writesDiagnosticsWithLowercaseMissingStatus() {
    TermRequirementReport report =
        validator.validate(
            Map.of("inventory.deleted", "{$item :term article=definite}."),
            Map.of("inventory.deleted", Map.of("item", java.util.List.of("item.missing"))),
            Map.of());

    JsonNode json = objectMapper.readTreeUnchecked(writer.write("fr", report));

    assertThat(json.at("/summary/diagnostics").asInt()).isEqualTo(1);
    assertThat(json.at("/diagnostics/0/messageId").asText()).isEqualTo("inventory.deleted");
    assertThat(json.at("/diagnostics/0/argument").asText()).isEqualTo("item");
    assertThat(json.at("/diagnostics/0/termId").asText()).isEqualTo("item.missing");
    assertThat(json.at("/diagnostics/0/missing/0").asText()).isEqualTo("missing-term");
    assertThat(json.at("/diagnostics/0/span/0").asInt()).isEqualTo(0);
    assertThat(json.at("/diagnostics/0/span/1").asInt()).isEqualTo(30);
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/validations/0/status").asText())
        .isEqualTo("missing");
  }

  @Test
  public void writesUnboundClosedWorldTermUsageWithNullTermId() {
    TermRequirementReport report =
        validator.validate(
            Map.of("inventory.deleted", "{$item :term article=definite}."), Map.of(), Map.of());

    JsonNode json = objectMapper.readTreeUnchecked(writer.write("fr", report));

    assertThat(json.at("/summary/diagnostics").asInt()).isEqualTo(1);
    assertThat(json.at("/diagnostics/0/messageId").asText()).isEqualTo("inventory.deleted");
    assertThat(json.at("/diagnostics/0/argument").asText()).isEqualTo("item");
    assertThat(json.at("/diagnostics/0/termId").isNull()).isTrue();
    assertThat(json.at("/diagnostics/0/missing/0").asText()).isEqualTo("missing-argument-terms");
    assertThat(json.at("/diagnostics/0/span/0").asInt()).isEqualTo(0);
    assertThat(json.at("/diagnostics/0/span/1").asInt()).isEqualTo(30);
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/termIds")).isEmpty();
    assertThat(json.at("/messages/inventory.deleted/termUsages/0/validations")).isEmpty();
  }

  @Test
  public void writesHindiPronounRelatedArgumentDiagnostics() {
    TermRequirementReport report =
        validator.validate(
            "hi",
            Map.of("inventory.owner", "{$owner :term person=first case=genitive agreeWith=$item}."),
            Map.of(),
            Map.of());

    JsonNode json = objectMapper.readTreeUnchecked(writer.write("hi", report));

    assertThat(json.at("/summary/diagnostics").asInt()).isEqualTo(1);
    assertThat(json.at("/diagnostics/0/messageId").asText()).isEqualTo("inventory.owner");
    assertThat(json.at("/diagnostics/0/argument").asText()).isEqualTo("owner");
    assertThat(json.at("/diagnostics/0/relatedArgument").asText()).isEqualTo("item");
    assertThat(json.at("/diagnostics/0/termId").isNull()).isTrue();
    assertThat(json.at("/diagnostics/0/missing/0").asText())
        .isEqualTo("agreeWith.missing-argument-terms");
  }

  @Test
  public void writesDeterministicMessageOptionsAndDiagnosticOrder() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("count", "$count");
    options.put("article", "definite");
    TermUsageRequirement usage =
        new TermUsageRequirement(
            "item",
            options,
            new SourceSpan(7, 49),
            List.of("gender", "forms.definite.singular"),
            List.of("item.iron_sword"),
            List.of(new TermValidation("item.iron_sword", TermValidationStatus.OK, List.of())));

    Map<String, MessageRequirement> messages = new LinkedHashMap<>();
    messages.put(
        "z.message",
        new MessageRequirement("Z {$item :term count=$count article=definite}.", List.of(usage)));
    messages.put(
        "a.message",
        new MessageRequirement("A {$item :term count=$count article=definite}.", List.of(usage)));
    TermRequirementReport report =
        new TermRequirementReport(
            messages,
            List.of(
                new Diagnostic("z.message", "item", "z.term", new SourceSpan(7, 49), List.of("z")),
                new Diagnostic("a.message", "item", null, new SourceSpan(7, 49), List.of("a"))),
            new Summary(2, 2, 2));

    JsonNode json = objectMapper.readTreeUnchecked(writer.write("fr", report));

    assertThat(fieldNames(json.get("messages"))).containsExactly("a.message", "z.message");
    assertThat(fieldNames(json.at("/messages/a.message/termUsages/0/options")))
        .containsExactly("article", "count");
    assertThat(json.at("/diagnostics/0/messageId").asText()).isEqualTo("a.message");
    assertThat(json.at("/diagnostics/0/missing/0").asText()).isEqualTo("a");
    assertThat(json.at("/diagnostics/1/messageId").asText()).isEqualTo("z.message");
  }

  private String usageCatalogJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": "fr",
          "messages": {
            "inventory.deleted": "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.",
            "inventory.found": "Vous avez trouv\u00e9 {$item :term article=indefinite}.",
            "inventory.weighed": "Le poids est de {$item :term count=$count}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "item": ["item.iron_sword", "concept.book", "unit.pound"]
            },
            "inventory.found": {
              "item": ["item.iron_sword"]
            },
            "inventory.weighed": {
              "item": ["unit.pound"]
            }
          }
        }
        """;
  }

  private String readableTermPackJson() {
    return """
        {
          "schema": "mojito-mf2-term-pack/example-v0",
          "locale": "fr",
          "terms": {
            "item.iron_sword": {
              "text": "\u00e9p\u00e9e de fer",
              "morphology": {
                "partOfSpeech": "noun",
                "gender": "feminine",
                "number": "singular",
                "startsWithVowelSound": true
              },
              "forms": {
                "definite.singular": "l'\u00e9p\u00e9e de fer",
                "definite.plural": "les \u00e9p\u00e9es de fer",
                "indefinite.singular": "une \u00e9p\u00e9e de fer",
                "indefinite.plural": "des \u00e9p\u00e9es de fer",
                "count.one": "1 \u00e9p\u00e9e de fer",
                "count.other": "{$count} \u00e9p\u00e9es de fer"
              }
            },
            "concept.book": {
              "text": "livre",
              "morphology": {
                "partOfSpeech": "noun",
                "gender": "masculine",
                "number": "singular",
                "startsWithVowelSound": false,
                "sense": "book"
              },
              "forms": {
                "definite.singular": "le livre",
                "definite.plural": "les livres",
                "count.one": "1 livre",
                "count.other": "{$count} livres"
              }
            },
            "unit.pound": {
              "text": "livre",
              "morphology": {
                "partOfSpeech": "noun",
                "gender": "feminine",
                "number": "singular",
                "startsWithVowelSound": false,
                "sense": "unit-pound"
              },
              "forms": {
                "definite.singular": "la livre",
                "definite.plural": "les livres",
                "count.one": "1 livre",
                "count.other": "{$count} livres"
              }
            }
          }
        }
        """;
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<String> fieldNames(JsonNode jsonNode) {
    List<String> fields = new ArrayList<>();
    jsonNode.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
