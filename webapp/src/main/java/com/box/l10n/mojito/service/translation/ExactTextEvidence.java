package com.box.l10n.mojito.service.translation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;

/** Bounded text evidence for semantic review, with secret-like payloads suppressed. */
public record ExactTextEvidence(
    String exactText,
    String preview,
    String sha256,
    long codePointLength,
    boolean truncated,
    boolean redacted,
    boolean requiresNativeReview) {

  public static final int PREVIEW_CODE_POINT_LIMIT = 256;

  private static final List<Pattern> SENSITIVE_PATTERNS =
      List.of(
          Pattern.compile("(?i)\\bBearer\\s+\\S+"),
          Pattern.compile(
              "(?i)[\\\"']?\\b(password|passwd|secret|token|api[_-]?key|account[_-]?key|client[_-]?secret|access[_-]?key|aws[_-]?secret[_-]?access[_-]?key|private[_-]?key|connection[_-]?string)\\b[\\\"']?\\s*[:=]\\s*(?:[\\\"'][^\\\"']+[\\\"']|[^\\s,;}\\]]+)"),
          Pattern.compile("(?i)\\b(?:proxy-)?authorization\\s*:\\s*\\S+"),
          Pattern.compile("(?i)\\b(?:cookie|set-cookie)\\s*:\\s*\\S+"),
          Pattern.compile(
              "(?i)\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{12,}|AKIA[A-Z0-9]{12,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})\\b"),
          Pattern.compile(
              "(?i)\\b(?:jdbc:[a-z0-9]+|mysql|mariadb|postgres(?:ql)?|mongodb(?:\\+srv)?|redis|rediss|amqp|amqps)://[^\\s]+"),
          Pattern.compile(
              "(?i)-----BEGIN [^-]{0,40}PRIVATE KEY-----.*?(?:-----END [^-]{0,40}PRIVATE KEY-----|$)",
              Pattern.DOTALL),
          Pattern.compile(
              "(?<![A-Za-z0-9_+/=-])(?=[A-Za-z0-9_+/=-]{32,})(?=[A-Za-z0-9_+/=-]*[A-Za-z])(?=[A-Za-z0-9_+/=-]*\\d)[A-Za-z0-9_+/=-]{32,}(?![A-Za-z0-9_+/=-])"),
          Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@"));

  public static ExactTextEvidence fromNullable(String value) {
    if (value == null) {
      return null;
    }
    String sha256 = DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    long codePointLength = value.codePointCount(0, value.length());
    int previewEnd =
        value.offsetByCodePoints(0, (int) Math.min(codePointLength, PREVIEW_CODE_POINT_LIMIT));
    return fromSummary(value.substring(0, previewEnd), sha256, codePointLength);
  }

  /** Builds evidence from a database-computed hash/length and a bounded prefix. */
  public static ExactTextEvidence fromSummary(
      String boundedPreview, String sha256, long codePointLength) {
    if (boundedPreview == null && sha256 == null) {
      return null;
    }
    if (sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
      throw new IllegalArgumentException("sha256 must be a 64-character hexadecimal digest");
    }
    if (codePointLength < 0) {
      throw new IllegalArgumentException("codePointLength must be non-negative");
    }
    String preview = boundedPreview == null ? "" : boundedPreview;
    int previewCodePointLength = preview.codePointCount(0, preview.length());
    if (previewCodePointLength > PREVIEW_CODE_POINT_LIMIT) {
      throw new IllegalArgumentException(
          "boundedPreview exceeds " + PREVIEW_CODE_POINT_LIMIT + " code points");
    }
    if (codePointLength < previewCodePointLength) {
      throw new IllegalArgumentException("codePointLength is shorter than boundedPreview");
    }
    boolean truncated = codePointLength > previewCodePointLength;
    boolean redacted =
        SENSITIVE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(preview).find());
    return new ExactTextEvidence(
        redacted || truncated ? null : preview,
        redacted || !truncated ? null : preview,
        sha256.toLowerCase(),
        codePointLength,
        truncated,
        redacted,
        redacted || truncated);
  }
}
