package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jyi
 */
public class WhitespaceIntegrityCheckerTest {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(WhitespaceIntegrityCheckerTest.class);

  WhitespaceIntegrityChecker checker = new WhitespaceIntegrityChecker();

  @Test
  public void testWhitespaceIntegrityCheckerWorksWithNoLeadingTrailingWhitespaces() {
    String source = "There are %1 files and %2 folders";
    String target = "Il y a %1 fichiers et %2 dossiers";
    checker.check(source, target);
  }

  @Test
  public void testWhitespaceIntegrityCheckerWorksWithLeadingTrailingWhitespaces() {
    String source = " There are %1 files and %2 folders\n";
    String target = " Il y a %1 fichiers et %2 dossiers\n";
    checker.check(source, target);
  }

  @Test
  public void testWhitespaceIntegrityCheckerWorksWithMultipleLeadingTrailingWhitespaces() {
    String source = "\t  There are %1 files and %2 folders  \n";
    String target = "\t  Il y a %1 fichiers et %2 dossiers  \n";
    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testWhitespaceIntegrityCheckerWorksWithDifferentLeadingTrailingWhitespaces1() {
    String source = " There are %1 files and %2 folders \n";
    String target = "  Il y a %1 fichiers et %2 dossiers\n";
    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testWhitespaceIntegrityCheckerWorksWithDifferentLeadingTrailingWhitespaces2() {
    String source = "\nThere are %1 files and %2 folders ";
    String target = " Il y a %1 fichiers et %2 dossiers\n";
    checker.check(source, target);
  }

  @Test
  public void testLeadingWhitespaceCheckWorks() {
    String source = "\n There are %1 files and %2 folders";
    String target = "\n Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test
  public void testLeadingWhitespaceCheckWorksWithoutLeadingWhitespaces() {
    String source = "There are %1 files and %2 folders";
    String target = "Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testLeadingWhitespaceCheckCatchesRemovedNewline() {
    String source = "\nThere are %1 files and %2 folders";
    String target = "Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testLeadingWhitespaceCheckCatchesRemovedSpace() {
    String source = " There are %1 files and %2 folders";
    String target = "Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testLeadingWhitespaceCheckCatchesDifferentLeadingWhitespaces() {
    String source = " \nThere are %1 files and %2 folders";
    String target = "\n Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test
  public void testTrailingWhitespaceCheckWorks() {
    String source = "There are %1 files and %2 folders \n";
    String target = "Il y a %1 fichiers et %2 dossiers \n";

    checker.check(source, target);
  }

  @Test
  public void testTrailingWhitespaceCheckWorksWithoutTrailingWhitespaces() {
    String source = "There are %1 files and %2 folders";
    String target = "Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testTrailingWhitespaceCheckCatchesRemovedNewline() {
    String source = "There are %1 files and %2 folders\n";
    String target = "Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testTrailingWhitespaceCheckCatchesRemovedSpace() {
    String source = "There are %1 files and %2 folders ";
    String target = "Il y a %1 fichiers et %2 dossiers";

    checker.check(source, target);
  }

  @Test(expected = WhitespaceIntegrityCheckerException.class)
  public void testTrailingWhitespaceCheckCatchesDifferentTrailingWhitespaces() {
    String source = "There are %1 files and %2 folders \n";
    String target = "Il y a %1 fichiers et %2 dossiers\n ";

    checker.check(source, target);
  }

  @Test
  public void rejectsAddedBoundaryWhitespace() {
    for (String target : new String[] {" Bonjour", "Bonjour ", "\nBonjour", "Bonjour\n"}) {
      assertThrows(WhitespaceIntegrityCheckerException.class, () -> checker.check("Hello", target));
    }
  }

  @Test
  public void rejectsBoundaryChangesInMultilineMessages() {
    for (String target :
        new String[] {
          " Bonjour\nMonde", "Bonjour\nMonde ", "\nBonjour\nMonde", "Bonjour\nMonde\n"
        }) {
      assertThrows(
          WhitespaceIntegrityCheckerException.class, () -> checker.check("Hello\nWorld", target));
    }
  }

  @Test
  public void acceptsMatchingBoundariesRegardlessOfInternalLineBreaks() {
    checker.check(" Hello\nWorld ", " Bonjour Monde ");
    checker.check(" Hello World ", " Bonjour\nMonde ");
    checker.check("\nHello\nWorld\n", "\nBonjour Monde\n");
  }

  @Test
  public void checksUnicodeWhitespaceAtBothBoundaries() {
    for (String whitespace : new String[] {"\u00a0", "\u202f", "\u2007", "\u3000", "\u0085"}) {
      checker.check(whitespace + "Hello" + whitespace, whitespace + "Bonjour" + whitespace);
      assertThrows(
          WhitespaceIntegrityCheckerException.class,
          () -> checker.check("Hello", whitespace + "Bonjour"));
      assertThrows(
          WhitespaceIntegrityCheckerException.class,
          () -> checker.check("Hello", "Bonjour" + whitespace));
      assertThrows(
          WhitespaceIntegrityCheckerException.class,
          () -> checker.check(whitespace + "Hello", "Bonjour"));
      assertThrows(
          WhitespaceIntegrityCheckerException.class,
          () -> checker.check("Hello" + whitespace, "Bonjour"));
    }
  }

  @Test
  public void preservesExactWhitespaceIdentity() {
    assertThrows(
        WhitespaceIntegrityCheckerException.class, () -> checker.check(" Hello", "\u00a0Bonjour"));
    assertThrows(
        WhitespaceIntegrityCheckerException.class, () -> checker.check("Hello\r\n", "Bonjour\n"));
  }

  @Test
  public void handlesEmptyAndWhitespaceOnlyMessages() {
    checker.check("", "");
    checker.check("", "Bonjour");
    checker.check("Hello", "");
    checker.check(" \t", " \t");
    for (String target : new String[] {" ", "\n", "\u00a0"}) {
      assertThrows(WhitespaceIntegrityCheckerException.class, () -> checker.check("", target));
    }
    assertThrows(WhitespaceIntegrityCheckerException.class, () -> checker.check(" \t", "\t "));
  }

  @Test
  public void leavesInternalSpacingAndMessageSyntaxUnvalidated() {
    checker.check("Hello world", "Bonjour\u00a0monde");
    checker.check("Hello world", "Bonjour  monde");
    checker.check("Hello", "Bonjour\nmonde");
    checker.check("Hello %@", "Bonjour %@");
    checker.check("Hello {name}", "Bonjour {unfinished");
  }
}
