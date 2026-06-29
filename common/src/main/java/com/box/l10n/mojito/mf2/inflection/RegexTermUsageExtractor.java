package com.box.l10n.mojito.mf2.inflection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Source-scanner-backed extractor for the experiment's `{$arg :term ...}` subset.
 *
 * <p>The class name is kept package-local for older tests and adapters, but the implementation no
 * longer relies on one expression-wide regex for spans.
 */
final class RegexTermUsageExtractor implements TermUsageExtractor {

  private static final Pattern OPTION_KEY_PATTERN = Pattern.compile("[A-Za-z_][\\w.-]*");

  RegexTermUsageExtractor() {}

  @Override
  public List<TermUsage> extract(String message) {
    Objects.requireNonNull(message, "message");
    List<TermUsage> usages = new ArrayList<>();
    for (TermUsageSourceScanner.ScannedTermUsage scannedUsage :
        TermUsageSourceScanner.scan(message)) {
      usages.add(
          new TermUsage(
              scannedUsage.argument(),
              parseOptions(scannedUsage.rawOptions()),
              scannedUsage.start(),
              scannedUsage.end()));
    }
    return List.copyOf(usages);
  }

  static Map<String, String> parseOptions(String rawOptions) {
    Map<String, String> options = new LinkedHashMap<>();
    for (String token : optionTokens(rawOptions)) {
      int equals = token.indexOf('=');
      if (equals < 0) {
        throw new IllegalArgumentException("Term option must use key=value syntax: " + token);
      }
      if (equals == 0) {
        throw new IllegalArgumentException("Term option key is required: " + token);
      }

      String key = token.substring(0, equals);
      if (!OPTION_KEY_PATTERN.matcher(key).matches()) {
        throw new IllegalArgumentException("Invalid term option key: " + key);
      }

      String value = stripQuotes(token.substring(equals + 1), key);
      if (value.isBlank()) {
        throw new IllegalArgumentException("Term option value must not be blank: " + key);
      }
      if (options.put(key, value) != null) {
        throw new IllegalArgumentException("Duplicate term option: " + key);
      }
    }
    return Collections.unmodifiableMap(options);
  }

  private static List<String> optionTokens(String rawOptions) {
    List<String> tokens = new ArrayList<>();
    int cursor = 0;
    while (cursor < rawOptions.length()) {
      while (cursor < rawOptions.length() && Character.isWhitespace(rawOptions.charAt(cursor))) {
        cursor++;
      }
      if (cursor >= rawOptions.length()) {
        break;
      }

      int tokenStart = cursor;
      Character quote = null;
      boolean escaped = false;
      while (cursor < rawOptions.length()) {
        char current = rawOptions.charAt(cursor);
        if (escaped) {
          escaped = false;
          cursor++;
          continue;
        }
        if (quote != null) {
          if (current == '\\') {
            escaped = true;
          } else if (current == quote) {
            quote = null;
          }
          cursor++;
          continue;
        }
        if (current == '"' || current == '|') {
          quote = current;
          cursor++;
        } else if (Character.isWhitespace(current)) {
          break;
        } else {
          cursor++;
        }
      }
      tokens.add(rawOptions.substring(tokenStart, cursor));
    }
    return tokens;
  }

  private static String stripQuotes(String value, String key) {
    boolean startsWithQuote = value.startsWith("\"") || value.startsWith("|");
    boolean endsWithQuote = value.endsWith("\"") || value.endsWith("|");
    if (startsWithQuote != endsWithQuote) {
      throw new IllegalArgumentException("Unbalanced quoted value for term option: " + key);
    }
    if (startsWithQuote && value.length() < 2) {
      throw new IllegalArgumentException("Unbalanced quoted value for term option: " + key);
    }
    if (startsWithQuote && value.charAt(0) != value.charAt(value.length() - 1)) {
      throw new IllegalArgumentException("Unbalanced quoted value for term option: " + key);
    }
    if (startsWithQuote) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
