package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Morphology;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.Term;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermRequirementReport;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermUsageRequirement;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TermValidationStatus;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.TurkishSuffix;
import com.box.l10n.mojito.mf2.inflection.TermRequirementValidator.ValidationMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TermRequirementValidatorTest {

  private final TermRequirementValidator validator = new TermRequirementValidator();

  @Test
  public void expandsTermUsageIntoRequiredMetadataAndForms() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "Vous avez supprim\u00e9 {$item :term article=definite count=$count}.");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).argument()).isEqualTo("item");
    assertThat(usages.get(0).options()).containsEntry("article", "definite");
    assertThat(usages.get(0).options()).containsEntry("count", "$count");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "elision",
            "forms.definite.singular",
            "forms.definite.plural",
            "forms.count.one",
            "forms.count.other");
  }

  @Test
  public void defaultExtractorRejectsInvalidMf2Messages() {
    assertThatThrownBy(() -> validator.requirementsForMessage("Hello {$item :term"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid MF2 message");
  }

  @Test
  public void rejectsUnsupportedTermOptionsBeforeValidation() {
    assertThatThrownBy(() -> validator.requirementsForOptions(Map.of("role", "source")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported term option: role");
  }

  @Test
  public void rejectsUnsupportedArticleBeforeValidation() {
    assertThatThrownBy(() -> validator.requirementsForOptions(Map.of("article", "partitive")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported article option: partitive");
  }

  @Test
  public void rejectsLiteralCountBeforeValidation() {
    assertThatThrownBy(() -> validator.requirementsForOptions(Map.of("count", "2")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Count option must reference a variable: 2");
  }

  @Test
  public void rejectsPrepositionWithoutArticleBeforeValidation() {
    assertThatThrownBy(() -> validator.requirementsForOptions(Map.of("preposition", "de")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Preposition option requires article option");
  }

  @Test
  public void rejectsUnsupportedPrepositionArticleCombinationBeforeValidation() {
    assertThatThrownBy(
            () ->
                validator.requirementsForOptions(
                    Map.of("preposition", "por", "article", "indefinite")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported preposition/article combination: por + indefinite");
  }

  @Test
  public void rejectsPrepositionCaseCombinationBeforeValidation() {
    assertThatThrownBy(
            () ->
                validator.requirementsForOptions(
                    Map.of("preposition", "de", "article", "definite", "case", "dative")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Preposition option cannot be combined with case option");
  }

  @Test
  public void rejectsDefinitenessArticleCombinationBeforeValidation() {
    assertThatThrownBy(
            () ->
                validator.requirementsForOptions(
                    Map.of("definiteness", "construct", "article", "definite")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Definiteness option cannot be combined with article option");
  }

  @Test
  public void rejectsNumberCountCombinationBeforeValidation() {
    assertThatThrownBy(
            () -> validator.requirementsForOptions(Map.of("number", "dual", "count", "$count")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Number option cannot be combined with count option");
  }

  @Test
  public void expandsCaseUsageIntoRequiredCaseForms() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage("Obrisano {$item :term case=accusative count=$count}.");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options()).containsEntry("case", "accusative");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "forms.accusative.singular",
            "forms.accusative.plural",
            "forms.count.one",
            "forms.count.other");
  }

  @Test
  public void expandsPrepositionalCaseUsageIntoRequiredCaseForms() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage("\u0412 {$item :term case=prepositional count=$count}.");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options()).containsEntry("case", "prepositional");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "forms.prepositional.singular",
            "forms.prepositional.plural",
            "forms.count.one",
            "forms.count.other");
  }

  @Test
  public void expandsArabicDefinitenessCaseNumberUsageIntoExactFormRequirement() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "اختيرت {$item :term definiteness=construct case=nominative number=dual}.", "ar");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options()).containsEntry("definiteness", "construct");
    assertThat(usages.get(0).options()).containsEntry("number", "dual");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun", "gender", "number", "forms.construct.nominative.dual");
  }

  @Test
  public void expandsArabicDefinitenessCaseUsageIntoSingularAndPluralRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "مع {$item :term definiteness=construct case=genitive}.", "ar");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "forms.construct.genitive.singular",
            "forms.construct.genitive.plural");
  }

  @Test
  public void expandsHindiObliqueCaseUsageIntoRequiredCaseForms() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "\u092e\u0947\u0902 {$item :term case=oblique count=$count}.", "hi");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options()).containsEntry("case", "oblique");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "forms.oblique.singular",
            "forms.oblique.plural",
            "forms.count.one",
            "forms.count.other");
  }

  @Test
  public void expandsHindiGenitivePronounUsageIntoAgreementRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "{$owner :term person=first case=genitive count=$ownerCount "
                + "agreeWith=$item agreeWithCount=$itemCount}.",
            "hi");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).argument()).isEqualTo("owner");
    assertThat(usages.get(0).options()).containsEntry("agreeWith", "$item");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "hindiPronoun.person",
            "hindiPronoun.case",
            "hindiPronoun.number",
            "agreeWith.gender",
            "agreeWith.count");
  }

  @Test
  public void validatesHindiPronounAgreementFromReferencedTermMetadata() {
    TermRequirementReport report =
        validator.validate(
            "hi",
            Map.of(
                "inventory.owner",
                "{$owner :term person=first case=genitive count=$ownerCount "
                    + "agreeWith=$item agreeWithCount=$itemCount}."),
            Map.of("inventory.owner", Map.of("item", List.of("hi.case.अंगारा"))),
            Map.of("hi.case.अंगारा", hindiMasculineTerm(null)));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.owner").termUsages().get(0).termIds()).isEmpty();
    assertThat(report.messages().get("inventory.owner").termUsages().get(0).validations())
        .isEmpty();
  }

  @Test
  public void reportsMissingHindiPronounAgreementTargetBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "hi",
            Map.of("inventory.owner", "{$owner :term person=first case=genitive agreeWith=$item}."),
            Map.of(),
            Map.of());

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).argument()).isEqualTo("owner");
    assertThat(report.diagnostics().get(0).termId()).isNull();
    assertThat(report.diagnostics().get(0).relatedArgument()).isEqualTo("item");
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly("agreeWith.missing-argument-terms");
  }

  @Test
  public void reportsMissingHindiPronounReferencedMetadataBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "hi",
            Map.of("inventory.owner", "{$owner :term person=first case=genitive agreeWith=$item}."),
            Map.of("inventory.owner", Map.of("item", List.of("hi.case.अंगारा"))),
            Map.of(
                "hi.case.अंगारा",
                new Term("अंगारा", new Morphology("noun", null, null, null, null), Map.of())));

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).termId()).isEqualTo("hi.case.अंगारा");
    assertThat(report.diagnostics().get(0).relatedArgument()).isEqualTo("item");
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly("agreeWith.gender", "agreeWith.number");
  }

  @Test
  public void reportsHindiSecondPersonPronounMissingRegisterBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "hi",
            Map.of("inventory.owner", "{$owner :term person=second case=direct}."),
            Map.of(),
            Map.of());

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).missing()).containsExactly("register");
  }

  @Test
  public void rejectsUnsupportedHindiPronounOptionBeforeValidation() {
    assertThatThrownBy(
            () ->
                validator.requirementsForOptions(
                    "hi", Map.of("person", "first", "case", "direct", "article", "definite")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported Hindi pronoun term option: article");
  }

  @Test
  public void expandsSpanishArticleUsageIntoMetadataCompositionRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "Has eliminado {$item :term article=definite count=$count}.", "es");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "stress",
            "forms.bare.singular",
            "forms.bare.plural");
  }

  @Test
  public void expandsItalianArticleUsageIntoMetadataCompositionRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "Hai eliminato {$item :term article=definite count=$count}.", "it");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "articleClass",
            "forms.bare.singular",
            "forms.bare.plural");
  }

  @Test
  public void expandsPortugueseArticleUsageIntoMetadataCompositionRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "Removido {$item :term article=definite count=$count}.", "pt");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun", "gender", "number", "forms.bare.singular", "forms.bare.plural");
  }

  @Test
  public void expandsPortuguesePrepositionArticleUsageIntoMetadataCompositionRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage(
            "Removido {$item :term preposition=de article=definite count=$count}.", "pt");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).options()).containsEntry("preposition", "de");
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun", "gender", "number", "forms.bare.singular", "forms.bare.plural");
  }

  @Test
  public void expandsTurkishCaseUsageIntoSuffixCompositionRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage("Silindi {$item :term case=ablative count=$count}.", "tr");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "number",
            "turkishSuffix.vowelEnd",
            "turkishSuffix.frontVowel",
            "turkishSuffix.roundedVowel",
            "turkishSuffix.hardConsonant",
            "forms.bare.singular");
  }

  @Test
  public void reportsUnsupportedRuntimeTermInflectionForMetadataOnlyLocale() {
    TermRequirementReport report =
        validator.validate(
            "nb-NO",
            Map.of(
                "inventory.deleted", "Slettet {$item :term definiteness=definite count=$count}."),
            Map.of("inventory.deleted", Map.of("item", List.of("nb.item.book"))),
            Map.of("nb.item.book", new Term("bok", null, Map.of())));

    TermUsageRequirement usage = report.messages().get("inventory.deleted").termUsages().get(0);
    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly(TermRequirementValidator.UNSUPPORTED_RUNTIME_TERM_INFLECTION);
    assertThat(usage.requirements())
        .containsExactly(TermRequirementValidator.UNSUPPORTED_RUNTIME_TERM_INFLECTION);
    assertThat(usage.termIds()).containsExactly("nb.item.book");
    assertThat(usage.validations()).isEmpty();
  }

  @Test
  public void reportsUnsupportedRuntimeTermInflectionForProfileOnlyLocaleWithoutBindingCascade() {
    TermRequirementReport report =
        validator.validate(
            "zh-Hant",
            Map.of("inventory.deleted", "Deleted {$item :term number=plural}."),
            Map.of(),
            Map.of());

    assertThat(report.summary().diagnostics()).isEqualTo(1);
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly(TermRequirementValidator.UNSUPPORTED_RUNTIME_TERM_INFLECTION);
  }

  @Test
  public void allowsBareTermUsageForProfileOnlyLocaleToUseNormalRequirements() {
    List<TermUsageRequirement> usages =
        validator.requirementsForMessage("Selected {$item :term}.", "ja");

    assertThat(usages).hasSize(1);
    assertThat(usages.get(0).requirements())
        .doesNotContain(TermRequirementValidator.UNSUPPORTED_RUNTIME_TERM_INFLECTION);
    assertThat(usages.get(0).requirements()).contains("forms.bare.singular");
  }

  @Test
  public void rejectsUnsupportedCaseBeforeValidation() {
    assertThatThrownBy(() -> validator.requirementsForOptions(Map.of("case", "ergative")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported case option: ergative");
  }

  @Test
  public void reportsMissingMetadataAndFormsBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            Map.of(
                "inventory.deleted",
                "Vous avez supprim\u00e9 {$item :term article=definite count=$count}."),
            Map.of(
                "inventory.deleted", Map.of("item", List.of("item.iron_sword", "item.incomplete"))),
            Map.of(
                "item.iron_sword",
                ironSword(),
                "item.incomplete",
                new Term(
                    "\u00e9p\u00e9e de bois",
                    new Morphology("noun", "feminine", "singular", null, null),
                    Map.of(
                        "definite.singular",
                        "l'\u00e9p\u00e9e de bois",
                        "count.one",
                        "1 \u00e9p\u00e9e de bois"))));

    assertThat(report.summary().messages()).isEqualTo(1);
    assertThat(report.summary().termUsages()).isEqualTo(1);
    assertThat(report.summary().diagnostics()).isEqualTo(1);
    assertThat(report.diagnostics().get(0).termId()).isEqualTo("item.incomplete");
    assertThat(report.diagnostics().get(0).span()).isEqualTo(new SourceSpan(19, 62));
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly("startsWithVowelSound", "definite.plural", "count.other");
  }

  @Test
  public void keepsAmbiguousSurfaceSeparateByTermId() {
    TermRequirementReport report =
        validator.validate(
            Map.of(
                "inventory.deleted",
                "Vous avez supprim\u00e9 {$item :term article=definite count=$count}."),
            Map.of("inventory.deleted", Map.of("item", List.of("concept.book", "unit.pound"))),
            Map.of("concept.book", book(), "unit.pound", pound()));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(
            report.messages().get("inventory.deleted").termUsages().get(0).validations().stream()
                .map(validation -> validation.status()))
        .containsExactly(TermValidationStatus.OK, TermValidationStatus.OK);
  }

  @Test
  public void validatesSpanishArticleCompositionFromBareForms() {
    TermRequirementReport report =
        validator.validate(
            "es",
            Map.of(
                "inventory.deleted", "Has eliminado {$item :term article=definite count=$count}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.water"))),
            Map.of("item.water", water()));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").termUsages().get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "stress",
            "forms.bare.singular",
            "forms.bare.plural");
  }

  @Test
  public void reportsMissingSpanishStressMetadataBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "es",
            Map.of("inventory.deleted", "Has eliminado {$item :term article=definite}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.water"))),
            Map.of(
                "item.water",
                new Term(
                    "agua",
                    new Morphology("noun", "feminine", "singular", null, null),
                    Map.of("bare.singular", "agua", "bare.plural", "aguas"))));

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).missing()).containsExactly("stressed");
  }

  @Test
  public void validatesItalianArticleCompositionFromArticleClassAndBareForms() {
    TermRequirementReport report =
        validator.validate(
            "it",
            Map.of(
                "inventory.deleted", "Hai eliminato {$item :term article=definite count=$count}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.gnome"))),
            Map.of("item.gnome", italianGnome()));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").termUsages().get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "gender",
            "number",
            "articleClass",
            "forms.bare.singular",
            "forms.bare.plural");
  }

  @Test
  public void reportsMissingItalianArticleClassBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "it",
            Map.of("inventory.deleted", "Hai eliminato {$item :term article=definite}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.gnome"))),
            Map.of(
                "item.gnome",
                new Term(
                    "gnomo",
                    new Morphology("noun", "masculine", "singular", null, null),
                    Map.of("bare.singular", "gnomo", "bare.plural", "gnomi"))));

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).missing()).containsExactly("articleClass");
  }

  @Test
  public void validatesPortugueseArticleCompositionFromBareForms() {
    TermRequirementReport report =
        validator.validate(
            "pt",
            Map.of(
                "inventory.from",
                "Removido {$item :term preposition=de article=definite count=$count}."),
            Map.of("inventory.from", Map.of("item", List.of("item.field"))),
            Map.of("item.field", field()));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.from").termUsages().get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun", "gender", "number", "forms.bare.singular", "forms.bare.plural");
  }

  @Test
  public void reportsMissingPortugueseBareFormBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "pt",
            Map.of("inventory.deleted", "Removido {$item :term article=definite}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.field"))),
            Map.of(
                "item.field",
                new Term(
                    "campo",
                    new Morphology("noun", "masculine", "singular", null, "field"),
                    Map.of("bare.singular", "campo"))));

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).missing()).containsExactly("bare.plural");
  }

  @Test
  public void validatesTurkishSuffixCompositionFromMetadata() {
    TermRequirementReport report =
        validator.validate(
            "tr",
            Map.of("inventory.deleted", "Silindi {$item :term case=accusative count=$count}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.house"))),
            Map.of("item.house", turkishHouse()));

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").termUsages().get(0).requirements())
        .containsExactly(
            "partOfSpeech=noun",
            "number",
            "turkishSuffix.vowelEnd",
            "turkishSuffix.frontVowel",
            "turkishSuffix.roundedVowel",
            "turkishSuffix.hardConsonant",
            "forms.bare.singular");
  }

  @Test
  public void reportsMissingTurkishSuffixMetadataBeforeRendering() {
    TermRequirementReport report =
        validator.validate(
            "tr",
            Map.of("inventory.deleted", "Silindi {$item :term case=accusative count=$count}."),
            Map.of("inventory.deleted", Map.of("item", List.of("item.house"))),
            Map.of(
                "item.house",
                new Term(
                    "ev",
                    new Morphology("noun", null, "singular", null, "house"),
                    Map.of("bare.singular", "ev"))));

    assertThat(report.diagnostics()).hasSize(1);
    assertThat(report.diagnostics().get(0).missing())
        .containsExactly("vowelEnd", "frontVowel", "roundedVowel", "hardConsonant");
  }

  @Test
  public void reportsMissingTermOnce() {
    assertThat(
            validator
                .validate(
                    Map.of("inventory.deleted", "{$item :term article=definite}."),
                    Map.of("inventory.deleted", Map.of("item", List.of("item.missing"))),
                    Map.of())
                .diagnostics()
                .get(0)
                .missing())
        .containsExactly("missing-term");
  }

  @Test
  public void reportsMissingArgumentTermsInClosedWorldMode() {
    TermRequirementReport report =
        validator.validate(
            Map.of("inventory.deleted", "{$item :term article=definite}."), Map.of(), Map.of());

    assertThat(report.summary().diagnostics()).isEqualTo(1);
    assertThat(report.diagnostics().get(0).messageId()).isEqualTo("inventory.deleted");
    assertThat(report.diagnostics().get(0).argument()).isEqualTo("item");
    assertThat(report.diagnostics().get(0).termId()).isNull();
    assertThat(report.diagnostics().get(0).span()).isEqualTo(new SourceSpan(0, 30));
    assertThat(report.diagnostics().get(0).missing()).containsExactly("missing-argument-terms");
    assertThat(report.messages().get("inventory.deleted").termUsages().get(0).termIds()).isEmpty();
  }

  @Test
  public void allowsMissingArgumentTermsInOpenWorldMode() {
    TermRequirementReport report =
        validator.validate(
            Map.of("inventory.deleted", "{$item :term article=definite}."),
            Map.of(),
            Map.of(),
            ValidationMode.OPEN_WORLD);

    assertThat(report.diagnostics()).isEmpty();
    assertThat(report.messages().get("inventory.deleted").termUsages().get(0).termIds()).isEmpty();
  }

  @Test
  public void acceptsParserBackedTermUsageExtractor() {
    TermRequirementValidator parserBackedValidator =
        new TermRequirementValidator(
            message ->
                List.of(
                    new TermUsageExtractor.TermUsage(
                        "item", Map.of("article", "definite"), 7, message.length())));

    TermRequirementReport report =
        parserBackedValidator.validate(
            Map.of("inventory.deleted", "ignored parser-backed message"),
            Map.of("inventory.deleted", Map.of("item", List.of("concept.book"))),
            Map.of("concept.book", book()));

    assertThat(report.messages().get("inventory.deleted").termUsages().get(0).span())
        .isEqualTo(new SourceSpan(7, "ignored parser-backed message".length()));
    assertThat(report.diagnostics()).isEmpty();
  }

  @Test
  public void termUsageRequirementDefensivelyCopiesCollections() {
    Map<String, String> options = new LinkedHashMap<>();
    options.put("article", "definite");
    List<String> requirements = new ArrayList<>(List.of("gender"));
    List<String> termIds = new ArrayList<>(List.of("item.iron_sword"));
    List<TermRequirementValidator.TermValidation> validations =
        new ArrayList<>(
            List.of(
                new TermRequirementValidator.TermValidation(
                    "item.iron_sword", TermValidationStatus.OK, List.of())));

    TermUsageRequirement usage =
        new TermUsageRequirement(
            "item", options, new SourceSpan(0, 30), requirements, termIds, validations);
    options.put("count", "$count");
    requirements.add("number");
    termIds.add("concept.book");
    validations.clear();

    assertThat(usage.options()).containsExactly(Map.entry("article", "definite"));
    assertThat(usage.requirements()).containsExactly("gender");
    assertThat(usage.termIds()).containsExactly("item.iron_sword");
    assertThat(usage.validations()).hasSize(1);
    assertThatThrownBy(() -> usage.options().put("count", "$count"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> usage.requirements().add("number"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void reportDefensivelyCopiesCollections() {
    Map<String, TermRequirementValidator.MessageRequirement> messages = new LinkedHashMap<>();
    List<TermRequirementValidator.Diagnostic> diagnostics =
        new ArrayList<>(
            List.of(
                new TermRequirementValidator.Diagnostic(
                    "inventory.deleted",
                    "item",
                    null,
                    new SourceSpan(0, 30),
                    List.of("missing-argument-terms"))));

    TermRequirementReport report =
        new TermRequirementReport(
            messages, diagnostics, new TermRequirementValidator.Summary(0, 0, 1));
    messages.put(
        "inventory.deleted", new TermRequirementValidator.MessageRequirement("x", List.of()));
    diagnostics.clear();

    assertThat(report.messages()).isEmpty();
    assertThat(report.diagnostics()).hasSize(1);
    assertThatThrownBy(
            () ->
                report
                    .messages()
                    .put(
                        "inventory.deleted",
                        new TermRequirementValidator.MessageRequirement("x", List.of())))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> report.diagnostics().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private Term ironSword() {
    return new Term(
        "\u00e9p\u00e9e de fer",
        new Morphology("noun", "feminine", "singular", true, null),
        Map.of(
            "definite.singular",
            "l'\u00e9p\u00e9e de fer",
            "definite.plural",
            "les \u00e9p\u00e9es de fer",
            "count.one",
            "1 \u00e9p\u00e9e de fer",
            "count.other",
            "{$count} \u00e9p\u00e9es de fer"));
  }

  private Term book() {
    return new Term(
        "livre",
        new Morphology("noun", "masculine", "singular", false, "book"),
        Map.of(
            "definite.singular",
            "le livre",
            "definite.plural",
            "les livres",
            "count.one",
            "1 livre",
            "count.other",
            "{$count} livres"));
  }

  private Term pound() {
    return new Term(
        "livre",
        new Morphology("noun", "feminine", "singular", false, "unit-pound"),
        Map.of(
            "definite.singular",
            "la livre",
            "definite.plural",
            "les livres",
            "count.one",
            "1 livre",
            "count.other",
            "{$count} livres"));
  }

  private Term water() {
    return new Term(
        "agua",
        new Morphology("noun", "feminine", "singular", null, true, "water"),
        Map.of("bare.singular", "agua", "bare.plural", "aguas"));
  }

  private Term italianGnome() {
    return new Term(
        "gnomo",
        new Morphology("noun", "masculine", "singular", null, null, "lo", "gnome"),
        Map.of("bare.singular", "gnomo", "bare.plural", "gnomi"));
  }

  private Term field() {
    return new Term(
        "campo",
        new Morphology("noun", "masculine", "singular", null, "field"),
        Map.of("bare.singular", "campo", "bare.plural", "campos"));
  }

  private Term turkishHouse() {
    return new Term(
        "ev",
        new Morphology(
            "noun",
            null,
            "singular",
            null,
            null,
            null,
            "house",
            new TurkishSuffix(false, true, false, false)),
        Map.of("bare.singular", "ev"));
  }

  private Term hindiMasculineTerm(String number) {
    return new Term("अंगारा", new Morphology("noun", "masculine", number, null, null), Map.of());
  }
}
