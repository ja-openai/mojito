package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class FormatJsRichTextIntegrityCheckerTest {

  private final FormatJsRichTextIntegrityChecker checker = new FormatJsRichTextIntegrityChecker();

  @Test
  public void testRejectsItalianApostropheBeforeRichTextTag() {
    String target =
        "Consulta i <termsLink>termini del servizio</termsLink> e "
            + "l'<privacyLink>informativa sulla privacy</privacyLink>.";

    try {
      checker.check("", target);
      fail("FormatJsRichTextIntegrityCheckerException must be thrown");
    } catch (FormatJsRichTextIntegrityCheckerException e) {
      assertEquals(
          "A single ASCII apostrophe before a FormatJS rich-text opening tag is invalid. "
              + "Use two apostrophes, for example l''<privacyLink>, so the tag compiles.",
          e.getMessage());
    }
  }

  @Test
  public void testAcceptsOrdinaryApostrophes() {
    checker.check("", "Condizioni d'uso e dettagli del servizio.");
  }

  @Test
  public void testAcceptsAlreadyDoubledApostrophe() {
    checker.check("", "l''<privacyLink>Informativa sulla privacy</privacyLink>");
  }

  @Test
  public void testRegistersThroughGenericIntegrityCheckerType() {
    assertEquals(
        FormatJsRichTextIntegrityChecker.class.getName(),
        IntegrityCheckerType.FORMATJS_RICH_TEXT.getClassName());
  }
}
