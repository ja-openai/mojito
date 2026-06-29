package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class CompiledTermPackJsonLoaderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void rejectNullObjectMapper() {
    assertThatThrownBy(() -> new CompiledTermPackJsonLoader(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("objectMapper");
  }

  @Test
  public void rejectNullJson() {
    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load((String) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("json");
  }

  @Test
  public void rejectNullRoot() {
    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load((JsonNode) null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("root");
  }

  @Test
  public void rejectNonObjectRoot() {
    assertThatThrownBy(
            () -> new CompiledTermPackJsonLoader().load(objectMapper.readTreeUnchecked("[]")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object root: compiled term pack");
  }

  @Test
  public void loadGeneratedJsonShapeAndRender() {
    CompiledTermPack pack = new CompiledTermPackJsonLoader().load(compiledFrenchPackJson());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("fr");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(1000);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().stringPoolBytes()).isEqualTo(155);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().formRowBytes()).isEqualTo(96);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(291);
    assertThat(pack.terms()).hasSize(2);
    assertThat(pack.terms().get(0).id()).isEqualTo(8);
    assertThat(pack.terms().get(0).text()).isEqualTo(9);
    assertThat(pack.terms().get(0).sense()).isEqualTo(10);
    assertThat(pack.terms().get(0).formSet()).isZero();
    String message = "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.";
    assertThat(
            renderer.renderMessage(message, Map.of("item", "concept.book"), Map.of("count", "1")))
        .isEqualTo("Vous avez supprim\u00e9 le livre.");
    assertThat(renderer.renderMessage(message, Map.of("item", "unit.pound"), Map.of("count", "1")))
        .isEqualTo("Vous avez supprim\u00e9 la livre.");
    assertThat(
            renderer.renderMessage(
                "Le poids est de {$item :term count=$count}.",
                Map.of("item", "unit.pound"),
                Map.of("count", "2")))
        .isEqualTo("Le poids est de 2 livres.");
  }

  @Test
  public void loadGeneratedSerbianCompiledFixtureAndRenderCaseForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/sr_compiled_case_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("sr");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(15021);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(240);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(2899);
    assertThat(pack.terms()).hasSize(12);
    assertThat(pack.formSets()).hasSize(12);
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
    assertThat(
            renderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "sr.case.ma\u010dka"),
                Map.of("count", "2")))
        .isEqualTo("Obrisano je ma\u010dke.");
  }

  @Test
  public void loadGeneratedHindiCompiledFixtureAndRenderCaseForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/hi_compiled_case_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("hi");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(3888);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(626);
    assertThat(pack.terms()).hasSize(3);
    assertThat(pack.formSets()).hasSize(3);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(18);
    assertThat(
            renderer.renderMessage(
                "\u0939\u091f\u093e \u0926\u093f\u092f\u093e {$item :term case=direct count=$count}.",
                Map.of("item", "hi.case.\u0905\u0902\u0917\u093e\u0930\u093e"),
                Map.of("count", "2")))
        .isEqualTo(
            "\u0939\u091f\u093e \u0926\u093f\u092f\u093e \u0905\u0902\u0917\u093e\u0930\u0947.");
    assertThat(
            renderer.renderMessage(
                "\u092e\u0947\u0902 {$item :term case=oblique count=$count}.",
                Map.of("item", "hi.case.\u0906\u0901\u0916"),
                Map.of("count", "2")))
        .isEqualTo("\u092e\u0947\u0902 \u0906\u0901\u0916\u094b\u0902.");
    assertThat(
            renderer.renderMessage(
                "\u0939\u0947 {$item :term case=vocative count=$count}.",
                Map.of("item", "hi.case.\u0906\u0926\u092e\u0940"),
                Map.of("count", "2")))
        .isEqualTo("\u0939\u0947 \u0906\u0926\u092e\u093f\u092f\u094b.");
  }

  @Test
  public void loadGeneratedArabicExplicitFixtureAndRenderForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader().load(arabicExplicitFormPackFixtureJson());
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("ar");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(5933);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(841);
    assertThat(pack.exportPolicy().runtimeExport()).isEqualTo("closed-world-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().automaticExportTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isEqualTo(1);
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredReasons()).containsEntry("missing-form-cell", 1);
    assertThat(pack.terms()).hasSize(1);
    assertThat(pack.formSets()).hasSize(1);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(14);
    assertThat(
            renderer.renderMessage(
                "حُذفت {$item :term definiteness=indefinite case=nominative}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("حُذفت أُمٌّ.");
    assertThat(
            renderer.renderMessage(
                "حُذفت {$item :term definiteness=indefinite case=nominative number=dual}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("حُذفت أُمَّانِ.");
    assertThat(
            renderer.renderMessage(
                "مع {$item :term definiteness=construct case=genitive}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("مع أُمِّ.");
    assertThat(
            renderer.renderMessage(
                "اختيرت {$item :term definiteness=construct case=nominative number=dual}.",
                Map.of("item", "ar.explicit.mother"),
                Map.of()))
        .isEqualTo("اختيرت أُمَّا.");
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "مع {$item :term definiteness=construct case=genitive number=dual}.",
                    Map.of("item", "ar.explicit.mother"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form construct.genitive.dual for term ar.explicit.mother");
  }

  @Test
  public void loadGeneratedApprovedArabicExplicitFixtureAndRenderAllRequiredForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ar_compiled_approved_explicit_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("ar");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(5819);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(850);
    assertThat(pack.exportPolicy().runtimeExport()).isEqualTo("closed-world-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().automaticExportTerms()).isEqualTo(1);
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredReasons()).isEmpty();
    assertThat(pack.terms()).hasSize(1);
    assertThat(pack.formSets()).hasSize(1);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(18);
    assertThat(
            renderer.renderMessage(
                "اختيرت {$item :term definiteness=construct case=genitive number=dual}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("اختيرت رسالتي.");
    assertThat(
            renderer.renderMessage(
                "حُذفت {$item :term definiteness=indefinite case=genitive number=plural}.",
                Map.of("item", "ar.explicit.message"),
                Map.of()))
        .isEqualTo("حُذفت رسائل.");
  }

  @Test
  public void rejectsGeneratedExplicitSummaryWithIncoherentMissingFormCount() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replace("\"requiredFormRows\": 18", "\"requiredFormRows\": 17");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled generation summary missing form count mismatch");
  }

  @Test
  public void rejectsGeneratedExplicitSummaryWithUnsupportedReviewReason() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replaceFirst("\"reason\": \"missing-form-cell\"", "\"reason\": \"needs-review\"");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Unsupported compiled generation summary review reason: needs-review");
  }

  @Test
  public void rejectsGeneratedExplicitSummaryWithUnknownDiagnosticTerm() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replaceFirst(
                "\"termId\": \"ar.explicit.mother\"", "\"termId\": \"ar.explicit.unknown\"");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Unknown compiled generation summary diagnostic term: ar.explicit.unknown");
  }

  @Test
  public void rejectsGeneratedExplicitSummaryWhenExportedKeysDriftFromFormRows() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replaceFirst("\"indefinite.nominative.singular\"", "\"indefinite.nominative.fake\"");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Compiled generation summary exported form keys do not match compiled form rows");
  }

  @Test
  public void rejectsGeneratedSummaryWithIncoherentExportPolicy() {
    String json =
        swedishGenitiveDefinitenessPackFixtureJson()
            .replace("\"automaticExportTerms\": 2", "\"automaticExportTerms\": 1");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled export policy exported terms mismatch");
  }

  @Test
  public void rejectsGeneratedSummaryWithIncoherentReviewReasonCounts() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replace("\"missing-form-cell\": 1", "\"missing-form-cell\": 0");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Compiled export policy reason count must be positive: missing-form-cell");
  }

  @Test
  public void rejectsGeneratedSummaryWhenReviewReasonDriftsFromDiagnostics() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replace("\"missing-form-cell\": 1", "\"needs-review\": 1");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Compiled export policy missing-form-cell review reason count does not match review diagnostics");
  }

  @Test
  public void rejectsGeneratedExplicitSummaryWithoutPolicy() {
    String json =
        arabicExplicitFormPackFixtureJson()
            .replace("\"policy\": \"closed-world explicit-form pack\",\n", "");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected nonblank text field: policy");
  }

  @Test
  public void loadPerTermGenerationSummaryWithSameMissingFormKeyAcrossTerms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader().load(perTermSharedMissingFormSummaryJson());

    assertThat(pack.terms()).hasSize(2);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(2);
    assertThat(pack.exportPolicy().reviewRequiredReasons()).containsEntry("missing-form-cell", 2);
  }

  @Test
  public void rejectsPerTermGenerationSummaryWhenReviewReasonDriftsFromDiagnostics() {
    String json =
        perTermSharedMissingFormSummaryJson()
            .replace("\"missing-form-cell\": 2", "\"needs-review\": 2");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Compiled export policy missing-form-cell review reason count does not match review diagnostics");
  }

  @Test
  public void rejectsPerTermGenerationSummaryWhenExportedKeysDriftFromFormRows() {
    String json =
        perTermSharedMissingFormSummaryJson().replaceFirst("\"bare.singular\"", "\"bare.fake\"");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Compiled generation summary per-term exported form keys do not match compiled form rows");
  }

  @Test
  public void loadGeneratedHebrewConstructFixtureAndRenderForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/he_compiled_construct_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("he");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(2766);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(186);
    assertThat(pack.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-construct-state-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().automaticExportTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isEqualTo(1);
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredReasons()).containsEntry("missing-form-cell", 1);
    assertThat(pack.terms()).hasSize(1);
    assertThat(pack.formSets()).hasSize(1);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(4);
    assertThat(
            renderer.renderMessage(
                "נמחק {$item :term}.", Map.of("item", "he.construct.house"), Map.of()))
        .isEqualTo("נמחק בית.");
    assertThat(
            renderer.renderMessage(
                "נמחקו {$item :term number=plural}.",
                Map.of("item", "he.construct.house"),
                Map.of()))
        .isEqualTo("נמחקו בתים.");
    assertThat(
            renderer.renderMessage(
                "נבחר {$item :term definiteness=construct}.",
                Map.of("item", "he.construct.house"),
                Map.of()))
        .isEqualTo("נבחר בית.");
    assertThat(
            renderer.renderMessage(
                "נבחרו {$item :term definiteness=construct number=plural}.",
                Map.of("item", "he.construct.house"),
                Map.of()))
        .isEqualTo("נבחרו בתי.");
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "נבחרו {$item :term definiteness=construct number=dual}.",
                    Map.of("item", "he.construct.house"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form construct.dual for term he.construct.house");
  }

  @Test
  public void loadGeneratedMalayalamCaseFixtureAndRenderForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ml_compiled_case_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("ml");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(5377);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(903);
    assertThat(pack.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-multi-case-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().automaticExportTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isEqualTo(1);
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredReasons()).containsEntry("missing-form-cell", 1);
    assertThat(pack.terms()).hasSize(1);
    assertThat(pack.formSets()).hasSize(1);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(14);
    assertThat(
            renderer.renderMessage(
                "നീക്കംചെയ്തത് {$item :term case=nominative}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("നീക്കംചെയ്തത് ശിഷ്യൻ.");
    assertThat(
            renderer.renderMessage(
                "തിരഞ്ഞെടുത്തത് {$item :term case=accusative}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("തിരഞ്ഞെടുത്തത് ശിഷ്യനെ.");
    assertThat(
            renderer.renderMessage(
                "അയച്ചു {$item :term case=dative number=plural}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("അയച്ചു ശിഷ്യന്മാർക്ക്.");
    assertThat(
            renderer.renderMessage(
                "കൂടെ {$item :term case=sociative number=plural}.",
                Map.of("item", "ml.case.disciple"),
                Map.of()))
        .isEqualTo("കൂടെ ശിഷ്യന്മാരോട്.");
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "വിളിച്ചു {$item :term case=vocative}.",
                    Map.of("item", "ml.case.disciple"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form vocative.singular for term ml.case.disciple");
  }

  @Test
  public void loadGeneratedApprovedMalayalamCaseFixtureAndRenderAllRequiredForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ml_compiled_approved_case_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("ml");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(5483);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(1032);
    assertThat(pack.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-multi-case-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().automaticExportTerms()).isEqualTo(1);
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.exportPolicy().reviewRequiredReasons()).isEmpty();
    assertThat(pack.terms()).hasSize(1);
    assertThat(pack.formSets()).hasSize(1);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(16);
    assertThat(
            renderer.renderMessage(
                "വിളിച്ചത് {$item :term case=vocative}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("വിളിച്ചത് പിതാവേ.");
    assertThat(
            renderer.renderMessage(
                "കൂടെ {$item :term case=sociative number=plural}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("കൂടെ പിതാക്കന്മാരോട്.");
    assertThat(
            renderer.renderMessage(
                "തിരഞ്ഞെടുത്തത് {$item :term case=dative number=plural}.",
                Map.of("item", "ml.case.father"),
                Map.of()))
        .isEqualTo("തിരഞ്ഞെടുത്തത് പിതാവുകൾക്കു്.");
  }

  @Test
  public void loadGeneratedSwedishGenitiveDefinitenessFixtureAndRenderForms() {
    String json = swedishGenitiveDefinitenessPackFixtureJson();
    assertThat(json).contains("\"compositionMode\": \"explicit-form-rows-v0\"");
    assertThat(json).contains("\"definiteness-suffix\"");
    CompiledTermPack pack = new CompiledTermPackJsonLoader().load(json);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);
    CompiledTermMetadataIndex metadataIndex = new CompiledTermMetadataIndex(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("sv");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(6438);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(760);
    assertThat(pack.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-genitive-definiteness-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().deferredComposition())
        .containsExactly("article-selection", "definiteness-suffix", "genitive-suffix");
    assertThat(pack.exportPolicy().automaticExportTerms()).isEqualTo(2);
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.terms()).hasSize(2);
    assertThat(pack.formSets()).hasSize(2);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(20);
    assertThat(metadataIndex.metadata("sv.definiteness.bostad").gender()).isEqualTo("common");
    assertThat(metadataIndex.metadata("sv.definiteness.chassi").gender()).isEqualTo("neuter");
    assertThat(
            renderer.renderMessage(
                "Vald {$item :term}.", Map.of("item", "sv.definiteness.bostad"), Map.of()))
        .isEqualTo("Vald bostad.");
    assertThat(
            renderer.renderMessage(
                "Valda {$item :term number=plural}.",
                Map.of("item", "sv.definiteness.bostad"),
                Map.of()))
        .isEqualTo("Valda bost\u00e4der.");
    assertThat(
            renderer.renderMessage(
                "Raderade {$item :term definiteness=definite case=nominative}.",
                Map.of("item", "sv.definiteness.bostad"),
                Map.of()))
        .isEqualTo("Raderade bostaden.");
    assertThat(
            renderer.renderMessage(
                "\u00c4gare: {$item :term definiteness=definite case=genitive number=plural}.",
                Map.of("item", "sv.definiteness.bostad"),
                Map.of()))
        .isEqualTo("\u00c4gare: bost\u00e4dernas.");
    assertThat(
            renderer.renderMessage(
                "Valt {$item :term definiteness=definite case=nominative}.",
                Map.of("item", "sv.definiteness.chassi"),
                Map.of()))
        .isEqualTo("Valt chassit.");
    assertThat(
            renderer.renderMessage(
                "ID: {$item :term definiteness=indefinite case=genitive number=plural}.",
                Map.of("item", "sv.definiteness.chassi"),
                Map.of()))
        .isEqualTo("ID: chassiers.");
  }

  @Test
  public void loadGeneratedDanishGenitiveDefinitenessFixtureAndRenderForms() {
    String json = danishGenitiveDefinitenessPackFixtureJson();
    assertThat(json).contains("\"compositionMode\": \"explicit-form-rows-v0\"");
    assertThat(json).contains("\"genitive-suffix\"");
    CompiledTermPack pack = new CompiledTermPackJsonLoader().load(json);
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);
    CompiledTermMetadataIndex metadataIndex = new CompiledTermMetadataIndex(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("da");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(1);
    assertThat(pack.provenance().sources()).hasSize(1);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(6526);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(821);
    assertThat(pack.exportPolicy().runtimeExport())
        .isEqualTo("closed-world-genitive-definiteness-explicit-forms");
    assertThat(pack.exportPolicy().compositionMode()).isEqualTo("explicit-form-rows-v0");
    assertThat(pack.exportPolicy().deferredComposition())
        .containsExactly("article-selection", "definiteness-suffix", "genitive-suffix");
    assertThat(pack.exportPolicy().automaticExportTerms()).isEqualTo(2);
    assertThat(pack.exportPolicy().reviewRequiredTerms()).isZero();
    assertThat(pack.exportPolicy().blockedTerms()).isZero();
    assertThat(pack.terms()).hasSize(2);
    assertThat(pack.formSets()).hasSize(2);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(20);
    assertThat(metadataIndex.metadata("da.definiteness.franskmand").gender()).isEqualTo("common");
    assertThat(metadataIndex.metadata("da.definiteness.barnebarn").gender()).isEqualTo("neuter");
    assertThat(
            renderer.renderMessage(
                "Valgt {$item :term}.", Map.of("item", "da.definiteness.franskmand"), Map.of()))
        .isEqualTo("Valgt franskmand.");
    assertThat(
            renderer.renderMessage(
                "Valgte {$item :term number=plural}.",
                Map.of("item", "da.definiteness.franskmand"),
                Map.of()))
        .isEqualTo("Valgte franskm\u00e6nd.");
    assertThat(
            renderer.renderMessage(
                "Slettede {$item :term definiteness=definite case=nominative}.",
                Map.of("item", "da.definiteness.franskmand"),
                Map.of()))
        .isEqualTo("Slettede franskmanden.");
    assertThat(
            renderer.renderMessage(
                "Ejer: {$item :term definiteness=definite case=genitive number=plural}.",
                Map.of("item", "da.definiteness.franskmand"),
                Map.of()))
        .isEqualTo("Ejer: franskm\u00e6ndenes.");
    assertThat(
            renderer.renderMessage(
                "Valgt {$item :term definiteness=definite case=nominative}.",
                Map.of("item", "da.definiteness.barnebarn"),
                Map.of()))
        .isEqualTo("Valgt barnebarnet.");
    assertThat(
            renderer.renderMessage(
                "ID: {$item :term definiteness=indefinite case=genitive number=plural}.",
                Map.of("item", "da.definiteness.barnebarn"),
                Map.of()))
        .isEqualTo("ID: b\u00f8rneb\u00f8rns.");
  }

  @Test
  public void loadGeneratedGermanClosedWorldFixtureAndRenderArticleCaseForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/de_compiled_article_case_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("de");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(2826);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(935);
    assertThat(pack.terms()).hasSize(3);
    assertThat(pack.formSets()).hasSize(3);
    assertThat(
            renderer.renderMessage(
                "Gel\u00f6scht: {$item :term article=definite case=accusative count=$count}.",
                Map.of("item", "de.article_case.katze"),
                Map.of("count", "1")))
        .isEqualTo("Gel\u00f6scht: die Katze.");
    assertThat(
            renderer.renderMessage(
                "Gel\u00f6scht: {$item :term article=definite case=accusative count=$count}.",
                Map.of("item", "de.article_case.katze"),
                Map.of("count", "2")))
        .isEqualTo("Gel\u00f6scht: die Katzen.");
    assertThat(
            renderer.renderMessage(
                "Mit {$item :term article=definite case=dative count=$count}.",
                Map.of("item", "de.article_case.maedchen"),
                Map.of("count", "2")))
        .isEqualTo("Mit den M\u00e4dchen.");
    assertThat(
            renderer.renderMessage(
                "Erstellt: {$item :term article=indefinite case=nominative count=$count}.",
                Map.of("item", "de.article_case.1_euro_job"),
                Map.of("count", "1")))
        .isEqualTo("Erstellt: ein 1-Euro-Job.");
    assertThat(
            renderer.renderMessage(
                "Erstellt: {$item :term article=indefinite case=nominative count=$count}.",
                Map.of("item", "de.article_case.maedchen"),
                Map.of("count", "2")))
        .isEqualTo("Erstellt: M\u00e4dchen.");
  }

  @Test
  public void loadGeneratedSpanishCompactFixtureAndRenderComposedArticles() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/es_compiled_article_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("es");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(1403);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(245);
    assertThat(pack.terms()).hasSize(3);
    assertThat(pack.formSets()).hasSize(3);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(6);
    assertThat(pack.terms().stream().filter(term -> (term.featureBits() & (1 << 14)) != 0))
        .hasSize(1);
    assertThat(
            renderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el agua.");
    assertThat(
            renderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "2")))
        .isEqualTo("Has eliminado las aguas.");
    assertThat(
            renderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado la abeja.");
    assertThat(
            renderer.renderMessage(
                "Has encontrado {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.poppy"),
                Map.of("count", "2")))
        .isEqualTo("Has encontrado unos ababoles.");
  }

  @Test
  public void loadGeneratedItalianCompactFixtureAndRenderComposedArticles() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/it_compiled_article_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("it");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(1622);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(308);
    assertThat(pack.terms()).hasSize(4);
    assertThat(pack.formSets()).hasSize(4);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(8);
    assertThat(
            renderer.renderMessage(
                "Hai eliminato {$item :term article=definite count=$count}.",
                Map.of("item", "item.gnome"),
                Map.of("count", "1")))
        .isEqualTo("Hai eliminato lo gnomo.");
    assertThat(
            renderer.renderMessage(
                "Hai eliminato {$item :term article=definite count=$count}.",
                Map.of("item", "item.gnome"),
                Map.of("count", "2")))
        .isEqualTo("Hai eliminato gli gnomi.");
    assertThat(
            renderer.renderMessage(
                "Hai eliminato {$item :term article=definite count=$count}.",
                Map.of("item", "item.book"),
                Map.of("count", "1")))
        .isEqualTo("Hai eliminato il libro.");
    assertThat(
            renderer.renderMessage(
                "Hai trovato {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "1")))
        .isEqualTo("Hai trovato un'acqua.");
    assertThat(
            renderer.renderMessage(
                "Hai trovato {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "1")))
        .isEqualTo("Hai trovato un'ape.");
  }

  @Test
  public void loadGeneratedPortugueseCompactFixtureAndRenderComposedAgreement() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/pt_compiled_agreement_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("pt");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(1395);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(172);
    assertThat(pack.terms()).hasSize(2);
    assertThat(pack.formSets()).hasSize(2);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(4);
    assertThat(
            renderer.renderMessage(
                "Removido {$item :term article=definite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "1")))
        .isEqualTo("Removido o campo.");
    assertThat(
            renderer.renderMessage(
                "Removido {$item :term article=definite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "2")))
        .isEqualTo("Removido os campos.");
    assertThat(
            renderer.renderMessage(
                "Encontrado {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Encontrado umas casas.");
    assertThat(
            renderer.renderMessage(
                "Removido {$item :term preposition=de article=definite count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Removido das casas.");
    assertThat(
            renderer.renderMessage(
                "Dispon\u00edvel {$item :term preposition=em article=indefinite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "1")))
        .isEqualTo("Dispon\u00edvel num campo.");
    assertThat(
            renderer.renderMessage(
                "Filtrado {$item :term preposition=por article=definite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "2")))
        .isEqualTo("Filtrado pelos campos.");
  }

  @Test
  public void loadGeneratedTurkishCompactFixtureAndRenderSuffixForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_suffix_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("tr");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(3);
    assertThat(pack.provenance().sources()).hasSize(3);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(2050);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().termRowBytes()).isEqualTo(100);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(272);
    assertThat(pack.terms()).hasSize(5);
    assertThat(pack.formSets()).hasSize(5);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(5);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.car"),
                Map.of("count", "1")))
        .isEqualTo("Silindi arabay\u0131.");
    assertThat(
            renderer.renderMessage(
                "Bulundu {$item :term case=locative count=$count}.",
                Map.of("item", "item.park"),
                Map.of("count", "1")))
        .isEqualTo("Bulundu parkta.");
    assertThat(
            renderer.renderMessage(
                "Bulundu {$item :term case=locative count=$count}.",
                Map.of("item", "item.school"),
                Map.of("count", "2")))
        .isEqualTo("Bulundu okullarda.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "item.rose"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi g\u00fcller.");
  }

  @Test
  public void loadGeneratedTurkishExplicitTemplateFixtureAndRenderForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("tr");
    assertThat(pack.provenance().license())
        .isEqualTo("CC0-1.0 dictionary data; Unicode-3.0 repository packaging");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(4281);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(647);
    assertThat(pack.terms()).hasSize(4);
    assertThat(pack.formSets()).hasSize(4);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(18);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.\u00e7akmak"),
                Map.of()))
        .isEqualTo("Silindi \u00e7akma\u011f\u0131.");
    assertThat(
            renderer.renderMessage(
                "G\u00f6nderildi {$item :term case=dative}.",
                Map.of("item", "tr.explicit.g\u00f6k"),
                Map.of()))
        .isEqualTo("G\u00f6nderildi g\u00f6\u011fe.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.amel"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi a\u02bcmal.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.anahtar"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi anahtarlar.");
  }

  @Test
  public void loadGeneratedTurkishAutomaticExplicitTemplateFixtureAndRenderForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/tr_compiled_explicit_template_auto_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("tr");
    assertThat(pack.provenance().license())
        .isEqualTo("CC0-1.0 dictionary data; Unicode-3.0 repository packaging");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(6583);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(983);
    assertThat(pack.terms()).hasSize(8);
    assertThat(pack.formSets()).hasSize(8);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(31);
    assertThat(
            renderer.renderMessage(
                "Silindi {$item :term case=accusative}.",
                Map.of("item", "tr.explicit.baklava"),
                Map.of()))
        .isEqualTo("Silindi baklavay\u0131.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.bahar"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi bah\u00e2ran.");
    assertThat(
            renderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "tr.explicit.cetvel"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi ced\u00e2vil.");
  }

  @Test
  public void loadGeneratedRussianUnambiguousFixtureAndRenderCaseForms() {
    CompiledTermPack pack =
        new CompiledTermPackJsonLoader()
            .load(
                readResource(
                    "com/box/l10n/mojito/mf2/inflection/ru_compiled_case_form_pack_fixture.json"));
    CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(pack);

    assertThat(pack.schema()).isEqualTo(CompiledTermPack.SCHEMA);
    assertThat(pack.locale()).isEqualTo("ru");
    assertThat(pack.provenance().license()).isEqualTo("Unicode-3.0");
    assertThat(pack.provenance().sourceLabels()).hasSize(2);
    assertThat(pack.provenance().sources()).hasSize(2);
    assertThat(pack.sizeEstimates().compactJsonBytes()).isEqualTo(6074);
    assertThat(pack.sizeEstimates().binaryLowerBoundBytes().totalBytes()).isEqualTo(1284);
    assertThat(pack.terms()).hasSize(3);
    assertThat(pack.formSets()).hasSize(3);
    assertThat(pack.formSets().stream().flatMap(formSet -> formSet.forms().stream())).hasSize(36);
    assertThat(
            renderer.renderMessage(
                "\u0423\u0434\u0430\u043b\u0435\u043d\u043e {$item :term case=accusative count=$count}.",
                Map.of("item", "ru.case.\u043a\u043e\u0448\u043a\u0430"),
                Map.of("count", "1")))
        .isEqualTo("\u0423\u0434\u0430\u043b\u0435\u043d\u043e \u043a\u043e\u0448\u043a\u0443.");
    assertThat(
            renderer.renderMessage(
                "\u0423\u0434\u0430\u043b\u0435\u043d\u043e {$item :term case=accusative count=$count}.",
                Map.of("item", "ru.case.\u043a\u043e\u0448\u043a\u0430"),
                Map.of("count", "2")))
        .isEqualTo("\u0423\u0434\u0430\u043b\u0435\u043d\u043e \u043a\u043e\u0448\u0435\u043a.");
    assertThat(
            renderer.renderMessage(
                "\u041d\u0435\u0442 {$item :term case=genitive count=$count}.",
                Map.of("item", "ru.case.\u0440\u0435\u0441\u0442\u043e\u0440\u0430\u043d"),
                Map.of("count", "2")))
        .isEqualTo(
            "\u041d\u0435\u0442 \u0440\u0435\u0441\u0442\u043e\u0440\u0430\u043d\u043e\u0432.");
    assertThat(
            renderer.renderMessage(
                "\u0412 {$item :term case=prepositional count=$count}.",
                Map.of("item", "ru.case.\u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u043e"),
                Map.of("count", "1")))
        .isEqualTo("\u0412 \u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u0435.");
    assertThat(
            renderer.renderMessage(
                "\u0412 {$item :term case=prepositional count=$count}.",
                Map.of("item", "ru.case.\u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u043e"),
                Map.of("count", "2")))
        .isEqualTo("\u0412 \u0430\u0431\u0431\u0430\u0442\u0441\u0442\u0432\u0430\u0445.");
  }

  @Test
  public void rejectUnexpectedSchema() {
    String json =
        compiledFrenchPackJson()
            .replace(
                "mojito-mf2-inflection/compiled-term-pack/v0",
                "mojito-mf2-inflection/compiled-term-pack/v999");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected compiled term pack schema");
  }

  @Test
  public void rejectBinaryLowerBoundTotalMismatch() {
    String json = compiledFrenchPackJson().replace("\"totalBytes\": 291", "\"totalBytes\": 292");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Binary lower-bound total does not match parts");
  }

  @Test
  public void rejectBinaryLowerBoundStringPoolMismatch() {
    String json =
        compiledFrenchPackJson()
            .replace("\"stringPoolBytes\": 155", "\"stringPoolBytes\": 156")
            .replace("\"totalBytes\": 291", "\"totalBytes\": 292");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("string-pool byte count mismatch");
  }

  @Test
  public void rejectBinaryLowerBoundTermRowMismatch() {
    String json =
        compiledFrenchPackJson()
            .replace("\"termRowBytes\": 40", "\"termRowBytes\": 60")
            .replace("\"totalBytes\": 291", "\"totalBytes\": 311");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("term-row byte count mismatch");
  }

  @Test
  public void rejectBinaryLowerBoundFormRowMismatch() {
    String json =
        compiledFrenchPackJson()
            .replace("\"formRowBytes\": 96", "\"formRowBytes\": 108")
            .replace("\"totalBytes\": 291", "\"totalBytes\": 303");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("form-row byte count mismatch");
  }

  @Test
  public void rejectBinaryLowerBoundBindingReferenceMismatch() {
    String json =
        compiledFrenchPackJson()
            .replace("\"bindingReferenceBytes\": 0", "\"bindingReferenceBytes\": 12")
            .replace("\"totalBytes\": 291", "\"totalBytes\": 303");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("binding-reference byte count mismatch");
  }

  @Test
  public void rejectProvenanceLabelSourceMismatch() {
    String json =
        compiledFrenchPackJson()
            .replace(
                "\"sourceLabels\": [\"unicode-org/inflection dictionary_fr.lst\"]",
                "\"sourceLabels\": [\"unicode-org/inflection dictionary_fr.lst\", \"extra\"]");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Provenance source label count does not match sources");
  }

  @Test
  public void rejectSourceBackedProvenanceWithoutLicense() {
    String json = compiledFrenchPackJson().replace("    \"license\": \"Unicode-3.0\",\n", "");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source-backed provenance requires license");
  }

  @Test
  public void rejectSourceBackedProvenanceWithoutGenerator() {
    String json =
        compiledFrenchPackJson()
            .replace(
                "    \"generator\": \"dev-docs/experiments/mf2-inflection/mf2_term_pack_compile.py\",\n",
                "");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Source-backed provenance requires generator");
  }

  @Test
  public void rejectBlankProvenanceSourceLabel() {
    String json =
        compiledFrenchPackJson()
            .replace(
                "\"sourceLabels\": [\"unicode-org/inflection dictionary_fr.lst\"]",
                "\"sourceLabels\": [\" \"]");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected nonblank text array value: sourceLabels");
  }

  @Test
  public void rejectInvalidProvenanceSourceSha256() {
    String json =
        compiledFrenchPackJson()
            .replace(
                "\"sha256\": \"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"",
                "\"sha256\": \"abc123\"");

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256 must be a 64-character lowercase SHA-256 hex digest");
  }

  @Test
  public void rejectDuplicateProvenanceSourceLabels() {
    String json =
        """
        {
          "provenance": {
            "generator": "dev-docs/experiments/mf2-inflection/mf2_term_pack_compile.py",
            "license": "Unicode-3.0",
            "sourceLabels": ["unicode-fr", "unicode-fr"],
            "sources": [
              {
                "byteSize": 1234,
                "gitLfsPointer": false,
                "path": "dictionary_fr.lst",
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              },
              {
                "byteSize": 5678,
                "gitLfsPointer": false,
                "path": "inflectional_fr.xml",
                "sha256": "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
              }
            ]
          },
          "strings": [],
          "formSets": [],
          "terms": []
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate provenance source label");
  }

  @Test
  public void rejectNonIntegralIntFields() {
    String json =
        """
        {
          "strings": ["bare.singular", "livre", "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            }
          ],
          "terms": [
            {"featureBits": 1.5, "formSet": 0, "id": 2, "sense": null, "text": 1}
          ],
          "diagnostics": []
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected int field: featureBits");
  }

  @Test
  public void rejectNonIntegralLongFields() {
    String json =
        """
        {
          "provenance": {
            "generator": "dev-docs/experiments/mf2-inflection/mf2_term_pack_compile.py",
            "license": "Unicode-3.0",
            "sourceLabels": ["unicode-fr"],
            "sources": [
              {
                "byteSize": 1234.5,
                "gitLfsPointer": false,
                "path": "dictionary_fr.lst",
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          },
          "strings": [],
          "formSets": [],
          "terms": []
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected long field: byteSize");
  }

  @Test
  public void rejectUnknownFormKind() {
    String json =
        """
        {
          "strings": ["count.one", "1 livre", "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "dynamic"}]
            }
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown form row kind");
  }

  @Test
  public void rejectOutOfBoundsStringIndexes() {
    String json =
        """
        {
          "strings": ["count.one", "1 livre", "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 99, "kind": "literal"}]
            }
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String index out of bounds");
  }

  @Test
  public void rejectDuplicateTermFormSets() {
    String json =
        """
        {
          "strings": ["count.one", "1 livre", "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            },
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            }
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate form set");
  }

  @Test
  public void rejectDuplicateFormKeysInFormSet() {
    String json =
        """
        {
          "strings": ["count.one", "1 livre", "concept.book", "un livre"],
          "formSets": [
            {
              "term": 2,
              "forms": [
                {"key": 0, "value": 1, "kind": "literal"},
                {"key": 0, "value": 3, "kind": "literal"}
              ]
            }
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate form key");
  }

  @Test
  public void rejectOrphanFormSetWithoutTermRow() {
    String json =
        """
        {
          "strings": ["bare.singular", "livre", "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            }
          ],
          "terms": []
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled form set has no term row: concept.book");
  }

  @Test
  public void rejectNonTextStringPoolValues() {
    String json =
        """
        {
          "strings": ["count.one", 42, "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            }
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Expected text value in strings array");
  }

  @Test
  public void rejectBlankStringPoolValues() {
    String json =
        """
        {
          "strings": ["bare.singular", " ", "concept.book"],
          "formSets": [],
          "terms": []
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strings value is required");
  }

  @Test
  public void rejectEmptyFormSets() {
    String json =
        """
        {
          "strings": ["concept.book", "livre"],
          "formSets": [
            {
              "term": 0,
              "forms": []
            }
          ],
          "terms": [
            {"featureBits": 0, "formSet": 0, "id": 0, "sense": null, "text": 1}
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled form set requires forms: concept.book");
  }

  @Test
  public void rejectCompiledPackWithDiagnostics() {
    String json =
        """
        {
          "strings": ["count.one", "1 livre", "concept.book"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            }
          ],
          "diagnostics": [
            {"termId": "concept.book", "code": "missing-form", "form": "definite.singular"}
          ]
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled term pack contains diagnostics");
  }

  @Test
  public void rejectTermRowThatDoesNotMatchFormSetTerm() {
    String json =
        """
        {
          "strings": ["count.one", "1 livre", "concept.book", "unit.pound"],
          "formSets": [
            {
              "term": 2,
              "forms": [{"key": 0, "value": 1, "kind": "literal"}]
            }
          ],
          "terms": [
            {"featureBits": 8465, "formSet": 0, "id": 3, "sense": null, "text": 1}
          ],
          "diagnostics": []
        }
        """;

    assertThatThrownBy(() -> new CompiledTermPackJsonLoader().load(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Term row id does not match form set term");
  }

  private String compiledFrenchPackJson() {
    return """
        {
          "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
          "locale": "fr",
          "provenance": {
            "generator": "dev-docs/experiments/mf2-inflection/mf2_term_pack_compile.py",
            "license": "Unicode-3.0",
            "sourceLabels": ["unicode-org/inflection dictionary_fr.lst"],
            "sources": [
              {
                "byteSize": 1234,
                "gitLfsPointer": false,
                "path": "dictionary_fr.lst",
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          },
          "strings": [
            "count.one",
            "1 livre",
            "count.other",
            "{$count} livres",
            "definite.plural",
            "les livres",
            "definite.singular",
            "le livre",
            "concept.book",
            "livre",
            "book",
            "la livre",
            "unit.pound",
            "unit-pound"
          ],
          "terms": [
            {"featureBits": 8465, "formSet": 0, "id": 8, "sense": 10, "text": 9},
            {"featureBits": 8481, "formSet": 1, "id": 12, "sense": 13, "text": 9}
          ],
          "formSets": [
            {
              "term": 8,
              "forms": [
                {"key": 0, "kind": "literal", "value": 1},
                {"key": 2, "kind": "pattern", "value": 3},
                {"key": 4, "kind": "literal", "value": 5},
                {"key": 6, "kind": "literal", "value": 7}
              ]
            },
            {
              "term": 12,
              "forms": [
                {"key": 0, "kind": "literal", "value": 1},
                {"key": 2, "kind": "pattern", "value": 3},
                {"key": 4, "kind": "literal", "value": 5},
                {"key": 6, "kind": "literal", "value": 11}
              ]
            }
          ],
          "diagnostics": [],
          "sizeEstimates": {
            "binaryLowerBoundBytes": {
              "bindingReferenceBytes": 0,
              "formRowBytes": 96,
              "stringPoolBytes": 155,
              "termRowBytes": 40,
              "totalBytes": 291
            },
            "compactJsonBytes": 1000
          }
        }
        """;
  }

  private String arabicExplicitFormPackFixtureJson() {
    return readResource(
        "com/box/l10n/mojito/mf2/inflection/ar_compiled_explicit_form_pack_fixture.json");
  }

  private String swedishGenitiveDefinitenessPackFixtureJson() {
    return readResource(
        "com/box/l10n/mojito/mf2/inflection/sv_compiled_genitive_definiteness_pack_fixture.json");
  }

  private String danishGenitiveDefinitenessPackFixtureJson() {
    return readResource(
        "com/box/l10n/mojito/mf2/inflection/da_compiled_genitive_definiteness_pack_fixture.json");
  }

  private String perTermSharedMissingFormSummaryJson() {
    return """
        {
          "diagnostics": [],
          "formSets": [
            {
              "forms": [
                {"key": 2, "kind": "literal", "value": 1}
              ],
              "term": 0
            },
            {
              "forms": [
                {"key": 2, "kind": "literal", "value": 4}
              ],
              "term": 3
            }
          ],
          "generationSummary": {
            "candidateTerms": 2,
            "exportPolicy": {
              "automaticExportTerms": 0,
              "blockedReasons": {},
              "blockedTerms": 0,
              "compositionMode": "explicit-form-rows-v0",
              "deferredComposition": [],
              "reviewRequiredReasons": {
                "missing-form-cell": 2
              },
              "reviewRequiredTerms": 2,
              "runtimeExport": "closed-world-test-explicit-forms"
            },
            "exportedFormKeys": ["bare.singular"],
            "exportedTerms": 2,
            "formRows": 2,
            "missingFormKeys": ["bare.plural"],
            "policy": "closed-world test explicit-form pack",
            "requiredFormRowsPerTerm": 2,
            "reviewDiagnostics": [
              {
                "formKey": "bare.plural",
                "reason": "missing-form-cell",
                "termId": "term.a"
              },
              {
                "formKey": "bare.plural",
                "reason": "missing-form-cell",
                "termId": "term.b"
              }
            ],
            "terms": [
              {
                "sourceRows": [1],
                "sourceRowsByFormKey": {"bare.singular": 1},
                "termId": "term.a"
              },
              {
                "sourceRows": [2],
                "sourceRowsByFormKey": {"bare.singular": 2},
                "termId": "term.b"
              }
            ]
          },
          "locale": "x-test",
          "schema": "mojito-mf2-inflection/compiled-term-pack/v0",
          "strings": [
            "term.a",
            "alpha",
            "bare.singular",
            "term.b",
            "beta"
          ],
          "terms": [
            {"featureBits": 0, "formSet": 0, "id": 0, "text": 1},
            {"featureBits": 0, "formSet": 1, "id": 3, "text": 4}
          ]
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
}
