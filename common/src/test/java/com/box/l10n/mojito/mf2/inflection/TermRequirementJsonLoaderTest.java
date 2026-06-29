package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.ReadableTermPack;
import com.box.l10n.mojito.mf2.inflection.TermRequirementJsonLoader.TermUsageCatalog;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Term;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermRequirementReport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.Test;

public class TermRequirementJsonLoaderTest {

  private final TermRequirementJsonLoader loader = new TermRequirementJsonLoader();
  private final TermRequirementValidator validator = new TermRequirementValidator();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void loadsReadableFixturesAndValidatesRequirements() {
    TermUsageCatalog usageCatalog = loader.loadUsageCatalog(usageCatalogJson());
    ReadableTermPack termPack = loader.loadTermPack(readableTermPackJson());

    TermRequirementReport report =
        validator.validate(usageCatalog.messages(), usageCatalog.argumentTerms(), termPack.terms());

    assertThat(usageCatalog.schema())
        .isEqualTo(TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA);
    assertThat(usageCatalog.locale()).isEqualTo("fr");
    assertThat(termPack.locale()).isEqualTo("fr");
    assertThat(report.summary().messages()).isEqualTo(3);
    assertThat(report.summary().termUsages()).isEqualTo(3);
    assertThat(report.summary().diagnostics()).isZero();
    assertThat(report.messages().get("inventory.found").termUsages().get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "elision",
            "forms.indefinite.singular",
            "forms.indefinite.plural");
  }

