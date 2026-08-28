package com.box.l10n.mojito.service.tm.search;

import java.util.Locale;
import java.util.regex.Pattern;

/** Detects message formats that need a format-specific translation editor. */
public final class MessageFormatDetector {

  public static final String MF2 = "MF2";

  private static final Pattern[] STRICT_MF2_DECLARATION_PATTERNS = {
    Pattern.compile("^\\s*\\.input\\s+\\{\\s*\\$[^\\s{}]+"),
    Pattern.compile("^\\s*\\.local\\s+\\$[^\\s=]+\\s*="),
    Pattern.compile("^\\s*\\.match(?:\\s+\\$[^\\s{}]+)+")
  };

  private MessageFormatDetector() {}

  public static String detect(String source) {
    return detect(source, null);
  }

  public static String detect(String source, String assetPath) {
    if (assetPath != null && assetPath.trim().toLowerCase(Locale.ROOT).endsWith(".mf2")) {
      return MF2;
    }
    if (source == null) {
      return null;
    }

    String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
    for (Pattern pattern : STRICT_MF2_DECLARATION_PATTERNS) {
      if (pattern.matcher(normalized).find()) {
        return MF2;
      }
    }
    return null;
  }

  public static String normalize(String messageFormat) {
    return messageFormat != null && MF2.equalsIgnoreCase(messageFormat.trim()) ? MF2 : null;
  }
}
