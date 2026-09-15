package com.box.l10n.mojito.service.review.feedback;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReviewEditDiffTest {
  @Test
  public void separatesCosmeticChangesAndPreservesRawChanges() {
    assertEquals("UNCHANGED", ReviewEditDiff.compare("hello", "hello").category());
    var spaces = ReviewEditDiff.compare("hello world", " hello\u00a0world ");
    assertTrue(spaces.rawChanged());
    assertFalse(spaces.normalizedChanged());
    assertFalse(spaces.material());
    assertEquals("QUOTE_STYLE", ReviewEditDiff.compare("Say “hello”", "Say „hello“").category());
    assertEquals("CASING", ReviewEditDiff.compare("Hello", "hello").category());
    assertEquals("PUNCTUATION", ReviewEditDiff.compare("Hello!", "Hello.").category());
  }

  @Test
  public void preservesProtectedDifferencesAndAvoidsSemanticGuessing() {
    var placeholder = ReviewEditDiff.compare("Hello {name}", "Hello {user}");
    assertTrue(placeholder.protectedChanges().contains("placeholders"));
    assertEquals("PROTECTED_TOKEN_CHANGE", placeholder.category());
    assertEquals("PROTECTED_TOKEN_CHANGE", ReviewEditDiff.compare("Pay 10.5", "Pay 15").category());
    assertEquals("UNKNOWN_MATERIAL_EDIT", ReviewEditDiff.compare("Enable", "Disable").category());
    assertEquals(
        "NEEDS_REVIEW", ReviewEditDiff.compare("Enable", "Disable").instanceSignificance());
    assertEquals("UNASSESSED", placeholder.patternSignificance());
  }

  @Test
  public void countsTokensAndBoundsQuadraticWork() {
    var edit = ReviewEditDiff.compare("Save the file", "Store the document");
    assertEquals(2, edit.removals());
    assertEquals(2, edit.additions());
    assertEquals(2, edit.replacements());
    var huge = ReviewEditDiff.compare("hello ".repeat(1000), "world ".repeat(1000));
    assertEquals("bounded_changed_span", huge.tokenAlgorithm());
  }

  @Test
  public void groupsQuoteDirectionsAcrossDifferentSourcesWithoutConflatingTheReverse() {
    String a = "Say \"hello\"", b = "Say „hello“", c = "Other \"text\"", d = "Other „text“";
    assertEquals(
        ReviewEditDiff.transform(ReviewEditDiff.compare(a, b), a, b),
        ReviewEditDiff.transform(ReviewEditDiff.compare(c, d), c, d));
    assertNotEquals(
        ReviewEditDiff.transform(ReviewEditDiff.compare(a, b), a, b),
        ReviewEditDiff.transform(ReviewEditDiff.compare(b, a), b, a));
    assertEquals("NORMALIZATION", ReviewEditDiff.compare("cafe\u0301", "café").category());
  }

  @Test
  public void nullAndEmptyHaveDistinctIdentity() {
    assertNotEquals(ReviewFeedbackCaptureService.hash(null), ReviewFeedbackCaptureService.hash(""));
    assertTrue(ReviewEditDiff.compare(null, "").rawChanged());
  }
}