  @Test
  public void loadedCatalogAndTermPackCollectionsAreImmutable() {
    TermUsageCatalog usageCatalog = loader.loadUsageCatalog(usageCatalogJson());
    ReadableTermPack termPack = loader.loadTermPack(readableTermPackJson());

    assertThatThrownBy(() -> usageCatalog.messages().put("new.message", "source"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                usageCatalog
                    .argumentTerms()
                    .get("inventory.deleted")
                    .put("place", java.util.List.of("place.castle")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> usageCatalog.argumentTerms().get("inventory.deleted").get("item").add("item.new"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                termPack
                    .terms()
                    .get("item.iron_sword")
                    .forms()
                    .put("bare.singular", "\u00e9p\u00e9e"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new TermRequirementJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("objectMapper");
  }

  @Test
  public void rejectsNullUsageCatalogJson() {
    assertThatThrownBy(() -> loader.loadUsageCatalog((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("json");
  }

  @Test
  public void rejectsNullUsageCatalogRoot() {
    assertThatThrownBy(() -> loader.loadUsageCatalog((JsonNode) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("root");
  }

  @Test
  public void rejectsNonObjectUsageCatalogRoot() {
    assertThatThrownBy(() -> loader.loadUsageCatalog(objectMapper.readTreeUnchecked("[]")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object root: message term binding manifest");
  }

  @Test
  public void rejectsNullTermPackJson() {
    assertThatThrownBy(() -> loader.loadTermPack((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("json");
  }

  @Test
  public void rejectsNullTermPackRoot() {
    assertThatThrownBy(() -> loader.loadTermPack((JsonNode) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("root");
  }

  @Test
  public void rejectsNonObjectTermPackRoot() {
    assertThatThrownBy(() -> loader.loadTermPack(objectMapper.readTreeUnchecked("[]")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object root: readable term pack");
  }

  @Test
  public void preservesEmptyMessageSourceInBindingManifest() {
    TermUsageCatalog usageCatalog =
        loader.loadUsageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "messages": {
                "inventory.empty": ""
              },
              "argumentTerms": {
                "inventory.empty": {}
              }
            }
            """);

    assertThat(usageCatalog.messages()).containsEntry("inventory.empty", "");
  }

  @Test
  public void letsValidatorReportSemanticGapsFromReadableJson() {
    TermUsageCatalog usageCatalog = loader.loadUsageCatalog(usageCatalogJson());
    ReadableTermPack termPack =
        loader.loadTermPack(
            """
            {
              "locale": "fr",
              "terms": {
                "item.iron_sword": {
                  "text": "\u00e9p\u00e9e de fer",
                  "morphology": {
                    "partOfSpeech": "noun",
                    "gender": "feminine",
                    "number": "singular"
                  },
                  "forms": {
                    "definite.singular": "l'\u00e9p\u00e9e de fer",
                    "count.one": "1 \u00e9p\u00e9e de fer"
                  }
                }
              }
            }
            """);

    TermRequirementReport report =
        validator.validate(usageCatalog.messages(), usageCatalog.argumentTerms(), termPack.terms());

    assertThat(report.diagnostics()).hasSize(5);
    assertThat(report.diagnostics().get(0).termId()).isEqualTo("item.iron_sword");
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly("startsWithVowelSound", "definite.plural", "count.other");
  }

  @Test
  public void loadsTurkishSuffixMetadataFromReadableJson() {
    TermUsageCatalog usageCatalog =
        loader.loadUsageCatalog(
            """
            {
              "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
              "locale": "tr",
              "messages": {
                "inventory.deleted": "Silindi {$item :term case=accusative count=$count}."
              },
              "argumentTerms": {
                "inventory.deleted": {
                  "item": ["item.house"]
                }
              }
            }
            """);
    ReadableTermPack termPack =
        loader.loadTermPack(
            """
            {
              "locale": "tr",
              "terms": {
                "item.house": {
                  "text": "ev",
                  "morphology": {
                    "partOfSpeech": "noun",
                    "number": "singular",
                    "turkishSuffix": {
                      "vowelEnd": false,
                      "frontVowel": true,
                      "roundedVowel": false,
                      "hardConsonant": false
                    }
                  },
                  "forms": {
                    "bare.singular": "ev"
                  }
                }
              }
            }
            """);

    TermRequirementReport report =
        validator.validate(
            usageCatalog.locale(),
            usageCatalog.messages(),
            usageCatalog.argumentTerms(),
            termPack.terms());

    assertThat(report.diagnostics()).isEmpty();
    assertThat(termPack.terms().get("item.house").morphology().turkishSuffix().frontVowel())
        .isTrue();
  }

  @Test
  public void rejectsStructurallyInvalidReadableJson() {
    assertThatThrownBy(() -> loader.loadTermPack("{\"terms\":{\"item\":{\"text\":42}}}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected text field: text");
  }

  @Test
  public void rejectsUnsupportedBindingManifestSchema() {
    String json =
        """
        {
          "schema": "mojito-mf2-term-usage/example-v0",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "item": ["item.iron_sword"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported schema");
  }

  @Test
  public void rejectsUnsupportedBindingManifestSchemaInRecordConstructor() {
    assertThatThrownBy(
            () ->
                new TermUsageCatalog(
                    "mojito-mf2-term-usage/example-v0",
                    null,
                    java.util.Map.of(),
                    java.util.Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported schema");
  }

  @Test
  public void rejectsBlankLocaleInUsageCatalogJson() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": " ",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "item": ["item.iron_sword"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("locale must not be blank");
  }

  @Test
  public void rejectsBlankMessageIdsInUsageCatalogJson() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "messages": {
            "": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "": {
              "item": ["item.iron_sword"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-blank message id");
  }

  @Test
  public void rejectsBlankMessageIdsInRecordConstructor() {
    assertThatThrownBy(
            () ->
                new TermUsageCatalog(
                    TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
                    null,
                    java.util.Map.of("", "{$item :term article=definite}."),
                    java.util.Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-blank message id");
  }

  @Test
  public void rejectsBlankArgumentNamesInRecordConstructor() {
    assertThatThrownBy(
            () ->
                new TermUsageCatalog(
                    TermRequirementJsonLoader.MESSAGE_TERM_BINDING_MANIFEST_SCHEMA,
                    null,
                    java.util.Map.of("inventory.deleted", "{$item :term article=definite}."),
                    java.util.Map.of(
                        "inventory.deleted",
                        java.util.Map.of("", java.util.List.of("item.iron_sword")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-blank argument name for message: inventory.deleted");
  }

  @Test
  public void rejectsBlankReadableTermIdsAtRecordBoundary() {
    assertThatThrownBy(
            () -> new ReadableTermPack(null, Map.of(" ", new Term("book", null, Map.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected non-blank term id");
  }

  @Test
  public void rejectsBlankTermTextAndFormsAtRecordBoundary() {
    assertThatThrownBy(() -> new Term(" ", null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("term text is required");
    assertThatThrownBy(() -> new Term("book", null, Map.of("bare.singular", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("form value is required");
    assertThatThrownBy(() -> new Term("book", null, Map.of(" ", "book")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("form key is required");
  }

  @Test
  public void rejectsArgumentTermsForUnknownMessage() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.renamed": {
              "item": ["item.iron_sword"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Argument terms reference unknown message");
  }

  @Test
  public void rejectsArgumentTermsForUnknownTermArgument() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "place": ["place.castle"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Argument terms reference unknown term argument inventory.deleted.place");
  }

  @Test
  public void rejectsDuplicateTermIdsForArgument() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "messages": {
            "inventory.deleted": "{$item :term article=definite}."
          },
          "argumentTerms": {
            "inventory.deleted": {
              "item": ["item.iron_sword", "item.iron_sword"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate term id");
  }

  @Test
  public void loadsHindiPronounAgreementBindingForReferencedArgument() {
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

    assertThat(usageCatalog.argumentTerms().get("inventory.owner").get("item"))
        .containsExactly("hi.case.अंगारा");
  }

  @Test
  public void rejectsHindiPronounSelfBindingAsUnusedTermArgument() {
    String json =
        """
        {
          "schema": "mojito-mf2-inflection/message-term-binding-manifest/v0",
          "locale": "hi",
          "messages": {
            "inventory.owner": "{$owner :term person=first case=genitive agreeWith=$item}."
          },
          "argumentTerms": {
            "inventory.owner": {
              "owner": ["hi.owner"],
              "item": ["hi.case.अंगारा"]
            }
          }
        }
        """;

    assertThatThrownBy(() -> loader.loadUsageCatalog(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Argument terms reference unknown term argument inventory.owner.owner");
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
                "bare.singular": "\u00e9p\u00e9e de fer",
                "bare.plural": "\u00e9p\u00e9es de fer",
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
                "bare.singular": "livre",
                "bare.plural": "livres",
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
                "bare.singular": "livre",
                "bare.plural": "livres",
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
}
