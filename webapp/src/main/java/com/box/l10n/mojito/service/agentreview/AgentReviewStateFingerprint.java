package com.box.l10n.mojito.service.agentreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Exact human-reviewed state; evidence is recorded from the locked save, never inferred later. */
public final class AgentReviewStateFingerprint {
  private AgentReviewStateFingerprint() {}

  /** Scope and concern identity are deliberately separate from the immutable reviewed state. */
  public static String finding(
      Long teamId, String reviewType, String state, String concernKey, String reason) {
    return of(
        teamId,
        null,
        reviewType(reviewType),
        state,
        null,
        concernKey == null || concernKey.isBlank()
            ? "reason:" + normalizeConcern(reason)
            : "key:" + normalizeConcern(concernKey),
        null,
        null);
  }

  public static String reviewType(String value) {
    return value == null || value.isBlank() ? "TRANSLATION_QUALITY" : value.trim();
  }

  private static String normalizeConcern(String value) {
    // Whitespace and Unicode spelling only: case and punctuation can distinguish actual defects.
    return value == null
        ? ""
        : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
            .strip()
            .replaceAll("(?U)\\s+", " ");
  }

  public static String of(
      Long textUnitId,
      Long localeId,
      String source,
      String sourceComment,
      Long variantId,
      String target,
      String status,
      Boolean included) {
    try {
      byte[] payload =
          new ObjectMapper()
              .writeValueAsBytes(
                  Arrays.asList(
                      textUnitId,
                      localeId,
                      source,
                      sourceComment,
                      variantId,
                      target,
                      status,
                      included));
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (Exception exception) {
      throw new IllegalStateException("Could not identify reviewed translation state", exception);
    }
  }
}
