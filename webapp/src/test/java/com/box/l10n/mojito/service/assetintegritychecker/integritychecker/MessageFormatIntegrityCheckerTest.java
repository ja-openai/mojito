package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableMap;
import com.ibm.icu.text.MessageFormat;
import org.junit.Test;

public class MessageFormatIntegrityCheckerTest {

  @Test
  public void testCompilationCheckWorks() throws IntegrityCheckException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{numFiles, plural, one{# There is one file} other{There are # files}}";
    String target = "{numFiles, plural, one{Il y a un fichier} other{Il y a # fichiers}}";

    checker.check(source, target);
  }

  @Test
  public void testCompilationCheckWorksWithMoreForms() throws IntegrityCheckException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{numFiles, plural, one{# There is one file} other{There are # files}}";
    String target =
        "{numFiles, plural, zero{Il n'y a pas de fichier} one{Il y a un fichier} other{Il y a # fichiers}}";

    checker.check(source, target);
  }

  @Test
  public void testEnglishPluralTranslationCheckWorks() throws IntegrityCheckException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
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
  public void testCompilationCheckFailsIfMissingBracket() throws IntegrityCheckException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{numFiles, plural, one{# There is one file} other{There are # files}}";
    String target = "{numFiles, plural, one{Il y a un fichier} other{Il y a # fichiers}";

    try {
      checker.check(source, target);
      fail("MessageFormatIntegrityCheckerException must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Invalid pattern - Bad plural pattern syntax: [at pattern index 18] \" one{Il y a un fichi ...\"",
          e.getMessage());
    }
  }

  @Test
  public void testCompilationCheckFailsIfPluralElementGetsTranslated()
      throws IntegrityCheckException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{numFiles, plural, one{# There is one file} other{There are # files}}";
    String target = "{numFiles, plural, un{Il y a un fichier} autre{Il y a # fichiers}}";

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

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "At {1,time} on {1,date}, there was {2} on planet {0,number,integer}.";
    String target = "At {1,time} on {1,date}, there was {2} on planet {0,number,integer}.";

    checker.check(source, target);
  }

  @Test
  public void testWrongNumberOfPlaceholder() throws MessageFormatIntegrityCheckerException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "At {1,time} on {1,date}, there was {2} on planet {0,number,integer}.";
    String target = "At on {1,date}, there was {2} on planet {0,number,integer}.";

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
  public void testNamedParametersChanged() throws MessageFormatIntegrityCheckerException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{username} likes skydiving";
    String target = "{utilisateur} aime le saut en parachute";

    try {
      checker.check(source, target);
      fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      assertEquals(
          "Target placeholders do not match source. Found: [utilisateur], expected: [username]",
          e.getMessage());
    }
  }

  /**
   * This test actually pass but ideally it should fail.
   *
   * <p>ICU doesn't provide public API to iterate on each format name. It only provides a method
   * that return argument names as a Set hence we can simply implements this check.
   *
   * @throws MessageFormatIntegrityCheckerException
   */
  @Test
  public void testNamedParametersChangedButWithDuplicates()
      throws MessageFormatIntegrityCheckerException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{1} {1} {2}";
    String target = "{1} {2} {2}";

    try {
      checker.check(source, target);
      // fail("Exception must be thrown");
    } catch (MessageFormatIntegrityCheckerException e) {
      // assertEquals("Different placeholder name in source and target", e.getMessage());
    }
  }

  /**
   * ICU4J accepts duplicate selectors in a select clause, while FormatJS rejects them.
   *
   * <p>This documents the current Mojito behavior so the gap is explicit in tests.
   */
  @Test
  public void testDuplicateSelectSelectorsAreAccepted()
      throws MessageFormatIntegrityCheckerException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "{itemType, select, primary {Primary} secondary {Secondary} other {Unknown}}";
    String target =
        "{itemType, select, primary {Principal} secondary {Secondaire} secondary {Alt} other {Inconnu}}";

    checker.check(source, target);
  }

  /**
   * ICU4J also accepts duplicate selectors in a plural clause, while FormatJS rejects them with
   * DUPLICATE_PLURAL_ARGUMENT_SELECTOR.
   *
   * <p>This documents the current backend gap with neutral strings so we can fix the checker
   * intentionally.
   */
  @Test
  public void testDuplicatePluralSelectorsAreAccepted()
      throws MessageFormatIntegrityCheckerException {

    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source =
        "Handled {itemCount, plural, one {# request} other {# requests}} in {durationMinutes} min";
    String target =
        "Processed {itemCount, plural, one {# record} few {# records} other {# records} other {# record}} in {durationMinutes} min";

    checker.check(source, target);
  }

  @Test(expected = IntegrityCheckException.class)
  public void testQuoteCurlyEscaping() throws MessageFormatIntegrityCheckerException {
    // ' with character are rendered by if it is a special character like {, it will escape it ....
    MessageFormatIntegrityChecker checker = new MessageFormatIntegrityChecker();
    String source = "This is a \"{placeholder}\"";
    String target = "C'est un '{placeholder}'";
    checker.check(source, target);

    MessageFormat messageFormat = new MessageFormat(target);
    String format = messageFormat.format(ImmutableMap.of("placeholder", "stuff"));
    assertEquals("C'est un {placeholder}", format);
  }
}
