package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import com.box.l10n.mojito.mf2.inflection.HindiPronounAgreementPackJsonLoader.HindiPronounAgreementPack;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class Mf2TermRendererTest {

  @Test
  public void rendersOrdinaryCompiledTermsThroughFacade() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());

    assertThat(
            renderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el agua.");
  }

  @Test
  public void rendersHindiPronounsThroughLocaleExtension() {
    Mf2TermRenderer renderer =
        Mf2TermRenderer.forHindi(hindiTermPack(), hindiPronounAgreementPack());

    assertThat(
            renderer.renderMessage(
                "{$owner :term person=first case=genitive count=$ownerCount "
                    + "agreeWith=$item agreeWithCount=$itemCount} "
                    + "{$item :term case=direct count=$itemCount}.",
                Map.of("item", "hi.case.आँख"),
                Map.of("ownerCount", "1", "itemCount", "2")))
        .isEqualTo("मेरी आँखें.");
  }

  @Test
  public void rendersSingleCompiledTermThroughFacade() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(hindiTermPack());

    assertThat(
            renderer.renderTerm(
                "hi.case.अंगारा",
                Map.of("case", "oblique", "count", "$count"),
                Map.of("count", "2")))
        .isEqualTo("अंगारों");
  }

  @Test
  public void rejectsNullFacadeInputsAtApiBoundary() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());
    TermUsageCatalog usageCatalog =
        usageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {}
              }
            }
            """);

    assertThatThrownBy(() -> renderer.renderBoundMessage(null, "inventory.deleted", Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("usageCatalog");
    assertThatThrownBy(() -> renderer.renderBoundMessage(usageCatalog, null, Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("messageId");
    assertThatThrownBy(() -> renderer.requireRenderableBoundMessage(null, "inventory.deleted"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("usageCatalog");
    assertThatThrownBy(() -> renderer.requireRenderableBoundMessage(usageCatalog, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("messageId");
    assertThatThrownBy(
            () ->
                renderer.renderBoundMessage(
                    usageCatalog, "inventory.deleted", (Map<String, String>) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("variables");
    assertThatThrownBy(() -> renderer.renderMessage(null, Map.of(), Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("message");
    assertThatThrownBy(() -> renderer.renderMessage("", (Map<String, String>) null, Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("termArguments");
    assertThatThrownBy(() -> renderer.renderMessage("", Map.of(), (Map<String, String>) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("variables");
    assertThatThrownBy(() -> renderer.renderTerm("item.water", null, Map.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("options");
    assertThatThrownBy(
            () -> renderer.renderTerm("item.water", Map.of(), (Map<String, String>) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("variables");
  }

  @Test
  public void rejectsInvalidRuntimeMapEntriesAtFacadeBoundary() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());

    assertThatThrownBy(() -> renderer.renderTerm(" ", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("termId must not be blank");

    assertThatThrownBy(
            () -> renderer.renderTerm("item.water", Map.of("article", " "), Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("options value must not be blank: article");

    assertThatThrownBy(
            () -> renderer.renderTerm("item.water", Map.of("", "definite"), Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("options key must not be blank");

    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Has eliminado {$item :term article=definite count=$count}.",
                    Map.of("", "item.water"),
                    Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("termArguments key must not be blank");

    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Has eliminado {$item :term article=definite count=$count}.",
                    Map.of("item", " "),
                    Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("termArguments value must not be blank: item");

    Map<String, String> variablesWithNullValue = new HashMap<>();
    variablesWithNullValue.put("count", null);
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Has eliminado {$item :term article=definite count=$count}.",
                    Map.of("item", "item.water"),
                    variablesWithNullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("variables value must not be null: count");

    assertThatThrownBy(() -> renderer.renderMessage("Plain", Map.of(), Map.of("", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("variables key must not be blank");
  }

  @Test
  public void rendersSingletonBindingManifestThroughFacade() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());

    assertThat(
            renderer.renderBoundMessage(
                usageCatalog(
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
                    """),
                "inventory.deleted",
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el agua.");
  }

  @Test
  public void preflightsBoundManifestAgainstCompiledPackBeforeRuntimeCountSelection() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishPackMissingBarePlural());
    TermUsageCatalog usageCatalog =
        usageCatalog(
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

    assertThatThrownBy(
            () ->
                renderer.renderBoundMessage(
                    usageCatalog, "inventory.deleted", Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Failed to render term argument item in message inventory.deleted bound to item.water at span 14-57")
        .hasMessageContaining("Missing Spanish bare form bare.plural for term item.water");
  }

  @Test
  public void requiresRenderableBoundManifestWithoutRuntimeVariables() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());

    renderer.requireRenderableBoundMessage(
        usageCatalog(
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
            """),
        "inventory.deleted");
  }

  @Test
  public void rejectsAmbiguousBindingManifestAtRenderTime() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());
    TermUsageCatalog usageCatalog =
        usageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["item.water", "item.unknown"]
                }
              }
            }
            """);

    assertThatThrownBy(
            () ->
                renderer.renderBoundMessage(
                    usageCatalog, "inventory.deleted", Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MF2 term binding manifest is not renderable: 1 binding diagnostics")
        .hasMessageContaining("inventory.deleted.item=ambiguous(item.water,item.unknown)");
  }

  @Test
  public void rejectsMissingBindingManifestArgumentAtRenderTime() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());
    TermUsageCatalog usageCatalog =
        usageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {}
              }
            }
            """);

    assertThatThrownBy(
            () ->
                renderer.renderBoundMessage(
                    usageCatalog, "inventory.deleted", Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MF2 term binding manifest is not renderable: 1 binding diagnostics")
        .hasMessageContaining("inventory.deleted.item=missing");
  }

  @Test
  public void rejectsUnknownSingletonBindingManifestArgumentAtRenderTime() {
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(spanishTermPack());
    TermUsageCatalog usageCatalog =
        usageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "es",
              "messages": {
                "inventory.deleted": "Has eliminado {$item :term article=definite count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["item.unknown"]
                }
              }
            }
            """);

    assertThatThrownBy(
            () ->
                renderer.renderBoundMessage(
                    usageCatalog, "inventory.deleted", Map.of("count", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MF2 term binding manifest is not renderable: 1 binding diagnostics")
        .hasMessageContaining("inventory.deleted.item=unknown(item.unknown)");
  }

  @Test
  public void rendersHindiPronounAgreementFromBoundManifestDependency() {
    Mf2TermRenderer renderer =
        Mf2TermRenderer.forHindi(hindiTermPack(), hindiPronounAgreementPack());

    assertThat(
            renderer.renderBoundMessage(
                usageCatalog(
                    """
                    {
                      "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
                      "locale": "hi",
                      "messages": {
                        "inventory.owner": "{$owner :term person=first case=genitive count=$ownerCount agreeWith=$item agreeWithCount=$itemCount} {$item :term case=direct count=$itemCount}."
                      },
                      "argumentTerms": {
                        "inventory.owner": {
                          "item": ["hi.case.अंगारा"]
                        }
                      }
                    }
                    """),
                "inventory.owner",
                Map.of("ownerCount", "1", "itemCount", "2")))
        .isEqualTo("मेरे अंगारे.");
  }

  @Test
  public void rejectsHindiExtensionForNonHindiTermPack() {
    assertThatThrownBy(
            () -> Mf2TermRenderer.forHindi(spanishTermPack(), hindiPronounAgreementPack()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Hindi pronoun rendering requires Hindi term pack: es");
  }

  private static CompiledTermPack spanishTermPack() {
    return compiledTermPack(
        "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json");
  }

  private static CompiledTermPack spanishPackMissingBarePlural() {
    List<String> strings = List.of("bare.singular", "agua", "item.water", "water");
    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "es",
        strings,
        List.of(new TermRow(2, 1, 16673, 3, 0)),
        List.of(new FormSet(2, List.of(new FormRow(0, 1, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
  }

  private static CompiledTermPack hindiTermPack() {
    return compiledTermPack(
        "com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json");
  }

  private static CompiledTermPack compiledTermPack(String path) {
    return new CompiledTermPackJsonLoader().load(readResource(path));
  }

  private static TermUsageCatalog usageCatalog(String json) {
    return new TermRequirementJsonLoader().loadUsageCatalog(json);
  }

  private static HindiPronounAgreementPack hindiPronounAgreementPack() {
    return new HindiPronounAgreementPackJsonLoader()
        .load(
            readResource(
                "com/box/l10n/mojito/mf2/inflection/hi_pronoun_agreement_pack_fixture.json"));
  }

  private static String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
