package com.box.l10n.mojito.translationintegrity.messageformat;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDiagnostic.Severity;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityEvaluation;
import java.util.List;
import org.junit.jupiter.api.Test;

class Mf2TranslationIntegrityEvaluatorTest {

  private static final String SOURCE =
      ".input {$count :number}\n.match $count\none {{One item for {$name}}}\n* {{{ $count } items for {$name}}}";

  private static final String ARABIC =
      ".input {$count :number}\n.match $count\nzero {{لا عناصر لـ{$name}}}\none {{عنصر لـ{$name}}}\ntwo {{عنصران لـ{$name}}}\nfew {{{ $count } عناصر لـ{$name}}}\nmany {{{ $count } عنصرا لـ{$name}}}\n* {{{ $count } عنصر لـ{$name}}}";

  private static TranslationIntegrityEvaluation check(String source, String target, String locale) {
    return Mf2TranslationIntegrityEvaluator.evaluate(source, target, locale);
  }

  private static void passes(TranslationIntegrityEvaluation result) {
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.PASS);
  }

  private static void rejects(TranslationIntegrityEvaluation result, String code) {
    assertThat(result.disposition()).isEqualTo(TranslationIntegrityDisposition.REJECT_TARGET);
    assertThat(result.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .contains(code);
  }

  @Test
  void requiresTargetLocaleCategoriesIncludingThoseAbsentFromSource() {
    TranslationIntegrityEvaluation result = check(SOURCE, SOURCE, "ar");
    rejects(result, "mf2-plural-category-missing");
    assertThat(
            result.diagnostics().stream()
                .filter(d -> d.code().equals("mf2-plural-category-missing"))
                .map(d -> d.details().get("category"))
                .distinct())
        .containsExactlyInAnyOrder("zero", "two", "few", "many");
  }

  @Test
  void acceptsArabicFormsAndWildcardOtherWithNaturalFixedCountOmissions() {
    TranslationIntegrityEvaluation result = check(SOURCE, ARABIC, "ar");
    passes(result);
    assertThat(result.diagnostics()).isEmpty();
  }

  @Test
  void missingVariableQuantitiesAreWarningsRatherThanRetryTriggers() {
    String source =
        ".input {$count :number}\n.match $count\none {{{ $count } item}}\n* {{{ $count } items}}";
    String target =
        ".input {$count :number}\n.match $count\none {{Один}}\nfew {{{ $count } предмета}}\nmany {{{ $count } предметов}}\n* {{{ $count } предмета}}";
    TranslationIntegrityEvaluation result = check(source, target, "ru");
    passes(result);
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("mf2-rendered-expression-missing");
              assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
            });
  }

  @Test
  void treatsAbsentOrdinaryRenderedNamesAsAmbiguous() {
    TranslationIntegrityEvaluation result = check("Hello {$name}", "Bonjour", "fr");
    passes(result);
    assertThat(result.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .containsExactly("mf2-rendered-expression-missing");
  }

  @Test
  void rejectsUnknownExternalVariablesButAllowsTargetLocalAliasesAndLiteralLocals() {
    rejects(check("Hello {$name}", "Bonjour {$other}", "fr"), "mf2-unknown-binding");
    passes(check("Hello {$name}", ".local $person = {$name}\n{{Bonjour {$person}}}", "fr"));
    passes(check("Hello", ".local $greeting = {|Bonjour|}\n{{{$greeting}}}", "fr"));
  }

  @Test
  void preservesExplicitFormatterOptions() {
    rejects(
        check(
            "Total {$amount :number minimumFractionDigits=2}",
            "Total {$amount :number minimumFractionDigits=1}",
            "fr"),
        "mf2-expression-contract-changed");
  }

  @Test
  void preservesInputAndSelectorBindings() {
    rejects(
        check(SOURCE, ARABIC.replace(":number", ":integer"), "ar"), "mf2-input-contract-changed");
    rejects(
        check(
            SOURCE,
            ARABIC.replace(".match $count", ".input {$other :number}\n.match $other"),
            "ar"),
        "mf2-selector-contract-changed");
  }

  @Test
  void resolvesSelectorAliasesAndCanonicalOptionSpelling() {
    String target =
        ARABIC
            .replace(":number", ":number select=|cardinal|")
            .replace(".match $count", ".local $n = {$count}\n.match $n");
    passes(check(SOURCE, target, "ar"));
  }

  @Test
  void ordinalCompletenessUsesOrdinalRules() {
    String source =
        ".input {$rank :integer select=ordinal}\n.match $rank\none {{{ $rank }st}}\n* {{{ $rank }th}}";
    rejects(check(source, source, "en"), "mf2-plural-category-missing");
    String target = source.replace("\n*", "\ntwo {{{ $rank }nd}}\nfew {{{ $rank }rd}}\n*");
    passes(check(source, target, "en"));
    passes(check(source, source, "fr"));
  }

  @Test
  void exactZeroMayCoverArabicSingletonZero() {
    passes(check(SOURCE, ARABIC.replace("zero {{", "0 {{"), "ar"));
  }

  @Test
  void preservesExactCasesEvenWhenThePluralCategoryExists() {
    String source = SOURCE.replace("\none", "\n0 {{No items for {$name}}}\none");
    rejects(check(source, ARABIC, "ar"), "mf2-selector-case-missing");
  }

  @Test
  void positiveExactOneAloneWarnsAboutUnprovenNegativeCoverage() {
    TranslationIntegrityEvaluation result =
        check(SOURCE, ARABIC.replace("\none {{", "\n1 {{"), "ar");
    passes(result);
    assertThat(result.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .contains("mf2-singleton-sign-coverage-unverified");
  }

  @Test
  void bothSignsCanExhaustASingletonCategory() {
    String target = ARABIC.replace("\none {{", "\n-1 {{عنصر لـ{$name}}}\n1 {{");
    TranslationIntegrityEvaluation result = check(SOURCE, target, "ar");
    passes(result);
    assertThat(result.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .doesNotContain("mf2-singleton-sign-coverage-unverified", "mf2-plural-category-missing");
  }

  @Test
  void allowsKnownInputPluralExpansionWithAnExplicitPolicyWarning() {
    String target =
        ".input {$count :number}\n.match $count\none {{One item}}\n* {{{ $count } items}}";
    TranslationIntegrityEvaluation result = check("{$count} items", target, "en");
    passes(result);
    assertThat(result.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .contains("mf2-selector-expansion-unverified");
  }

  @Test
  void requiresFallbackAndClassifiesInvalidSourceSeparately() {
    TranslationIntegrityEvaluation invalidSource = check(".match $x\none {{one}}", ARABIC, "ar");
    assertThat(invalidSource.disposition())
        .isEqualTo(TranslationIntegrityDisposition.REJECT_SOURCE);
    rejects(check(SOURCE, ARABIC.substring(0, ARABIC.indexOf("\n*")), "ar"), "mf2-invalid-target");
    rejects(check(SOURCE, ARABIC + "{", "ar"), "mf2-invalid-target");
  }

  @Test
  void rejectsUntypedSelectorsAndDuplicateBindingsAtTheirOwnSide() {
    String untyped = ".match $count\none {{One}}\n* {{Other}}";
    assertThat(check(untyped, untyped, "en").disposition())
        .isEqualTo(TranslationIntegrityDisposition.REJECT_SOURCE);
    rejects(
        check(SOURCE, ARABIC.replace(".match", ".input {$count :number}\n.match"), "ar"),
        "mf2-invalid-target");
  }

  @Test
  void checksFormsWithinStringSelectorContextsRatherThanTheirGlobalUnion() {
    String header = ".input {$gender :string}\n.input {$count :number}\n.match $gender $count\n";
    String source =
        header
            + "female one {{Her item}}\nfemale * {{Her items}}\n* one {{Their item}}\n* * {{Their items}}";
    String target =
        header
            + "female one {{Her item}}\nfemale * {{Her items}}\n* zero {{None}}\n* one {{One}}\n* two {{Two}}\n* few {{Few}}\n* many {{Many}}\n* * {{Other}}";
    TranslationIntegrityEvaluation result = check(source, target, "ar");
    rejects(result, "mf2-plural-category-missing");
    assertThat(
            result.diagnostics().stream()
                .filter(d -> d.code().equals("mf2-plural-category-missing")))
        .anySatisfy(
            d -> assertThat(((List<?>) d.details().get("context")).contains("female")).isTrue());
  }

  @Test
  void doesNotRequireAFullCartesianMatrixWhenWildcardRowsAreEffective() {
    String header = ".input {$count :number}\n.input {$gender :string}\n.match $count $gender\n";
    String source = header + "one * {{One}}\n* female {{Her items}}\n* * {{Items}}";
    String target =
        header
            + "zero * {{None}}\none * {{One}}\ntwo * {{Two}}\nfew * {{Few}}\nmany * {{Many}}\n* female {{Her items}}\n* * {{Items}}";
    passes(check(source, target, "ar"));
  }

  @Test
  void preservesNonPluralSelectorCasesInTheirContexts() {
    String source = ".input {$gender :string}\n.match $gender\nfemale {{She}}\n* {{They}}";
    rejects(check(source, source.replace("female", "male"), "en"), "mf2-selector-case-missing");
  }

  @Test
  void customAndDynamicSelectorsWarnWithoutInventingPluralRequirements() {
    for (String annotation :
        List.of(
            "custom:choice",
            "number select=$mode",
            "number minimumFractionDigits=2",
            "offset offset=1")) {
      String source =
          ".input {$count :" + annotation + "}\n.match $count\none {{One}}\n* {{Other}}";
      TranslationIntegrityEvaluation result = check(source, source, "ar");
      passes(result);
      assertThat(result.diagnostics())
          .extracting(TranslationIntegrityDiagnostic::code)
          .contains("mf2-selector-semantics-unverified")
          .doesNotContain("mf2-plural-category-missing");
    }
  }

  @Test
  void exactSelectionDoesNotRequirePluralForms() {
    String source = ".input {$count :number select=exact}\n.match $count\n1 {{One}}\n* {{Other}}";
    TranslationIntegrityEvaluation result = check(source, source, "ar");
    passes(result);
    assertThat(result.diagnostics()).isEmpty();
  }

  @Test
  void unknownLocaleWarnsWhileBindingChecksRemainActive() {
    for (String locale : new String[] {null, "", "und", "invalid-language", "en-!"}) {
      TranslationIntegrityEvaluation result = check(SOURCE, SOURCE, locale);
      passes(result);
      assertThat(result.diagnostics())
          .extracting(TranslationIntegrityDiagnostic::code)
          .contains("mf2-plural-locale-unavailable");
      rejects(
          check(SOURCE, SOURCE.replace("{$name}", "{$invented}"), locale), "mf2-unknown-binding");
    }
  }

  @Test
  void stringSelectorNumericKeysRemainDistinctStrings() {
    String source =
        ".input {$version :string}\n.match $version\n|1.0| {{Version one}}\n1 {{First version}}\n* {{Other}}";
    TranslationIntegrityEvaluation result = check(source, source, "en");
    passes(result);
    assertThat(result.diagnostics()).isEmpty();
    rejects(
        check(source, source.replace("|1.0| {{Version one}}\n", ""), "en"),
        "mf2-selector-case-missing");
  }

  @Test
  void unsupportedLocaleStillPreservesExactCases() {
    String source = SOURCE.replace("\none", "\n0 {{No items for {$name}}}\none");
    rejects(check(source, SOURCE, "en-!"), "mf2-selector-case-missing");
  }

  @Test
  void checksEachNumericSelectorInTheWinningCombinedBranch() {
    String header = ".input {$likes :number}\n.input {$shares :number}\n.match $likes $shares\n";
    String source =
        header
            + "one one {{One of each}}\none * {{One like}}\n* one {{One share}}\n* * {{Likes and shares}}";
    List<String> keys = List.of("zero", "one", "two", "few", "many", "*");
    String target =
        header
            + keys.stream()
                .flatMap(
                    first ->
                        keys.stream().map(second -> first + " " + second + " {{Translated}}\n"))
                .collect(java.util.stream.Collectors.joining());
    passes(check(source, target, "ar"));
    TranslationIntegrityEvaluation missing =
        check(source, target.replace("one few {{Translated}}\n", ""), "ar");
    rejects(missing, "mf2-plural-category-missing");
    assertThat(missing.diagnostics())
        .anySatisfy(
            d -> {
              assertThat(d.code()).isEqualTo("mf2-plural-category-missing");
              assertThat(d.details().get("selectorIndex")).isEqualTo(1);
              assertThat(d.details().get("category")).isEqualTo("few");
              assertThat(d.details().get("context")).isEqualTo(List.of("one", "few"));
            });
  }

  @Test
  void reportsTheContextBoundWithoutClaimingExhaustiveValidation() {
    StringBuilder message = new StringBuilder();
    for (int dimension = 0; dimension < 5; dimension++)
      message.append(".input {$s").append(dimension).append(" :string}\n");
    message.append(".match $s0 $s1 $s2 $s3 $s4\n");
    for (int dimension = 0; dimension < 5; dimension++) {
      for (int value = 0; value < 8; value++) {
        for (int column = 0; column < 5; column++)
          message.append(column == dimension ? "key" + value : "*").append(' ');
        message.append("{{Text}}\n");
      }
    }
    message.append("* * * * * {{Fallback}}");
    TranslationIntegrityEvaluation result = check(message.toString(), message.toString(), "en");
    passes(result);
    assertThat(result.diagnostics())
        .extracting(TranslationIntegrityDiagnostic::code)
        .contains("mf2-selector-context-limit");
  }
}
