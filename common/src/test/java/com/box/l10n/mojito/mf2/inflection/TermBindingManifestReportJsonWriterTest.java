package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.ArgumentBinding;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.BindingStatus;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.Diagnostic;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.MessageBinding;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.Summary;
import com.box.l10n.mojito.mf2.inflection.TermBindingManifestValidator.TermBindingReport;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TermBindingManifestReportJsonWriterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TermRequirementJsonLoader loader = new TermRequirementJsonLoader();
  private final TermBindingManifestValidator validator = new TermBindingManifestValidator();
  private final TermBindingManifestReportJsonWriter writer =
      new TermBindingManifestReportJsonWriter();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new TermBindingManifestReportJsonWriter(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("objectMapper");
  }

  @Test
  public void rejectsNullReport() {
    assertThatThrownBy(() -> writer.write("es", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("report");
  }

  @Test
  public void writesRenderableSingletonBindingReportShape() {
    TermUsageCatalog usageCatalog =
        loader.loadUsageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["item.water"]
                }
              }
            }
            """);
    TermBindingReport report = validator.validate(usageCatalog);

    JsonNode json = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));

    assertThat(json.get("schema").asText())
        .isEqualTo("mojito-mf2-inflection/term-binding-report/v0");
    assertThat(json.get("locale").asText()).isEqualTo("es");
    assertThat(json.at("/summary/messages").asInt()).isEqualTo(1);
    assertThat(json.at("/summary/requiredArguments").asInt()).isEqualTo(1);
    assertThat(json.at("/summary/diagnostics").asInt()).isZero();
    assertThat(json.at("/diagnostics")).isEmpty();
    assertThat(json.at("/messages/inventory.deleted/source").asText())
        .isEqualTo("Has eliminado {$item :term article=definite count=$count}.");
    assertThat(json.at("/messages/inventory.deleted/requiredArguments/0").asText())
        .isEqualTo("item");
    assertThat(json.at("/messages/inventory.deleted/arguments/item/status").asText())
        .isEqualTo("ok");
    assertThat(json.at("/messages/inventory.deleted/arguments/item/termIds/0").asText())
        .isEqualTo("item.water");
  }

  @Test
  public void writesDiagnosticsWithLowercaseStatuses() {
    TermUsageCatalog usageCatalog =
        loader.loadUsageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}.",
                "inventory.moved": "{$item :term article=definite} -> {$place :term article=definite}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["item.water", "item.fire"]
                },
                "inventory.moved": {
                  "item": []
                }
              }
            }
            """);
    TermBindingReport report = validator.validate(usageCatalog);

    JsonNode json = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));

    assertThat(json.at("/summary/messages").asInt()).isEqualTo(2);
    assertThat(json.at("/summary/requiredArguments").asInt()).isEqualTo(3);
    assertThat(json.at("/summary/diagnostics").asInt()).isEqualTo(3);
    assertThat(json.at("/diagnostics/0/messageId").asText()).isEqualTo("inventory.deleted");
    assertThat(json.at("/diagnostics/0/argument").asText()).isEqualTo("item");
    assertThat(json.at("/diagnostics/0/status").asText()).isEqualTo("ambiguous");
    assertThat(json.at("/diagnostics/0/termIds/1").asText()).isEqualTo("item.fire");
    assertThat(json.at("/diagnostics/1/status").asText()).isEqualTo("missing");
    assertThat(json.at("/diagnostics/2/argument").asText()).isEqualTo("place");
    assertThat(json.at("/messages/inventory.moved/arguments/place/status").asText())
        .isEqualTo("missing");
  }

  @Test
  public void writesHindiPronounDependencyBindingReport() {
    TermUsageCatalog usageCatalog =
        loader.loadUsageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "hi",
              "messages": {
                "inventory.owner": "{$owner :term person=first case=genitive agreeWith=$item}."
              },
              "argumentTerms": {
                "inventory.owner": {
                  "item": ["hi.case.अंगारा"]
                }
              }
            }
            """);
    TermBindingReport report = validator.validate(usageCatalog);

    JsonNode json = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));

    assertThat(json.at("/messages/inventory.owner/requiredArguments/0").asText()).isEqualTo("item");
    assertThat(json.at("/messages/inventory.owner/arguments/item/status").asText()).isEqualTo("ok");
    assertThat(json.at("/messages/inventory.owner/arguments/owner").isMissingNode()).isTrue();
  }

  @Test
  public void writesUnsupportedLocaleRuntimeTermInflectionStatus() {
    TermUsageCatalog usageCatalog =
        loader.loadUsageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "nb-NO",
              "messages": {
                "inventory.deleted": "Slettet {$item :term definiteness=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["nb.item.book"]
                }
              }
            }
            """);
    TermBindingReport report = validator.validate(usageCatalog);

    JsonNode json = objectMapper.readTreeUnchecked(writer.write(usageCatalog.locale(), report));

    assertThat(json.at("/summary/diagnostics").asInt()).isEqualTo(1);
    assertThat(json.at("/diagnostics/0/status").asText())
        .isEqualTo("unsupported-locale-runtime-term-inflection");
    assertThat(json.at("/messages/inventory.deleted/arguments/item/status").asText())
        .isEqualTo("unsupported-locale-runtime-term-inflection");
    assertThat(json.at("/messages/inventory.deleted/arguments/item/termIds/0").asText())
        .isEqualTo("nb.item.book");
  }

  @Test
  public void writesDeterministicMessageArgumentAndDiagnosticOrder() {
    Map<String, ArgumentBinding> zThenAArguments = new LinkedHashMap<>();
    zThenAArguments.put("zebra", new ArgumentBinding("zebra", BindingStatus.MISSING, List.of()));
    zThenAArguments.put(
        "apple", new ArgumentBinding("apple", BindingStatus.OK, List.of("item.apple")));

    Map<String, MessageBinding> zThenAMessages = new LinkedHashMap<>();
    zThenAMessages.put(
        "z.message", new MessageBinding("Z {$zebra :term}.", List.of("zebra"), zThenAArguments));
    zThenAMessages.put(
        "a.message",
        new MessageBinding(
            "A {$apple :term}.",
            List.of("apple"),
            Map.of(
                "apple", new ArgumentBinding("apple", BindingStatus.OK, List.of("item.apple")))));

    TermBindingReport report =
        new TermBindingReport(
            zThenAMessages,
            List.of(
                new Diagnostic("z.message", "zebra", BindingStatus.MISSING, List.of()),
                new Diagnostic(
                    "a.message",
                    "apple",
                    BindingStatus.AMBIGUOUS,
                    List.of("item.apple", "item.apfel"))),
            new Summary(2, 2, 2));

    JsonNode json = objectMapper.readTreeUnchecked(writer.write("en", report));

    assertThat(fieldNames(json.get("messages"))).containsExactly("a.message", "z.message");
    assertThat(fieldNames(json.at("/messages/z.message/arguments")))
        .containsExactly("apple", "zebra");
    assertThat(json.at("/diagnostics/0/messageId").asText()).isEqualTo("a.message");
    assertThat(json.at("/diagnostics/1/messageId").asText()).isEqualTo("z.message");
  }

  private List<String> fieldNames(JsonNode node) {
    List<String> fieldNames = new ArrayList<>();
    node.fieldNames().forEachRemaining(fieldNames::add);
    return fieldNames;
  }
}
