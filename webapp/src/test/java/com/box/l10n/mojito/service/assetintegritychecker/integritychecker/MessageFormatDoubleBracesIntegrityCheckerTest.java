package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import org.junit.Test;

public class MessageFormatDoubleBracesIntegrityCheckerTest {

  private final MessageFormatDoubleBracesIntegrityChecker checker =
      new MessageFormatDoubleBracesIntegrityChecker();

  @Test
  public void acceptsMixedTemplateVariablesWithoutChangingTheirContents() {
    checker.check(
        "Request {{type}}: {{{ verifyUrl }}}. Cancel: {{{ noVerifyUrl }}}.",
        "Annuler : {{{noVerifyUrl}}}. Demande {{ type }} : {{{verifyUrl}}}.");
  }

  @Test
  public void acceptsRepeatedTripleVariablesAndSurroundingApostrophes() {
    checker.check(
        "{{{url}}} and <a href='{{{ url }}}'>{{title}}</a>",
        "<a href='{{{url}}}'>{{title}}</a> et {{{ url }}}");
  }

  @Test
  public void rejectsChangedTripleVariableNamesCountsAndBraceWidths() {
    String source = "{{{first}}} {{{first}}} {{{second}}} {{name}}";
    for (String target :
        new String[] {
          "{{{renamed}}} {{{first}}} {{{second}}} {{name}}",
          "{{{first}}} {{{second}}} {{name}}",
          "{{{first}}} {{{first}}} {{{first}}} {{{second}}} {{name}}",
          "{{{first}}} {{{second}}} {{{second}}} {{name}}",
          "{{first}} {{first}} {{{second}}} {{name}}",
          "{first} {first} {{{second}}} {{name}}",
          "{{{first}}} {{{first}}} {{{second}}} {{{name}}}",
          "{{{first}}} {{{first}}} {{{second}}} {{name}} {{{extra}}}"
        }) {
      assertThrows(
          target,
          MessageFormatIntegrityCheckerException.class,
          () -> checker.check(source, target));
    }
  }

  @Test
  public void stillChecksSingleAndDoubleBraceArgumentsAlongsideTripleVariables() {
    String source = "{{{url}}} {{name}} {count}";
    for (String target :
        new String[] {
          "{{{url}}} {{otherName}} {count}",
          "{{{url}}} {{name}} {otherCount}",
          "{{{url}}} {count}",
          "{{{url}}} {{name}}"
        }) {
      assertThrows(
          target,
          MessageFormatIntegrityCheckerException.class,
          () -> checker.check(source, target));
    }
  }

  @Test
  public void keepsPartialValidationAlongsideTripleVariables() {
    String source = "{{> header}} {{{url}}} {{> footer}}";
    checker.check(source, "{{> header }}Lien : {{{ url }}}{{> footer }}");
    for (String target :
        new String[] {
          "{{> footer}} {{{url}}} {{> header}}",
          "{{> header}} {{{url}}} {{footer}}",
          "{{> header}} {{{url}}} {{> renamed}}"
        }) {
      assertThrows(
          target,
          MessageFormatIntegrityCheckerException.class,
          () -> checker.check(source, target));
    }
  }

  @Test
  public void keepsIcuPluralValidationAlongsideTripleVariables() {
    String source = "{count, plural, one{One file} other{# files}} {{{url}}}";
    checker.check(source, "{count, plural, one{Un fichier} other{# fichiers}} {{{ url }}}");
    assertThrows(
        MessageFormatIntegrityCheckerException.class,
        () -> checker.check(source, "{count, plural, one{Un fichier}} {{{url}}}"));
    assertThrows(
        MessageFormatIntegrityCheckerException.class,
        () ->
            checker.check(
                source, "{renamed, plural, one{Un fichier} other{# fichiers}} {{{url}}}"));
  }

  @Test
  public void doesNotMaskNestedIcuBracesWhenTopLevelTripleVariablesActivateFallback() {
    String source = "{n,plural,one{{{first}}} other{{{second}}}} at {{{url}}}";
    checker.check(source, "À {{{ url }}} : {n,plural,one{{{first}}} other{{{second}}}}");
    assertThrows(
        MessageFormatIntegrityCheckerException.class,
        () -> checker.check(source, "{n,plural,one{{{first}}} autre{{{second}}}} {{{url}}}"));
  }

  @Test
  public void preservesLegacyNestedIcuAndQuotedLiteralInterpretations() {
    for (String source :
        new String[] {
          "{n, plural, other{{{value}}}}",
          "{{n, plural, other{{{value}}}}}",
          "{n,plural,one{{{first}}} other{{{second}}}}"
        }) {
      checker.check(source, source);
    }
    checker.check("'{{{literal}}}'", "'{{{texte}}}'");
    checker.check("<a href='{{{url}}}'>Link</a>", "<a href='{{{url}}}'>Lien</a>");
  }

  @Test
  public void neverReinterpretsARejectedTargetForAnExistingValidSource() {
    for (String[] pair :
        new String[][] {
          {"{{name}}", "{{{name}}}"},
          {"{name}", "{{{name}}}"},
          {"{n,plural,one{{{first}}} other{{{second}}}}", "{n,plural,one{{{first}}}}"}
        }) {
      assertThrows(
          MessageFormatIntegrityCheckerException.class, () -> checker.check(pair[0], pair[1]));
    }
  }

  @Test
  public void rejectsMalformedAndUnsupportedTripleVariables() {
    for (String malformed :
        new String[] {
          "{{{url}}",
          "{{url}}}",
          "{{{{url}}}}",
          "{{{}}}",
          "{{{two words}}}",
          "{{{url,number}}}",
          "{{{user.url}}}",
          "{{{> partial}}}",
          "{{{#section}}}"
        }) {
      assertThrows(
          malformed,
          MessageFormatIntegrityCheckerException.class,
          () -> checker.check("{{{url}}}", malformed));
      // A valid top-level variable must not hide another malformed source fragment.
      String malformedSource = "{{{valid}}} " + malformed;
      assertThrows(
          malformedSource,
          MessageFormatIntegrityCheckerException.class,
          () -> checker.check(malformedSource, malformedSource));
    }
  }

  @Test
  public void quotedMalformedTripleVariablesCannotBypassTemplateValidation() {
    for (String malformed :
        new String[] {"{{{}}}", "{{{user.url}}}", "{{{{url}}}}", "{{{extra} }}"}) {
      assertThrows(
          malformed,
          MessageFormatIntegrityCheckerException.class,
          () -> checker.check("{{{url}}}", "{{{url}}} '" + malformed + "'"));
    }
  }

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
