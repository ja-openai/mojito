package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormRow;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.FormSet;
import com.box.l10n.mojito.mf2.inflection.CompiledTermPack.TermRow;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CompiledTermPackRendererTest {

  private final CompiledTermPackRenderer renderer = new CompiledTermPackRenderer(frenchPack());

  @Test
  public void provenanceRejectsBlankSourceLabelsAtRecordBoundary() {
    assertThatThrownBy(
            () ->
                new CompiledTermPack.Provenance(
                    "Unicode-3.0",
                    "generator.py",
                    List.of(" "),
                    List.of(
                        new CompiledTermPack.Source(
                            "dictionary_fr.lst",
                            1234,
                            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                            false))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceLabels is required");
  }

  @Test
  public void sourceRejectsInvalidSha256AtRecordBoundary() {
    assertThatThrownBy(
            () -> new CompiledTermPack.Source("dictionary_fr.lst", 1234, "abc123", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sha256 must be a 64-character lowercase SHA-256 hex digest");
  }

  @Test
  public void compiledPackRejectsOrphanFormSetAtRecordBoundary() {
    assertThatThrownBy(
            () ->
                new CompiledTermPack(
                    List.of("bare.singular", "livre", "concept.book"),
                    List.of(),
                    List.of(new FormSet(2, List.of(new FormRow(0, 1, false))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled form set has no term row: concept.book");
  }

  @Test
  public void compiledPackRejectsBlankStringPoolValuesAtRecordBoundary() {
    assertThatThrownBy(() -> new CompiledTermPack(List.of(" "), List.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("strings value is required");
  }

  @Test
  public void compiledPackRejectsEmptyFormSetAtRecordBoundary() {
    assertThatThrownBy(
            () ->
                new CompiledTermPack(
                    List.of("concept.book", "livre"),
                    List.of(new TermRow(0, 1, 0, null, 0)),
                    List.of(new FormSet(0, List.of()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Compiled form set requires forms: concept.book");
  }

  @Test
  public void exportPolicyRejectsReasonCountsThatDoNotMatchTermCountsAtRecordBoundary() {
    assertThatThrownBy(
            () ->
                new CompiledTermPack.ExportPolicy(
                    "closed-world-explicit-forms",
                    "explicit-form-rows-v0",
                    List.of(),
                    0,
                    2,
                    0,
                    Map.of("missing-form-cell", 1),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reviewRequiredReasons must sum to the matching term count");

    assertThatThrownBy(
            () ->
                new CompiledTermPack.ExportPolicy(
                    "closed-world-explicit-forms",
                    "explicit-form-rows-v0",
                    List.of(),
                    0,
                    0,
                    2,
                    Map.of(),
                    Map.of("disabled-profile", 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blockedReasons must sum to the matching term count");
  }

  @Test
  public void exportPolicyRejectsZeroReasonCountsAtRecordBoundary() {
    assertThatThrownBy(
            () ->
                new CompiledTermPack.ExportPolicy(
                    "closed-world-explicit-forms",
                    "explicit-form-rows-v0",
                    List.of(),
                    0,
                    0,
                    0,
                    Map.of("missing-form-cell", 0),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reviewRequiredReasons value must be positive");
  }

  @Test
  public void renderDefiniteArticleWithElision() {
    assertThat(
            renderer.renderMessage(
                "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.",
                Map.of("item", "item.iron_sword"),
                Map.of("count", "1")))
        .isEqualTo("Vous avez supprim\u00e9 l'\u00e9p\u00e9e de fer.");
  }

  @Test
  public void renderPluralDefiniteArticleFromCount() {
    assertThat(
            renderer.renderMessage(
                "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.",
                Map.of("item", "item.iron_sword"),
                Map.of("count", "2")))
        .isEqualTo("Vous avez supprim\u00e9 les \u00e9p\u00e9es de fer.");
  }

  @Test
  public void renderIndefiniteArticleWithoutCountAsSingular() {
    assertThat(
            renderer.renderMessage(
                "Vous avez trouv\u00e9 {$item :term article=indefinite}.",
                Map.of("item", "item.iron_sword"),
                Map.of()))
        .isEqualTo("Vous avez trouv\u00e9 une \u00e9p\u00e9e de fer.");
  }

  @Test
  public void renderAmbiguousSurfaceFromTermId() {
    String message = "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.";

    assertThat(
            renderer.renderMessage(message, Map.of("item", "concept.book"), Map.of("count", "1")))
        .isEqualTo("Vous avez supprim\u00e9 le livre.");
    assertThat(renderer.renderMessage(message, Map.of("item", "unit.pound"), Map.of("count", "1")))
        .isEqualTo("Vous avez supprim\u00e9 la livre.");
  }

  @Test
  public void renderCountFormWithVariableSubstitution() {
    assertThat(
            renderer.renderMessage(
                "Le poids est de {$item :term count=$count}.",
                Map.of("item", "unit.pound"),
                Map.of("count", "2")))
        .isEqualTo("Le poids est de 2 livres.");
  }

  @Test
  public void renderSerbianCaseFormFromCount() {
    CompiledTermPackRenderer serbianRenderer = new CompiledTermPackRenderer(serbianPack());

    assertThat(
            serbianRenderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "animal.cat"),
                Map.of("count", "1")))
        .isEqualTo("Obrisano je ma\u010dku.");
    assertThat(
            serbianRenderer.renderMessage(
                "Obrisano je {$item :term case=accusative count=$count}.",
                Map.of("item", "animal.cat"),
                Map.of("count", "2")))
        .isEqualTo("Obrisano je ma\u010dke.");
  }

  @Test
  public void renderSerbianCaseFormDefaultsToSingularWithoutCount() {
    CompiledTermPackRenderer serbianRenderer = new CompiledTermPackRenderer(serbianPack());

    assertThat(
            serbianRenderer.renderMessage(
                "Daj {$item :term case=dative}.", Map.of("item", "animal.cat"), Map.of()))
        .isEqualTo("Daj ma\u010dki.");
  }

  @Test
  public void renderSpanishStressedFeminineArticleFromMetadata() {
    CompiledTermPackRenderer spanishRenderer = new CompiledTermPackRenderer(spanishPack());

    assertThat(
            spanishRenderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el agua.");
    assertThat(
            spanishRenderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.water"),
                Map.of("count", "2")))
        .isEqualTo("Has eliminado las aguas.");
    assertThat(
            spanishRenderer.renderMessage(
                "Has encontrado {$item :term article=indefinite}.",
                Map.of("item", "item.water"),
                Map.of()))
        .isEqualTo("Has encontrado un agua.");
  }

  @Test
  public void renderSpanishRegularFeminineArticleFromMetadata() {
    CompiledTermPackRenderer spanishRenderer = new CompiledTermPackRenderer(spanishPack());

    assertThat(
            spanishRenderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado la abeja.");
    assertThat(
            spanishRenderer.renderMessage(
                "Has encontrado {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.bee"),
                Map.of("count", "2")))
        .isEqualTo("Has encontrado unas abejas.");
  }

  @Test
  public void renderSpanishMasculineArticleFromMetadata() {
    CompiledTermPackRenderer spanishRenderer = new CompiledTermPackRenderer(spanishPack());

    assertThat(
            spanishRenderer.renderMessage(
                "Has eliminado {$item :term article=definite count=$count}.",
                Map.of("item", "item.poppy"),
                Map.of("count", "1")))
        .isEqualTo("Has eliminado el ababol.");
    assertThat(
            spanishRenderer.renderMessage(
                "Has encontrado {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.poppy"),
                Map.of("count", "2")))
        .isEqualTo("Has encontrado unos ababoles.");
  }

  @Test
  public void explicitSpanishArticleFormWinsOverMetadataComposition() {
    CompiledTermPackRenderer spanishRenderer = new CompiledTermPackRenderer(spanishPack());

    assertThat(
            spanishRenderer.renderMessage(
                "Has eliminado {$item :term article=definite}.",
                Map.of("item", "item.explicit"),
                Map.of()))
        .isEqualTo("Has eliminado forma explicita.");
  }

  @Test
  public void missingSpanishBareFormFailsWithSpecificDiagnostic() {
    CompiledTermPackRenderer spanishRenderer =
        new CompiledTermPackRenderer(spanishPackMissingBarePlural());

    assertThatThrownBy(
            () ->
                spanishRenderer.renderMessage(
                    "Has eliminado {$item :term article=definite count=$count}.",
                    Map.of("item", "item.water"),
                    Map.of("count", "2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing Spanish bare form bare.plural for term item.water");
  }

  @Test
  public void renderPortugueseArticleAndPrepositionAgreementFromMetadata() {
    CompiledTermPackRenderer portugueseRenderer = new CompiledTermPackRenderer(portuguesePack());

    assertThat(
            portugueseRenderer.renderMessage(
                "Removido {$item :term article=definite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "1")))
        .isEqualTo("Removido o campo.");
    assertThat(
            portugueseRenderer.renderMessage(
                "Encontrado {$item :term article=indefinite count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Encontrado umas casas.");
    assertThat(
            portugueseRenderer.renderMessage(
                "Removido {$item :term preposition=de article=definite count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Removido das casas.");
    assertThat(
            portugueseRenderer.renderMessage(
                "Dispon\u00edvel {$item :term preposition=em article=indefinite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "1")))
        .isEqualTo("Dispon\u00edvel num campo.");
    assertThat(
            portugueseRenderer.renderMessage(
                "Filtrado {$item :term preposition=por article=definite count=$count}.",
                Map.of("item", "item.field"),
                Map.of("count", "2")))
        .isEqualTo("Filtrado pelos campos.");
  }

  @Test
  public void explicitPortuguesePrepositionFormWinsOverMetadataComposition() {
    CompiledTermPackRenderer portugueseRenderer = new CompiledTermPackRenderer(portuguesePack());

    assertThat(
            portugueseRenderer.renderMessage(
                "Removido {$item :term preposition=de article=definite}.",
                Map.of("item", "item.explicit"),
                Map.of()))
        .isEqualTo("Removido forma explicita.");
  }

  @Test
  public void missingPortugueseBareFormFailsWithSpecificDiagnostic() {
    CompiledTermPackRenderer portugueseRenderer =
        new CompiledTermPackRenderer(portuguesePackMissingBarePlural());

    assertThatThrownBy(
            () ->
                portugueseRenderer.renderMessage(
                    "Removido {$item :term article=definite count=$count}.",
                    Map.of("item", "item.field"),
                    Map.of("count", "2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing Portuguese bare form bare.plural for term item.field");
  }

  @Test
  public void renderTurkishSuffixCasesFromMetadata() {
    CompiledTermPackRenderer turkishRenderer = new CompiledTermPackRenderer(turkishPack());

    assertThat(
            turkishRenderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "1")))
        .isEqualTo("Silindi evi.");
    assertThat(
            turkishRenderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.school"),
                Map.of("count", "1")))
        .isEqualTo("Silindi okulu.");
    assertThat(
            turkishRenderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.car"),
                Map.of("count", "1")))
        .isEqualTo("Silindi arabay\u0131.");
    assertThat(
            turkishRenderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.rose"),
                Map.of("count", "1")))
        .isEqualTo("Silindi g\u00fcl\u00fc.");
    assertThat(
            turkishRenderer.renderMessage(
                "G\u00f6nderildi {$item :term case=dative count=$count}.",
                Map.of("item", "item.car"),
                Map.of("count", "1")))
        .isEqualTo("G\u00f6nderildi arabaya.");
    assertThat(
            turkishRenderer.renderMessage(
                "Bulundu {$item :term case=locative count=$count}.",
                Map.of("item", "item.park"),
                Map.of("count", "1")))
        .isEqualTo("Bulundu parkta.");
    assertThat(
            turkishRenderer.renderMessage(
                "Al\u0131nd\u0131 {$item :term case=ablative count=$count}.",
                Map.of("item", "item.park"),
                Map.of("count", "1")))
        .isEqualTo("Al\u0131nd\u0131 parktan.");
    assertThat(
            turkishRenderer.renderMessage(
                "Silindi {$item :term case=accusative count=$count}.",
                Map.of("item", "item.house"),
                Map.of("count", "2")))
        .isEqualTo("Silindi evleri.");
    assertThat(
            turkishRenderer.renderMessage(
                "Bulundu {$item :term case=locative count=$count}.",
                Map.of("item", "item.school"),
                Map.of("count", "2")))
        .isEqualTo("Bulundu okullarda.");
    assertThat(
            turkishRenderer.renderMessage(
                "Listelendi {$item :term count=$count}.",
                Map.of("item", "item.rose"),
                Map.of("count", "2")))
        .isEqualTo("Listelendi g\u00fcller.");
  }

  @Test
  public void explicitTurkishCaseFormWinsOverMetadataComposition() {
    CompiledTermPackRenderer turkishRenderer = new CompiledTermPackRenderer(turkishPack());

    assertThat(
            turkishRenderer.renderMessage(
                "Bulundu {$item :term case=locative}.", Map.of("item", "item.explicit"), Map.of()))
        .isEqualTo("Bulundu \u00f6zelde.");
  }

  @Test
  public void missingTurkishSuffixMetadataFailsWithSpecificDiagnostic() {
    CompiledTermPackRenderer turkishRenderer =
        new CompiledTermPackRenderer(turkishPackMissingSuffixMetadata());

    assertThatThrownBy(
            () ->
                turkishRenderer.renderMessage(
                    "G\u00f6nderildi {$item :term case=dative}.",
                    Map.of("item", "item.house"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Turkish suffix composition requires turkishSuffix metadata for term item.house");
  }

  @Test
  public void missingTurkishBareFormFailsWithSpecificDiagnostic() {
    CompiledTermPackRenderer turkishRenderer =
        new CompiledTermPackRenderer(turkishPackMissingBareForm());

    assertThatThrownBy(
            () ->
                turkishRenderer.renderMessage(
                    "Silindi {$item :term case=accusative}.",
                    Map.of("item", "item.house"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing Turkish bare form bare.singular for term item.house");
  }

  @Test
  public void missingCompiledTermFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Vous avez supprim\u00e9 {$item :term article=definite}.",
                    Map.of("item", "missing.term"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing compiled term");
  }

  @Test
  public void missingTermArgumentNamesArgument() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Vous avez supprim\u00e9 {$item :term article=definite}.", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing term argument: item");
  }

  @Test
  public void missingCountVariableFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing count variable: count");
  }

  @Test
  public void renderFailureNamesArgumentBoundTermAndSourceSpan() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "X {$item :term count=$count}.", Map.of("item", "item.iron_sword"), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Failed to render term argument item bound to item.iron_sword at span 2-28: "
                + "Missing count variable: count")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void nonNumericCountVariableFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of("count", "many")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Count variable must be numeric");
  }

  @Test
  public void unsupportedArticleFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Vous avez supprim\u00e9 {$item :term article=partitive}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported article option: partitive");
  }

  @Test
  public void unsupportedTermOptionFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Vous avez supprim\u00e9 {$item :term role=source}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported term option: role");
  }

  @Test
  public void unsupportedCaseFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Obrisano je {$item :term case=ergative}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported case option: ergative");
  }

  @Test
  public void unsupportedDefinitenessFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Obrisano je {$item :term definiteness=absolute}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported definiteness option: absolute");
  }

  @Test
  public void unsupportedNumberFails() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Obrisano je {$item :term number=paucal}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported number option: paucal");
  }

  @Test
  public void numberAndCountCannotBeCombined() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Obrisano je {$item :term number=dual count=$count}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of("count", "2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Number option cannot be combined with count option");
  }

  @Test
  public void definitenessAndArticleCannotBeCombined() {
    assertThatThrownBy(
            () ->
                renderer.renderMessage(
                    "Obrisano je {$item :term definiteness=construct article=definite}.",
                    Map.of("item", "item.iron_sword"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Definiteness option cannot be combined with article option");
  }

  @Test
  public void missingCaseFormFails() {
    CompiledTermPackRenderer serbianRenderer = new CompiledTermPackRenderer(serbianPack());

    assertThatThrownBy(
            () ->
                serbianRenderer.renderMessage(
                    "Obrisano je {$item :term case=genitive}.",
                    Map.of("item", "animal.cat"),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing form genitive.singular for term animal.cat");
  }

  @Test
  public void renderabilityPreflightIncludesMessageIdInFailures() {
    CompiledTermPackRenderer serbianRenderer = new CompiledTermPackRenderer(serbianPack());

    assertThatThrownBy(
            () ->
                serbianRenderer.requireRenderableMessage(
                    "inventory.deleted",
                    "Obrisano je {$item :term case=genitive}.",
                    Map.of("item", "animal.cat")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Failed to render term argument item in message inventory.deleted bound to animal.cat at span")
        .hasMessageContaining("Missing form genitive.singular for term animal.cat");
  }

  @Test
  public void missingPatternVariableFails() {
    assertThatThrownBy(() -> renderer.renderMessage("Owner {$owner}", Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing pattern variable: owner");
  }

  private static CompiledTermPack frenchPack() {
    List<String> strings =
        List.of(
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
            "1 \u00e9p\u00e9e de fer",
            "{$count} \u00e9p\u00e9es de fer",
            "les \u00e9p\u00e9es de fer",
            "l'\u00e9p\u00e9e de fer",
            "indefinite.plural",
            "des \u00e9p\u00e9es de fer",
            "indefinite.singular",
            "une \u00e9p\u00e9e de fer",
            "item.iron_sword",
            "\u00e9p\u00e9e de fer",
            "la livre",
            "unit.pound",
            "unit-pound");

    return new CompiledTermPack(
        strings,
        List.of(
            new TermRow(8, 9, 8465, 10, 0),
            new TermRow(19, 20, 4385, null, 1),
            new TermRow(22, 9, 8481, 23, 2)),
        List.of(
            new FormSet(
                8,
                List.of(
                    new FormRow(0, 1, false),
                    new FormRow(2, 3, true),
                    new FormRow(4, 5, false),
                    new FormRow(6, 7, false))),
            new FormSet(
                19,
                List.of(
                    new FormRow(0, 11, false),
                    new FormRow(2, 12, true),
                    new FormRow(4, 13, false),
                    new FormRow(6, 14, false),
                    new FormRow(15, 16, false),
                    new FormRow(17, 18, false))),
            new FormSet(
                22,
                List.of(
                    new FormRow(0, 1, false),
                    new FormRow(2, 3, true),
                    new FormRow(4, 5, false),
                    new FormRow(6, 21, false)))));
  }

  private static CompiledTermPack serbianPack() {
    List<String> strings =
        List.of(
            "accusative.singular",
            "ma\u010dku",
            "accusative.plural",
            "ma\u010dke",
            "dative.singular",
            "ma\u010dki",
            "dative.plural",
            "ma\u010dkama",
            "count.one",
            "1 ma\u010dka",
            "count.other",
            "{$count} ma\u010dke",
            "animal.cat",
            "ma\u010dka",
            "cat");

    return new CompiledTermPack(
        strings,
        List.of(new TermRow(12, 13, 4385, 14, 0)),
        List.of(
            new FormSet(
                12,
                List.of(
                    new FormRow(0, 1, false),
                    new FormRow(2, 3, false),
                    new FormRow(4, 5, false),
                    new FormRow(6, 7, false),
                    new FormRow(8, 9, false),
                    new FormRow(10, 11, true)))));
  }

  private static CompiledTermPack spanishPack() {
    List<String> strings =
        List.of(
            "bare.singular",
            "agua",
            "bare.plural",
            "aguas",
            "item.water",
            "water",
            "abeja",
            "abejas",
            "item.bee",
            "bee",
            "ababol",
            "ababoles",
            "item.poppy",
            "poppy",
            "definite.singular",
            "forma explicita",
            "item.explicit",
            "explicit",
            "forma",
            "formas");

    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "es",
        strings,
        List.of(
            new TermRow(4, 1, 16673, 5, 0),
            new TermRow(8, 6, 289, 9, 1),
            new TermRow(12, 10, 273, 13, 2),
            new TermRow(16, 18, 289, 17, 3)),
        List.of(
            new FormSet(4, List.of(new FormRow(0, 1, false), new FormRow(2, 3, false))),
            new FormSet(8, List.of(new FormRow(0, 6, false), new FormRow(2, 7, false))),
            new FormSet(12, List.of(new FormRow(0, 10, false), new FormRow(2, 11, false))),
            new FormSet(
                16,
                List.of(
                    new FormRow(0, 18, false),
                    new FormRow(2, 19, false),
                    new FormRow(14, 15, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
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

  private static CompiledTermPack portuguesePack() {
    List<String> strings =
        List.of(
            "bare.singular",
            "campo",
            "bare.plural",
            "campos",
            "item.field",
            "field",
            "casa",
            "casas",
            "item.house",
            "house",
            "preposition.de.definite.singular",
            "forma explicita",
            "item.explicit",
            "explicit",
            "formas");

    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "pt",
        strings,
        List.of(
            new TermRow(4, 1, 273, 5, 0),
            new TermRow(8, 6, 289, 9, 1),
            new TermRow(12, 1, 273, 13, 2)),
        List.of(
            new FormSet(4, List.of(new FormRow(0, 1, false), new FormRow(2, 3, false))),
            new FormSet(8, List.of(new FormRow(0, 6, false), new FormRow(2, 7, false))),
            new FormSet(
                12,
                List.of(
                    new FormRow(0, 1, false),
                    new FormRow(2, 14, false),
                    new FormRow(10, 11, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
  }

  private static CompiledTermPack portuguesePackMissingBarePlural() {
    List<String> strings = List.of("bare.singular", "campo", "item.field", "field");

    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "pt",
        strings,
        List.of(new TermRow(2, 1, 273, 3, 0)),
        List.of(new FormSet(2, List.of(new FormRow(0, 1, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
  }

  private static CompiledTermPack turkishPack() {
    List<String> strings =
        List.of(
            "bare.singular",
            "ev",
            "item.house",
            "house",
            "okul",
            "item.school",
            "school",
            "araba",
            "item.car",
            "car",
            "park",
            "item.park",
            "park-sense",
            "g\u00fcl",
            "item.rose",
            "rose",
            "locative.singular",
            "\u00f6zelde",
            "item.explicit",
            "\u00f6zel",
            "explicit");

    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "tr",
        strings,
        List.of(
            new TermRow(2, 1, turkishBits(false, true, false, false), 3, 0),
            new TermRow(5, 4, turkishBits(false, false, true, false), 6, 1),
            new TermRow(8, 7, turkishBits(true, false, false, false), 9, 2),
            new TermRow(11, 10, turkishBits(false, false, false, true), 12, 3),
            new TermRow(14, 13, turkishBits(false, true, true, false), 15, 4),
            new TermRow(18, 19, turkishBits(false, true, false, false), 20, 5)),
        List.of(
            new FormSet(2, List.of(new FormRow(0, 1, false))),
            new FormSet(5, List.of(new FormRow(0, 4, false))),
            new FormSet(8, List.of(new FormRow(0, 7, false))),
            new FormSet(11, List.of(new FormRow(0, 10, false))),
            new FormSet(14, List.of(new FormRow(0, 13, false))),
            new FormSet(18, List.of(new FormRow(0, 19, false), new FormRow(16, 17, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
  }

  private static CompiledTermPack turkishPackMissingSuffixMetadata() {
    List<String> strings = List.of("bare.singular", "ev", "item.house", "house");

    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "tr",
        strings,
        List.of(new TermRow(2, 1, 1 | (1 << 8), 3, 0)),
        List.of(new FormSet(2, List.of(new FormRow(0, 1, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
  }

  private static CompiledTermPack turkishPackMissingBareForm() {
    List<String> strings = List.of("locative.singular", "evde", "item.house", "ev", "house");

    return new CompiledTermPack(
        CompiledTermPack.SCHEMA,
        "tr",
        strings,
        List.of(new TermRow(2, 3, turkishBits(false, true, false, false), 4, 0)),
        List.of(new FormSet(2, List.of(new FormRow(0, 1, false)))),
        CompiledTermPack.Provenance.empty(),
        CompiledTermPack.SizeEstimates.empty());
  }

  private static int turkishBits(
      boolean vowelEnd, boolean frontVowel, boolean roundedVowel, boolean hardConsonant) {
    int bits = 1 | (1 << 8) | (1 << 20);
    if (vowelEnd) {
      bits |= 1 << 21;
    }
    if (frontVowel) {
      bits |= 1 << 22;
    }
    if (roundedVowel) {
      bits |= 1 << 23;
    }
    if (hardConsonant) {
      bits |= 1 << 24;
    }
    return bits;
  }
}
