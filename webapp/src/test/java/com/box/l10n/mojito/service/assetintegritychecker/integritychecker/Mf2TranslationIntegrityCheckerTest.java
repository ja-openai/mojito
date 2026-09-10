package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.translationintegrity.TranslationIntegrityDisposition;
import com.box.l10n.mojito.translationintegrity.TranslationIntegrityInputLimits;
import org.junit.Test;

public class Mf2TranslationIntegrityCheckerTest {
  private final Mf2TranslationIntegrityChecker checker = new Mf2TranslationIntegrityChecker();
  private static final String SOURCE =
      ".input {$count :number}\n.match $count\none {{One item for {$name}}}\n* {{{ $count } items for {$name}}}";
  private static final String ARABIC =
      ".input {$count :number}\n.match $count\nzero {{لا عناصر لـ{$name}}}\none {{عنصر لـ{$name}}}\ntwo {{عنصران لـ{$name}}}\nfew {{{ $count } عناصر لـ{$name}}}\nmany {{{ $count } عنصرا لـ{$name}}}\n* {{{ $count } عنصر لـ{$name}}}";

  @Test
  public void selectableThroughExistingConfiguration() throws Exception {
    assertEquals(
        Mf2TranslationIntegrityChecker.class.getName(), IntegrityCheckerType.MF2.getClassName());
    assertTrue(
        Class.forName(IntegrityCheckerType.MF2.getClassName())
                .getDeclaredConstructor()
                .newInstance()
            instanceof TextUnitIntegrityChecker);
  }

  @Test
  public void acceptsValidTarget() {
    checker.check("Hello {$name}", "Bonjour {$name}", "fr");
    checker.check(SOURCE, ARABIC, "ar");
  }

  @Test
  public void rejectsChangedExternalBindings() {
    var failure =
        assertThrows(
            TranslationIntegrityCheckerException.class,
            () -> checker.check("Hello {$name}", "Bonjour {$other}", "fr"));
    assertEquals(
        TranslationIntegrityDisposition.REJECT_TARGET, failure.getEvaluation().disposition());
  }

  @Test
  public void targetLocaleChangesPluralRequirements() {
    checker.check(SOURCE, SOURCE, "en");
    var failure =
        assertThrows(
            TranslationIntegrityCheckerException.class, () -> checker.check(SOURCE, SOURCE, "ar"));
    assertTrue(
        failure.getEvaluation().diagnostics().stream()
            .anyMatch(d -> d.code().equals("mf2-plural-category-missing")));
  }

  @Test
  public void legacyCallsStillPerformStructuralChecksWithoutAssumingEnglish() {
    checker.check(SOURCE, SOURCE);
    assertThrows(
        TranslationIntegrityCheckerException.class,
        () -> checker.check("Hello {$name}", "Bonjour {$other}"));
  }

  @Test
  public void sourceDefectsAndWordingWarningsDoNotBlockTargets() {
    checker.check("Hello {$name", "Bonjour {$name}", "fr");
    checker.check(
        ".input {$n :number}\n.match $n\none {{{ $n } item}}\n* {{{ $n } items}}",
        ".input {$n :number}\n.match $n\none {{One item}}\n* {{{ $n } items}}",
        "en");
  }

  @Test
  public void oversizedTargetIsRejectedBeforeParsing() {
    assertThrows(
        TranslationIntegrityCheckerException.class,
        () ->
            checker.check(
                "Hello {$name}",
                "x".repeat(TranslationIntegrityInputLimits.MAX_UTF16_CODE_UNITS + 1),
                "fr"));
  }
}
