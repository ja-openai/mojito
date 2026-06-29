package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.mf2.inflection.TermInflectionProfilePackJsonLoader.TermInflectionProfilePack;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TermInflectionProfilePackJsonLoaderTest {

  private final TermInflectionProfilePackJsonLoader loader =
      new TermInflectionProfilePackJsonLoader();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void rejectsNullObjectMapper() {
    assertThatThrownBy(() -> new TermInflectionProfilePackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void rejectsNullJson() {
    assertThatThrownBy(() -> loader.load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("json");
  }

  @Test
  public void rejectsNullRoot() {
    assertThatThrownBy(() -> loader.load((JsonNode) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("root");
  }

  @Test
  public void rejectsNonObjectRoot() {
    assertThatThrownBy(() -> loader.load(objectMapper.readTreeUnchecked("[]")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object root: term inflection profile pack");
  }

  @Test
  public void loadsAuthoringProfileAndCompilesToRuntimePack() {
    TermInflectionProfilePack profilePack = fixturePack();

    TermRequirementValidator.TermRequirementReport report =
        new TermRequirementValidator()
            .validate(
                "fr",
                Map.of(
                    "inventory.deleted",
                    "Vous avez supprime {$item :term article=definite count=$count}."),
                Map.of("inventory.deleted", Map.of("item", List.of("item.iron_sword"))),
                profilePack.toRequirementTerms());
    assertThat(report.diagnostics()).isEmpty();

    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(profilePack.toCompiledTermPack());
    assertThat(
            renderer.renderMessage(
                "Vous avez supprime {$item :term article=definite count=$count}.",
                Map.of("item", "item.iron_sword"),
                Map.of("count", "2")))
        .isEqualTo("Vous avez supprime les epees de fer.");
    assertThat(
            renderer.renderMessage(
                "Total: {$item :term count=$count}.",
                Map.of("item", "item.iron_sword"),
                Map.of("count", "2")))
        .isEqualTo("Total: 2 epees de fer.");
  }

  @Test
  public void compilesReviewedHebrewConstructStateProfileToRuntimePack() {
    TermInflectionProfilePack profilePack =
        loader.load(
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "he",
              "provenance": {
                "license": "Mojito-authored",
                "generator": "glossary-term-inflection-profile/v0",
                "sourceLabels": [],
                "sources": []
              },
              "profiles": [{
                "termId": "he.reviewed.hand",
                "source": "יד",
                "status": "APPROVED",
                "morphology": {
                  "partOfSpeech": "noun",
                  "gender": "feminine",
                  "number": "singular",
                  "sense": "reviewed-product-term"
                },
                "forms": {
                  "bare.singular": "יד",
                  "bare.plural": "ידיים",
                  "construct.singular": "יד",
                  "construct.plural": "ידי",
                  "construct.dual": "ידי"
                },
                "diagnostics": [],
                "provenance": {
                  "reviewStatus": "approved",
                  "reviewedBy": "translator",
                  "reason": "product-reviewed-complete-construct-state"
                }
              }]
            }
            """);

    CompiledTermPack compiled = profilePack.toCompiledTermPack();
    Mf2TermRenderer renderer = Mf2TermRenderer.forCompiledTerms(compiled);

    assertThat(renderer.renderTerm("he.reviewed.hand", Map.of(), Map.of())).isEqualTo("יד");
    assertThat(renderer.renderTerm("he.reviewed.hand", Map.of("number", "plural"), Map.of()))
        .isEqualTo("ידיים");
    assertThat(
            renderer.renderTerm(
                "he.reviewed.hand",
                Map.of("definiteness", "construct", "number", "singular"),
                Map.of()))
        .isEqualTo("יד");
    assertThat(
            renderer.renderTerm(
                "he.reviewed.hand",
                Map.of("definiteness", "construct", "number", "plural"),
                Map.of()))
        .isEqualTo("ידי");
    assertThat(
            renderer.renderTerm(
                "he.reviewed.hand",
                Map.of("definiteness", "construct", "number", "dual"),
                Map.of()))
        .isEqualTo("ידי");
    assertThat(
            renderer.renderMessage(
                "נבחרו {$item :term definiteness=construct number=dual}.",
                Map.of("item", "he.reviewed.hand"),
                Map.of()))
        .isEqualTo("נבחרו ידי.");
  }

  @Test
  public void rejectsApprovedProfilesWithDiagnostics() {
    assertThatThrownBy(
            () ->
                loader.load(
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "fr",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [{
                        "termId": "item.warning",
                        "source": "warning",
                        "status": "APPROVED",
                        "morphology": {"partOfSpeech": "noun"},
                        "forms": {"bare.singular": "warning"},
                        "diagnostics": [{"code": "ambiguous", "message": "Needs review"}]
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Approved inflection profile cannot include diagnostics");
  }

  @Test
  public void loadsStructuredMissingFormCellDiagnostic() {
    TermInflectionProfilePack pack =
        loader.load(
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "ar",
              "provenance": {"sourceLabels": [], "sources": []},
              "profiles": [{
                "termId": "ar.explicit.mother",
                "source": "أم",
                "status": "REVIEW_NEEDED",
                "morphology": {"partOfSpeech": "noun", "gender": "feminine"},
                "forms": {"construct.genitive.singular": "أُمِّ"},
                "diagnostics": [{
                  "termId": "ar.explicit.mother",
                  "reason": "missing-form-cell",
                  "formKey": "construct.genitive.dual"
                }]
              }]
            }
            """);

    TermInflectionProfilePackJsonLoader.Diagnostic diagnostic =
        pack.profiles().getFirst().diagnostics().getFirst();

    assertThat(diagnostic.code()).isEqualTo("missing-form-cell");
    assertThat(diagnostic.message()).isEqualTo("missing-form-cell: construct.genitive.dual");
    assertThat(diagnostic.reason()).isEqualTo("missing-form-cell");
    assertThat(diagnostic.formKey()).isEqualTo("construct.genitive.dual");
    assertThat(diagnostic.termId()).isEqualTo("ar.explicit.mother");
  }

  @Test
  public void rejectsMissingFormCellDiagnosticForExistingForm() {
    assertThatThrownBy(
            () ->
                loader.load(
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "ar",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [{
                        "termId": "ar.explicit.mother",
                        "source": "أم",
                        "status": "REVIEW_NEEDED",
                        "morphology": {"partOfSpeech": "noun"},
                        "forms": {"construct.genitive.singular": "أُمِّ"},
                        "diagnostics": [{
                          "reason": "missing-form-cell",
                          "formKey": "construct.genitive.singular"
                        }]
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "missing-form-cell diagnostic points to existing form: construct.genitive.singular");
  }

  @Test
  public void rejectsMissingFormCellDiagnosticForDifferentTerm() {
    assertThatThrownBy(
            () ->
                loader.load(
                    """
                    {
                      "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
                      "locale": "ar",
                      "provenance": {"sourceLabels": [], "sources": []},
                      "profiles": [{
                        "termId": "ar.explicit.mother",
                        "source": "أم",
                        "status": "REVIEW_NEEDED",
                        "morphology": {"partOfSpeech": "noun"},
                        "forms": {"construct.genitive.singular": "أُمِّ"},
                        "diagnostics": [{
                          "termId": "ar.explicit.other",
                          "reason": "missing-form-cell",
                          "formKey": "construct.genitive.dual"
                        }]
                      }]
                    }
                    """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Inflection profile diagnostic term mismatch: ar.explicit.other for ar.explicit.mother");
  }

  @Test
  public void rejectsCompilationOfProfilesThatStillNeedReview() {
    TermInflectionProfilePack pack =
        loader.load(
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "fr",
              "provenance": {"sourceLabels": [], "sources": []},
              "profiles": [{
                "termId": "item.warning",
                "source": "warning",
                "status": "REVIEW_NEEDED",
                "morphology": {"partOfSpeech": "noun"},
                "forms": {"bare.singular": "warning"},
                "diagnostics": [{"code": "ambiguous", "message": "Needs review"}]
              }]
            }
            """);

    assertThatThrownBy(pack::toCompiledTermPack)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Cannot compile inflection profile item.warning with status REVIEW_NEEDED");
  }

  @Test
  public void compilesProfilesAndFormsDeterministically() {
    CompiledTermPack first =
        loader.load(profilePackJson("item.b", "B", "item.a", "A")).toCompiledTermPack();
    CompiledTermPack second =
        loader.load(profilePackJson("item.a", "A", "item.b", "B")).toCompiledTermPack();

    assertThat(first).isEqualTo(second);
  }

  @Test
  public void loadsOptionalPerProfileProvenance() {
    TermInflectionProfilePack pack =
        loader.load(
            """
            {
              "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
              "locale": "fr",
              "provenance": {"sourceLabels": [], "sources": []},
              "profiles": [
                {
                  "termId": "item.manual",
                  "source": "manual",
                  "status": "APPROVED",
                  "morphology": {"partOfSpeech": "noun"},
                  "forms": {"bare.singular": "manuel"},
                  "diagnostics": [],
                  "provenance": {"source": "reviewed"}
                },
                {
                  "termId": "item.legacy",
                  "source": "legacy",
                  "status": "APPROVED",
                  "morphology": {"partOfSpeech": "noun"},
                  "forms": {"bare.singular": "heritage"},
                  "diagnostics": []
                }
              ]
            }
            """);

    assertThat(pack.profiles().get(0).provenance().get("source").asText()).isEqualTo("reviewed");
    assertThat(pack.profiles().get(1).provenance()).isEmpty();
  }

  @Test
  public void loadsSourceBackedPackProvenanceAndCompilesIt() {
    TermInflectionProfilePack pack =
        loader.load(
            singleProfilePackJson(
                """
                {
                  "license": "Unicode-3.0",
                  "generator": "dev-docs/experiments/mf2-inflection/fr_profile_prefill.py",
                  "sourceLabels": ["unicode-fr"],
                  "sources": [{
                    "path": "dictionary_fr.lst",
                    "byteSize": 123,
                    "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "gitLfsPointer": false
                  }]
                }
                """));

    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().generator())
        .isEqualTo("dev-docs/experiments/mf2-inflection/fr_profile_prefill.py");
    assertThat(pack.provenance().sourceLabels()).containsExactly("unicode-fr");
    assertThat(pack.provenance().sources().getFirst().path()).isEqualTo("dictionary_fr.lst");
    assertThat(pack.toCompiledTermPack().provenance()).isEqualTo(pack.provenance());
  }

  @Test
  public void rejectsSourceBackedPackProvenanceWithoutLicense() {
    assertThatThrownBy(
            () ->
                loader.load(
                    singleProfilePackJson(
                        """
                        {
                          "generator": "dev-docs/experiments/mf2-inflection/fr_profile_prefill.py",
                          "sourceLabels": ["unicode-fr"],
                          "sources": [{
                            "path": "dictionary_fr.lst",
                            "byteSize": 123,
                            "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            "gitLfsPointer": false
                          }]
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source-backed provenance requires license");
  }

  @Test
  public void rejectsSourceBackedPackProvenanceWithoutGenerator() {
    assertThatThrownBy(
            () ->
                loader.load(
                    singleProfilePackJson(
                        """
                        {
                          "license": "Unicode-3.0",
                          "sourceLabels": ["unicode-fr"],
                          "sources": [{
                            "path": "dictionary_fr.lst",
                            "byteSize": 123,
                            "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            "gitLfsPointer": false
                          }]
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source-backed provenance requires generator");
  }

  @Test
  public void rejectsPackProvenanceSourceLabelCountMismatch() {
    assertThatThrownBy(
            () ->
                loader.load(
                    singleProfilePackJson(
                        """
                        {
                          "license": "Unicode-3.0",
                          "generator": "dev-docs/experiments/mf2-inflection/fr_profile_prefill.py",
                          "sourceLabels": [],
                          "sources": [{
                            "path": "dictionary_fr.lst",
                            "byteSize": 123,
                            "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            "gitLfsPointer": false
                          }]
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Provenance source label count does not match sources");
  }

  @Test
  public void rejectsNonIntegralSourceByteSize() {
    assertThatThrownBy(
            () ->
                loader.load(
                    singleProfilePackJson(
                        """
                        {
                          "license": "Unicode-3.0",
                          "generator": "dev-docs/experiments/mf2-inflection/fr_profile_prefill.py",
                          "sourceLabels": ["unicode-fr"],
                          "sources": [{
                            "path": "dictionary_fr.lst",
                            "byteSize": 123.5,
                            "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            "gitLfsPointer": false
                          }]
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectsNegativeSourceByteSize() {
    assertThatThrownBy(
            () ->
                loader.load(
                    singleProfilePackJson(
                        """
                        {
                          "license": "Unicode-3.0",
                          "generator": "dev-docs/experiments/mf2-inflection/fr_profile_prefill.py",
                          "sourceLabels": ["unicode-fr"],
                          "sources": [{
                            "path": "dictionary_fr.lst",
                            "byteSize": -1,
                            "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            "gitLfsPointer": false
                          }]
                        }
                        """)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("byteSize must be non-negative");
  }

  private TermInflectionProfilePack fixturePack() {
    return loader.load(
        readResource("com/box/l10n/mojito/mf2/inflection/fr_inflection_profile_pack_fixture.json"));
  }

  private String profilePackJson(
      String firstTermId, String firstSource, String secondTermId, String secondSource) {
    return """
        {
          "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
          "locale": "fr",
          "provenance": {"sourceLabels": [], "sources": []},
          "profiles": [
            {
              "termId": "%s",
              "source": "%s",
              "status": "APPROVED",
              "morphology": {"partOfSpeech": "noun"},
              "forms": {"bare.singular": "%s", "bare.plural": "%ss"},
              "diagnostics": []
            },
            {
              "termId": "%s",
              "source": "%s",
              "status": "APPROVED",
              "morphology": {"partOfSpeech": "noun"},
              "forms": {"bare.plural": "%ss", "bare.singular": "%s"},
              "diagnostics": []
            }
          ]
        }
        """
        .formatted(
            firstTermId,
            firstSource,
            firstSource,
            firstSource,
            secondTermId,
            secondSource,
            secondSource,
            secondSource);
  }

  private String singleProfilePackJson(String provenance) {
    return """
        {
          "schema": "mojito-mf2-inflection/term-inflection-profile-pack/v0",
          "locale": "fr",
          "provenance": %s,
          "profiles": [{
            "termId": "item.iron_sword",
            "source": "epee de fer",
            "status": "APPROVED",
            "morphology": {"partOfSpeech": "noun"},
            "forms": {"bare.singular": "epee de fer"},
            "diagnostics": []
          }]
        }
        """
        .formatted(provenance);
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
