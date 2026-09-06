package com.box.l10n.mojito.translationintegrity.messageformat;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcuTranslationIntegrityEvaluatorTest {
  private static final String SOURCE = "{count, plural, one {One file} other {# files}}";
  private static final String ARABIC =
      "{count, plural, zero {لا ملفات} one {ملف واحد} two {ملفان}"
          + " few {# ملفات} many {# ملف} other {# ملف}}";
  private static final String RUSSIAN =
      "{count, plural, one {# файл} few {# файла} many {# файлов} other {# файла}}";

  @Test
  void allowsArabicGrammaticalCountOmissionInSingletonCategories() {
    assertThat(evaluate(SOURCE, ARABIC, "ar")).isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(evaluate("{count, plural, other {{count} files}}", ARABIC, "ar"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void warnsWhenRussianOneDropsCountNeededForTwentyOne() {
    TranslationIntegrityEvaluation result =
        evaluate(SOURCE, RUSSIAN.replace("# файл}", "файл}"), "ru");

    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(result.diagnostics())
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("missing-rendered-argument");
              assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
              assertThat(diagnostic.details()).containsEntry("argument", "count");
              assertThat(diagnostic.details().get("context").toString()).contains("[one]");
            });
    assertThat(evaluate(SOURCE, RUSSIAN, "ru")).isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void keepsWarningsForFormattedCountOmissionsInFixedCategories() {
    TranslationIntegrityEvaluation result =
        evaluate("{count, plural, other {{count, number} files}}", ARABIC, "ar");

    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(result)).contains("missing-rendered-argument");
    assertThat(result.diagnostics())
        .allMatch(diagnostic -> diagnostic.severity() == Severity.WARNING);
  }

  @Test
  void checksAllArabicCategories() {
    TranslationIntegrityEvaluation result = evaluate(SOURCE, SOURCE, "ar");

    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("missing-plural-categories");
              assertThat(diagnostic.details())
                  .containsEntry("missing", List.of("few", "many", "two", "zero"));
            });
  }

  @Test
  void usesCurrentFrenchRulesAndRegionalLocaleTags() {
    assertThat(codes(evaluate(SOURCE, SOURCE, "fr-CA"))).contains("missing-plural-categories");
    assertThat(
            evaluate(
                    SOURCE,
                    "{count, plural, one {Un fichier} many {# fichiers} other {# fichiers}}",
                    "fr_CA")
                .disposition())
        .isEqualTo(TranslationIntegrityDisposition.PASS);
  }

  @Test
  void doesNotRequireIrrelevantSourceCategoriesInJapanese() {
    assertThat(evaluate(SOURCE, "{count, plural, other {# 個のファイル}}", "ja"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void distinguishesCardinalAndOrdinalSelection() {
    String ordinal = "{count, selectordinal, one {#st} two {#nd} few {#rd} other {#th}}";
    assertThat(evaluate(ordinal, ordinal, "en")).isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(codes(evaluate(ordinal, "{count, selectordinal, one {#st} other {#th}}", "en")))
        .contains("missing-plural-categories");
    assertThat(codes(evaluate(SOURCE, ordinal, "en"))).contains("missing-selector");
  }

  @Test
  void checksEachNestedContextIndependently() {
    String source = "{gender, select, male {" + SOURCE + "} other {" + SOURCE + "}}";
    String target = "{gender, select, male {" + ARABIC + "} other {" + SOURCE + "}}";
    TranslationIntegrityEvaluation result = evaluate(source, target, "ar");

    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(
            result.diagnostics().stream()
                .filter(diagnostic -> diagnostic.code().equals("missing-plural-categories")))
        .singleElement()
        .satisfies(
            diagnostic ->
                assertThat(diagnostic.details().get("context").toString()).contains("[other]"));
  }

  @Test
  void supportsReorderedIndependentSelectorsAndChecksTheirOccurrences() {
    String files = "{files, plural, other {# files}}";
    String folders = "{folders, plural, other {# folders}}";
    assertThat(evaluate(files + folders, folders + files, "ja"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    assertThat(codes(evaluate(files + files, files, "ja"))).contains("missing-selector");
    assertThat(codes(evaluate(files + folders, files, "ja"))).contains("missing-selector");
  }

  @Test
  void permitsNewSelectorsUsingExistingRuntimeArguments() {
    assertThat(evaluate("{count} files", ARABIC, "ar").disposition())
        .isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(evaluate(SOURCE, ARABIC.replace("{count, plural", "{invented, plural"), "ar")))
        .contains("unknown-argument", "missing-selector");
  }

  @Test
  void preservesSelectorEvenWhenSourceNeverRendersCount() {
    assertThat(codes(evaluate("{count, plural, one {A file} other {Files}}", "Files", "en")))
        .contains("missing-selector");
  }

  @Test
  void preservesApplicationSelectBranches() {
    String source = "{gender, select, female {Her file} male {His file} other {Their file}}";
    assertThat(
            codes(
                evaluate(
                    source, "{gender, select, female {Son fichier} other {Leur fichier}}", "fr")))
        .contains("missing-select-branches");
  }

  @Test
  void allowsExactCasesThatExhaustFiniteCategories() {
    String target =
        ARABIC
            .replace("zero {لا ملفات}", "=0 {لا ملفات}")
            .replace("one {ملف واحد}", "=1 {ملف واحد} =-1 {ملف واحد}");
    assertThat(evaluate(SOURCE, target, "ar")).isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void warnsAboutUnprovenNegativeDomainInsteadOfForcingExtraCategory() {
    String target = ARABIC.replace("one {ملف واحد}", "=1 {ملف واحد}");
    TranslationIntegrityEvaluation result = evaluate(SOURCE, target, "ar");
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(result)).contains("plural-exact-coverage-uncertain");
  }

  @Test
  void exactCasesDoNotExhaustRepeatingRussianCategory() {
    String target = RUSSIAN.replace("one {# файл}", "=1 {Один файл}");
    assertThat(evaluate(SOURCE, target, "ru").disposition())
        .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(codes(evaluate(SOURCE, target, "ru"))).contains("missing-plural-categories");
  }

  @Test
  void preservesExactCasesAndOffsets() {
    String source = "{count, plural, offset:1 =0 {Nobody} one {One guest} other {# guests}}";
    String target = "{count, plural, offset:1 =0 {Personne} one {Un invité} other {# invités}}";
    assertThat(evaluate(source, target, "en").disposition())
        .isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(evaluate(source, target.replace("offset:1", "offset:2"), "en")))
        .contains("plural-offset-changed");
    assertThat(codes(evaluate(source, target.replace("=0 {Personne}", ""), "en")))
        .contains("missing-exact-branches");
  }

  @Test
  void accountsForOffsetBeforeProvingExactCoverage() {
    String source = "{count, plural, offset:1 one {# file} other {# files}}";
    String target = "{count, plural, offset:1 =0 {Minus one} =2 {One} other {# files}}";
    assertThat(evaluate(source, target, "en")).isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void missingCountInOffsetCategoryIsAdvisory() {
    String message = "{count, plural, offset:1 one {One guest} other {# guests}}";
    TranslationIntegrityEvaluation result = evaluate(message, message, "en");
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(result)).contains("missing-rendered-argument");
  }

  @Test
  void warnsForUnrelatedMissingVariableAndRejectsInventedVariable() {
    assertThat(evaluate("Hello {name}", "Bonjour", "fr").disposition())
        .isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(evaluate("Hello {name}", "Bonjour", "fr")))
        .containsExactly("missing-rendered-argument");
    assertThat(evaluate("Hello {name}", "Bonjour {surname}", "fr").disposition())
        .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(codes(evaluate("Hello {name}", "Bonjour {surname}", "fr")))
        .contains("unknown-argument");
  }

  @Test
  void permitsPlaceholderMovementAcrossGuaranteedBranches() {
    String source = "{name} {gender, select, female {Her file} other {Their file}}";
    String target = "{gender, select, female {{name}, son fichier} other {{name}, leur fichier}}";
    assertThat(evaluate(source, target, "fr")).isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void doesNotTreatQuotedPlaceholdersAsRuntimeArguments() {
    assertThat(evaluate("'{name}' {value}", "'{literal}' {value}", "en"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
  }

  @Test
  void keepsCustomFormatsAndOrdinaryTextUsable() {
    TranslationIntegrityEvaluation changedCustom =
        evaluate("Value {n, custom, application-style}", "Valeur {n, custom, other-style}", "fr");
    assertThat(changedCustom.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(changedCustom)).containsExactly("argument-format-changed");
    assertThat(evaluate("Today's files", "Les fichiers d'aujourd'hui", "fr"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    TranslationIntegrityEvaluation custom =
        evaluate(
            "{count, plural, other {{count, number, integer} files}}",
            "{count, plural, other {{count, number, integer} fichiers}}",
            "fr");
    assertThat(custom.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(custom)).contains("plural-format-analysis-unsupported");
  }

  @Test
  void warnsWhenCurrencyFormattingChangesWithoutRejectingTheTranslation() {
    TranslationIntegrityEvaluation result =
        evaluate(
            "Price: {amount, number, ::currency/USD}",
            "Prix : {amount, number, ::currency/EUR}",
            "fr");
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(result.diagnostics())
        .singleElement()
        .satisfies(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("argument-format-changed");
              assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
              assertThat(diagnostic.details())
                  .containsEntry("sourceFormats", List.of("number, ::currency/USD"))
                  .containsEntry("targetFormats", List.of("number, ::currency/EUR"));
            });
  }

  @Test
  void warnsWhenBareArgumentAcquiresAnExplicitType() {
    TranslationIntegrityEvaluation result =
        evaluate("Hello {name}", "Bonjour {name, number}", "fr");
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(result)).containsExactly("argument-format-changed");
  }

  @Test
  void comparesFormattingContractsWithoutRequiringIdenticalRepetitionCounts() {
    String source = "{date, date, short}, again {date, date, short}";
    assertThat(evaluate(source, "{date, date, short}", "fr"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    TranslationIntegrityEvaluation changed = evaluate(source, "{date, date, long}", "fr");
    assertThat(changed.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(changed)).containsExactly("argument-format-changed");
  }

  @Test
  void distinguishesExplicitCounterFormattingFromBareCountRendering() {
    String target = "{count, plural, other {# 個}}";
    assertThat(evaluate("{count, plural, other {{count, number} files}}", target, "ja"))
        .isEqualTo(TranslationIntegrityEvaluation.pass());
    TranslationIntegrityEvaluation changed =
        evaluate("{count, plural, other {{count, number, integer} files}}", target, "ja");
    assertThat(changed.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(changed)).containsExactly("argument-format-changed");
  }

  @Test
  void invalidLocaleSkipsCoverageButRetainsStructure() {
    for (String locale : new String[] {null, "", "zz-ZZ"}) {
      assertThat(evaluate(SOURCE, SOURCE, locale).disposition())
          .isEqualTo(TranslationIntegrityDisposition.PASS);
      assertThat(codes(evaluate(SOURCE, SOURCE, locale))).contains("plural-locale-unknown");
      assertThat(codes(evaluate(SOURCE, "Files", locale))).contains("missing-selector");
    }
  }

  @Test
  void separatesMalformedSourceFromMalformedTargetAndRequiresFallback() {
    assertThat(evaluate("{count, plural, one {File}}", SOURCE, "en").disposition())
        .isEqualTo(TranslationIntegrityDisposition.REJECT_SOURCE);
    for (String target :
        List.of(
            "{count, plural, one {File}}",
            "{count, plural, other {Files}",
            "{count, plural, one {File} one {File} other {Files}}")) {
      assertThat(evaluate(SOURCE, target, "en").disposition())
          .isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
      assertThat(codes(evaluate(SOURCE, target, "en"))).contains("target-format-invalid");
    }
  }

  @Test
  void doesNotGuessChoiceRangeSemantics() {
    String message = "{count, choice, 0#No files|1#One file|1<Many files}";
    TranslationIntegrityEvaluation result = evaluate(message, message, "en");
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
    assertThat(codes(result)).contains("choice-analysis-unsupported");
  }

  private static TranslationIntegrityEvaluation evaluate(
      String source, String target, String locale) {
    return IcuTranslationIntegrityEvaluator.evaluate(source, target, locale);
  }

  private static List<String> codes(TranslationIntegrityEvaluation result) {
    return result.diagnostics().stream().map(TranslationIntegrityDiagnostic::code).toList();
  }
}
