package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class MessageFormatDoubleBracesIntegrityCheckerTest {

  @Test
  public void testCompilationCheckWorks() throws IntegrityCheckException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{numFiles, plural, one{# There is one file} other{There are # files}}}";
    String target = "{{numFiles, plural, one{Il y a un fichier} other{Il y a # fichiers}}}";

    checker.check(source, target);
  }

  @Test
  public void testCompilationCheckWorksWithMoreForms() throws IntegrityCheckException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{numFiles, plural, one{{# There is one file}} other{{There are # files}}}}";
    String target =
        "{{numFiles, plural, zero{{Il n'y a pas de fichier}} one{{Il y a un fichier}} other{{Il y a # fichiers}}}}";

    checker.check(source, target);
  }

  @Test
  public void testCompilationCheckFailsIfMissingRightBracket() throws IntegrityCheckException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{numFiles, plural, one{{# There is one file}} other{{There are # files}}}}";
    String target = "{{numFiles, plural, one{{Il y a un fichier}} other{{Il y a # fichiers}}}";

    try {
      checker.check(source, target);
      fail("MessageFormatIntegrityCheckerException must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Invalid pattern, there is more left than right braces in string.", e.getMessage());
    }
  }

  @Test
  public void testCompilationCheckFailsIfMissingLeftBracket() throws IntegrityCheckException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{numFiles, plural, one{{# There is one file}} other{{There are # files}}}}";
    String target = "{numFiles, plural, one{{Il y a un fichier}} other{{Il y a # fichiers}}}}";

    try {
      checker.check(source, target);
      fail("MessageFormatIntegrityCheckerException must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Invalid pattern, closing bracket found with no associated opening bracket.",
          e.getMessage());
    }
  }

  @Test
  public void testCompilationCheckFailsIfPluralElementGetsTranslated()
      throws IntegrityCheckException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{numFiles, plural, one{{# There is one file}} other{{There are # files}}}}";
    String target = "{{numFiles, plural, un{{Il y a un fichier}} autre{{Il y a # fichiers}}}}";

    try {
      checker.check(source, target);
      fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Invalid pattern - Missing 'other' keyword in plural pattern in \"{numFiles, plural, u ...\"",
          e.getMessage());
    }
  }

  @Test
  public void testNumberOfPlaceholder() throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "At {{1,time}} on {{1,date}}, there was {{2}} on planet {{0,number,integer}}.";
    String target = "At {{1,time}} on {{1,date}}, there was {{2}} on planet {{0,number,integer}}.";

    checker.check(source, target);
  }

  @Test
  public void testWrongNumberOfPlaceholder() throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "At {{1,time}} on {{1,date}}, there was {{2}} on planet {{0,number,integer}}.";
    String target = "At on {{1,date}}, there was {{2}} on planet {{0,number,integer}}.";

    try {
      checker.check(source, target);
      fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Number of top level placeholders in source (4) and target (3) is different",
          e.getMessage());
    }
  }

  @Test
  public void testDoubleAndSingleBracketsInUse() throws MessageFormatIntegrityCheckerException {
    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{username}} likes skydiving with {other_user}";
    String target = "{{username}} aime le saut en parachute avec {other_user}";

    checker.check(source, target);
  }

  @Test
  public void testSingleBraceMessageFormatPluralWithAdjacentClosingBraces()
      throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source =
        "You have {count, plural, one {one active item} other {active items}} "
            + "in {count, plural, one {one category} other {multiple categories}}: "
            + "{itemNames}.";
    String target =
        "There {count, plural, one {is one active item} other {are active items}} "
            + "in {count, plural, one {one category} other {multiple categories}}: "
            + "{itemNames}.";

    checker.check(source, target);
  }

  @Test
  public void testNamedParametersChanged() throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{username}} likes skydiving";
    String target = "{{utilisateur}} aime le saut en parachute";

    try {
      checker.check(source, target);
      fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Target placeholders do not match source. Found: [utilisateur], expected: [username]",
          e.getMessage());
    }
  }

  @Test
  public void testMustachePartialsAreAllowed() throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source =
        "<h2>Document Review Invitation</h2>\n"
            + "<p>Hello,</p>\n"
            + "<p>You have been invited to review <strong>{{ documentTitle }}</strong> for"
            + " {{ accountName }}. Select the button below to get started.</p>\n"
            + "{{> buttonHeader }}Review Document{{> buttonFooter }}\n"
            + "<p>Thank you,</p>\n"
            + "<p>The {{ accountName }} Team</p>";
    String target =
        "<h2>Invitation to review a document</h2>\n"
            + "<p>Hello,</p>\n"
            + "<p>You have been invited to review <strong>{{ documentTitle }}</strong> for"
            + " {{ accountName }}. Select the button below to start.</p>\n"
            + "{{> buttonHeader }}Review Document{{> buttonFooter }}\n"
            + "<p>Thank you,</p>\n"
            + "<p>The {{ accountName }} Team</p>";

    checker.check(source, target);
  }

  @Test
  public void testMustachePartialsChanged() throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{> buttonHeader }}View Report{{> buttonFooter }}";
    String target = "{{> buttonHeader }}View Report{{ buttonFooter }}";

    try {
      checker.check(source, target);
      fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Mustache partials do not match source. Found: [{{> buttonHeader }}], expected: [{{> buttonHeader }}, {{> buttonFooter }}]",
          e.getMessage());
    }
  }

  @Test
  public void testMustachePartialOrderChanged() throws MessageFormatIntegrityCheckerException {

    MessageFormatDoubleBracesIntegrityChecker checker =
        new MessageFormatDoubleBracesIntegrityChecker();
    String source = "{{> buttonHeader }}View Report{{> buttonFooter }}";
    String target = "{{> buttonFooter }}View Report{{> buttonHeader }}";

    try {
      checker.check(source, target);
      fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Mustache partials do not match source. Found: [{{> buttonFooter }}, {{> buttonHeader }}], expected: [{{> buttonHeader }}, {{> buttonFooter }}]",
          e.getMessage());
    }
  }
}
