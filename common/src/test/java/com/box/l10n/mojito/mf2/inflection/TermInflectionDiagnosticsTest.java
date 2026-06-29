package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;

public class TermInflectionDiagnosticsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void extractsSortedMissingFormKeysFromCodeAndReasonDiagnostics() {
    JsonNode diagnostics =
        objectMapper.readTreeUnchecked(
            """
            [
              {
                "code": "missing-form-cell",
                "formKey": "construct.genitive.dual"
              },
              {
                "reason": "missing-form-cell",
                "formKey": "construct.accusative.dual"
              },
              {
                "code": "ambiguous",
                "formKey": "ignored.form"
              },
              {
                "reason": "missing-form-cell",
                "formKey": "construct.genitive.dual"
              }
            ]
            """);

    assertThat(TermInflectionDiagnostics.missingFormKeys(diagnostics))
        .containsExactly("construct.accusative.dual", "construct.genitive.dual");
  }

  @Test
  public void ignoresMissingFormDiagnosticsWithoutTextualFormKey() {
    JsonNode diagnostics =
        objectMapper.readTreeUnchecked(
            """
            [
              {"reason": "missing-form-cell"},
              {"reason": "missing-form-cell", "formKey": ""},
              {"reason": "missing-form-cell", "formKey": 12}
            ]
            """);

    assertThat(TermInflectionDiagnostics.missingFormKeys(diagnostics)).isEmpty();
  }

  @Test
  public void ignoresNonArrayDiagnosticsForSummaryUse() {
    JsonNode diagnostics = objectMapper.readTreeUnchecked("{\"reason\":\"missing-form-cell\"}");

    assertThat(TermInflectionDiagnostics.missingFormKeys(diagnostics)).isEmpty();
    assertThat(TermInflectionDiagnostics.missingFormKeys(null)).isEmpty();
  }

  @Test
  public void summarizesStructuredDiagnosticsForReviewAndAuthoringSurfaces() {
    JsonNode diagnostics =
        objectMapper.readTreeUnchecked(
            """
            [
              {
                "messageId": "checkout.pay",
                "argument": "owner",
                "relatedArgument": "item",
                "termId": "inventory.owner",
                "missing": ["agreeWith.gender", "agreeWith.count"],
                "span": [0, 91]
              },
              {
                "reason": "missing-form-cell",
                "message": "Missing construct.genitive.dual",
                "formKey": "construct.genitive.dual",
                "termId": "he.reviewed.hand"
              }
            ]
            """);

    assertThat(TermInflectionDiagnostics.diagnosticSummaries(diagnostics)).hasSize(2);
    TermInflectionDiagnostics.DiagnosticSummary relatedArgument =
        TermInflectionDiagnostics.diagnosticSummaries(diagnostics).getFirst();
    assertThat(relatedArgument.messageId()).isEqualTo("checkout.pay");
    assertThat(relatedArgument.argument()).isEqualTo("owner");
    assertThat(relatedArgument.relatedArgument()).isEqualTo("item");
    assertThat(relatedArgument.missing()).containsExactly("agreeWith.gender", "agreeWith.count");
    assertThat(relatedArgument.span()).containsExactly(0, 91);

    TermInflectionDiagnostics.DiagnosticSummary missingForm =
        TermInflectionDiagnostics.diagnosticSummaries(diagnostics).get(1);
    assertThat(missingForm.reason()).isEqualTo("missing-form-cell");
    assertThat(missingForm.formKey()).isEqualTo("construct.genitive.dual");
    assertThat(missingForm.message()).isEqualTo("Missing construct.genitive.dual");
    assertThat(missingForm.termId()).isEqualTo("he.reviewed.hand");
  }
}
