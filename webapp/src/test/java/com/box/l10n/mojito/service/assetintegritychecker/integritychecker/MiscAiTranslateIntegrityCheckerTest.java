package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.jupiter.api.Assertions.*;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

public class MiscAiTranslateIntegrityCheckerTest {

  MiscAiTranslateIntegrityChecker checker = new MiscAiTranslateIntegrityChecker();

  @Test
  void testHashtagValidCases() {
    assertDoesNotThrow(() -> checker.checkSingleHashtag("#hello", "#hola"));
    assertDoesNotThrow(() -> checker.checkSingleHashtag("#welcome", "#bienvenido"));
    assertDoesNotThrow(() -> checker.checkSingleHashtag("#goodmorning", "#gutenmorgen"));
    assertDoesNotThrow(() -> checker.checkSingleHashtag("#bonjour-2024", "#bonjour-2024"));
    assertDoesNotThrow(() -> checker.checkSingleHashtag("#こんにちは", "#おはよう"));
    assertDoesNotThrow(() -> checker.checkSingleHashtag("Hello world", "#hola"));
    assertDoesNotThrow(
        () ->
            checker.checkSingleHashtag(
                "#hello-world", Normalizer.normalize("#हेलो-वर्ल्ड", Normalizer.Form.NFC)));
  }

  @Test
  void testHashtagInvalidCases() {
    IntegrityCheckException ex;

    ex =
        assertThrows(
            IntegrityCheckException.class,
            () -> checker.checkSingleHashtag("#hello", "hola")); // Missing #
    assertTrue(
        ex.getMessage()
            .contains("Source is a single hashtag, but the target is not a valid hashtag"));

    ex =
        assertThrows(
            IntegrityCheckException.class,
            () -> checker.checkSingleHashtag("#hello", "#hola mundo")); // Space
    assertTrue(
        ex.getMessage()
            .contains("Source is a single hashtag, but the target is not a valid hashtag"));

    ex =
        assertThrows(
            IntegrityCheckException.class,
            () -> checker.checkSingleHashtag("#hello", "#")); // Too short
    assertTrue(
        ex.getMessage()
            .contains("Source is a single hashtag, but the target is not a valid hashtag"));
  }

  @Test
  void testNoCheckIfSourceNotHashtag() {
    assertDoesNotThrow(() -> checker.checkSingleHashtag("NotAHashtag", "randomoutput"));
    assertDoesNotThrow(() -> checker.checkSingleHashtag("Section for debugger", "randomoutput"));
  }

  @Test
  void testUnexpectedAiArtifactRejectedWhenMissingFromSource() {
    IntegrityCheckException ex =
        assertThrows(
            IntegrityCheckException.class,
            () ->
                checker.check(
                    "Open Settings to continue.",
                    "Ouvrez Settings pour continuer. output_json_schema"));

    assertTrue(ex.getMessage().contains("output_json_schema"));
    assertTrue(ex.getMessage().contains("not present in the source"));
  }

  @Test
  void testUnexpectedAiArtifactAllowedWhenPresentInSource() {
    assertDoesNotThrow(
        () ->
            checker.check(
                "Use output_json_schema for this config value.",
                "Utilisez output_json_schema pour cette valeur de configuration."));
  }

  @Test
  void testUnexpectedAiArtifactDetectionIsCaseInsensitive() {
    assertThrows(
        IntegrityCheckException.class,
        () ->
            checker.check(
                "Open Settings to continue.",
                "Ouvrez Settings pour continuer. Output_JSON_Schema"));
  }
}
