package com.box.l10n.mojito.translationintegrity.formatjs;

import com.box.l10n.mojito.translationintegrity.formatjs.FormatJsElement.NumberSkeletonToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation-relevant port of {@code @formatjs/icu-skeleton-parser} 2.1.9, the exact dependency
 * used by {@code @formatjs/icu-messageformat-parser} 3.5.10.
 *
 * <p>The upstream parser intentionally accepts unknown number stems and unknown date/time text;
 * this class preserves that behavior instead of imposing a newer skeleton grammar.
 *
 * <p>Unlike the JavaScript 2.1.9 implementation, compiled regular expressions are used
 * deterministically. The validation port does not reproduce accidental cross-call {@code lastIndex}
 * state from upstream global regular expressions.
 */
final class FormatJsSkeletonParser {

  private static final Pattern DATE_TIME_PATTERN =
      Pattern.compile(
          "(?:[Eec]{1,6}|G{1,5}|[Qq]{1,5}|(?:[yYur]+|U{1,5})|[ML]{1,5}|d{1,2}|D{1,3}|F|[abB]{1,5}|[hkHK]{1,2}|w{1,2}|W|m{1,2}|s{1,2}|[zZOvVxX]{1,4})(?=([^']*'[^']*')*[^']*$)");
  private static final Pattern FRACTION_PRECISION =
      Pattern.compile("^\\.(?:(0+)(\\*)?|(#+)|(0+)(#+))$");
  private static final Pattern SIGNIFICANT_PRECISION = Pattern.compile("^(@+)?(\\+|#+)?[rs]?$");
  private static final Pattern INTEGER_WIDTH = Pattern.compile("(\\*)(0+)|(#+)(0+)|(0+)");
  private static final Pattern CONCISE_INTEGER_WIDTH = Pattern.compile("^(0+)$");
  private static final Pattern JAVASCRIPT_FLOAT_PREFIX =
      Pattern.compile("^[+-]?(?:Infinity|(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)");

  private FormatJsSkeletonParser() {}

  static List<NumberSkeletonToken> tokenizeNumberSkeleton(String skeleton) {
    if (skeleton.isEmpty()) {
      throw new NumberSkeletonTokenizationException("Number skeleton cannot be empty");
    }

    List<NumberSkeletonToken> tokens = new ArrayList<>();
    int tokenStart = -1;
    for (int i = 0; i <= skeleton.length(); i++) {
      boolean atEnd = i == skeleton.length();
      boolean whitespace = !atEnd && isSkeletonWhitespace(skeleton.charAt(i));
      if (!atEnd && !whitespace && tokenStart < 0) {
        tokenStart = i;
      }
      if ((atEnd || whitespace) && tokenStart >= 0) {
        tokens.add(parseNumberToken(skeleton.substring(tokenStart, i)));
        tokenStart = -1;
      }
    }
    return List.copyOf(tokens);
  }

  static Map<String, Object> parseNumberSkeleton(List<NumberSkeletonToken> tokens) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (NumberSkeletonToken token : tokens) {
      String stem = token.stem();
      List<String> options = token.options();
      switch (stem) {
        case "percent", "%" -> result.put("style", "percent");
        case "%x100" -> {
          result.put("style", "percent");
          result.put("scale", 100);
        }
        case "currency" -> {
          result.put("style", "currency");
          // Java null is the closest stable analogue to an own JavaScript property whose value
          // is undefined.
          result.put("currency", options.isEmpty() ? null : options.get(0));
        }
        case "group-off", ",_" -> result.put("useGrouping", false);
        case "precision-integer", "." -> result.put("maximumFractionDigits", 0);
        case "measure-unit", "unit" -> {
          if (options.isEmpty()) {
            throw new SkeletonSyntaxException("measure-unit requires an option");
          }
          result.put("style", "unit");
          result.put("unit", options.get(0).replaceFirst("^(.*?)-", ""));
        }
        case "compact-short", "K" -> {
          result.put("notation", "compact");
          result.put("compactDisplay", "short");
        }
        case "compact-long", "KK" -> {
          result.put("notation", "compact");
          result.put("compactDisplay", "long");
        }
        case "scientific" -> {
          result.put("notation", "scientific");
          options.forEach(option -> result.putAll(parseSign(option)));
        }
        case "engineering" -> {
          result.put("notation", "engineering");
          options.forEach(option -> result.putAll(parseSign(option)));
        }
        case "notation-simple" -> result.put("notation", "standard");
        case "unit-width-narrow" -> {
          result.put("currencyDisplay", "narrowSymbol");
          result.put("unitDisplay", "narrow");
        }
        case "unit-width-short" -> {
          result.put("currencyDisplay", "code");
          result.put("unitDisplay", "short");
        }
        case "unit-width-full-name" -> {
          result.put("currencyDisplay", "name");
          result.put("unitDisplay", "long");
        }
        case "unit-width-iso-code" -> result.put("currencyDisplay", "symbol");
        case "scale" ->
            result.put(
                "scale", options.isEmpty() ? Double.NaN : parseJavaScriptFloat(options.get(0)));
        case "rounding-mode-floor" -> result.put("roundingMode", "floor");
        case "rounding-mode-ceiling" -> result.put("roundingMode", "ceil");
        case "rounding-mode-down" -> result.put("roundingMode", "trunc");
        case "rounding-mode-up" -> result.put("roundingMode", "expand");
        case "rounding-mode-half-even" -> result.put("roundingMode", "halfEven");
        case "rounding-mode-half-down" -> result.put("roundingMode", "halfTrunc");
        case "rounding-mode-half-up" -> result.put("roundingMode", "halfExpand");
        case "integer-width" -> parseIntegerWidth(options, result);
        default -> parseConciseNumberStem(token, result);
      }
    }
    return result;
  }

  static Map<String, Object> parseDateTimeSkeleton(String skeleton) {
    Map<String, Object> result = new LinkedHashMap<>();
    Matcher matcher = DATE_TIME_PATTERN.matcher(skeleton);
    while (matcher.find()) {
      String match = matcher.group();
      int length = match.length();
      switch (match.charAt(0)) {
        case 'G' -> result.put("era", length == 4 ? "long" : length == 5 ? "narrow" : "short");
        case 'y' -> result.put("year", length == 2 ? "2-digit" : "numeric");
        case 'Y', 'u', 'U', 'r' ->
            throw new SkeletonSyntaxException(
                "`Y/u/U/r` (year) patterns are not supported, use `y` instead");
        case 'q', 'Q' ->
            throw new SkeletonSyntaxException("`q/Q` (quarter) patterns are not supported");
        case 'M', 'L' ->
            result.put(
                "month", List.of("numeric", "2-digit", "short", "long", "narrow").get(length - 1));
        case 'w', 'W' ->
            throw new SkeletonSyntaxException("`w/W` (week) patterns are not supported");
        case 'd' -> result.put("day", length == 1 ? "numeric" : "2-digit");
        case 'D', 'F', 'g' ->
            throw new SkeletonSyntaxException(
                "`D/F/g` (day) patterns are not supported, use `d` instead");
        case 'E' -> result.put("weekday", length == 4 ? "long" : length == 5 ? "narrow" : "short");
        case 'e', 'c' -> {
          if (length < 4) {
            char symbol = match.charAt(0);
            throw new SkeletonSyntaxException(
                "`"
                    + symbol
                    + ".."
                    + symbol
                    + symbol
                    + symbol
                    + "` (weekday) patterns are not supported");
          }
          result.put("weekday", List.of("short", "long", "narrow", "short").get(length - 4));
        }
        case 'a' -> result.put("hour12", true);
        case 'b', 'B' ->
            throw new SkeletonSyntaxException(
                "`b/B` (period) patterns are not supported, use `a` instead");
        case 'h' -> putHour(result, "h12", length);
        case 'H' -> putHour(result, "h23", length);
        case 'K' -> putHour(result, "h11", length);
        case 'k' -> putHour(result, "h24", length);
        case 'j', 'J', 'C' ->
            throw new SkeletonSyntaxException(
                "`j/J/C` (hour) patterns are not supported, use `h/H/K/k` instead");
        case 'm' -> result.put("minute", length == 1 ? "numeric" : "2-digit");
        case 's' -> result.put("second", length == 1 ? "numeric" : "2-digit");
        case 'S', 'A' ->
            throw new SkeletonSyntaxException(
                "`S/A` (second) patterns are not supported, use `s` instead");
        case 'z' -> result.put("timeZoneName", length < 4 ? "short" : "long");
        case 'Z', 'O', 'v', 'V', 'X', 'x' ->
            throw new SkeletonSyntaxException(
                "`Z/O/v/V/X/x` (timeZone) patterns are not supported, use `z` instead");
        default -> {
          // Kept for parity with the exhaustive upstream switch.
        }
      }
    }
    return result;
  }

  private static NumberSkeletonToken parseNumberToken(String token) {
    String[] pieces = token.split("/", -1);
    String stem = pieces[0];
    List<String> options = new ArrayList<>();
    for (int i = 1; i < pieces.length; i++) {
      if (pieces[i].isEmpty()) {
        throw new NumberSkeletonTokenizationException("Invalid number skeleton");
      }
      options.add(pieces[i]);
    }
    return new NumberSkeletonToken(stem, options);
  }

  private static boolean isSkeletonWhitespace(char value) {
    return (value >= '\t' && value <= '\r')
        || value == ' '
        || value == '\u0085'
        || value == '\u200e'
        || value == '\u200f'
        || value == '\u2028'
        || value == '\u2029';
  }

  private static void parseIntegerWidth(List<String> options, Map<String, Object> parsedOptions) {
    if (options.size() > 1) {
      throw new SkeletonSyntaxException("integer-width accepts at most one option");
    }
    if (options.isEmpty()) {
      throw new SkeletonSyntaxException("integer-width requires an option");
    }
    Matcher matcher = INTEGER_WIDTH.matcher(options.get(0));
    while (matcher.find()) {
      if (matcher.group(1) != null) {
        parsedOptions.put("minimumIntegerDigits", matcher.group(2).length());
      } else if (matcher.group(3) != null && matcher.group(4) != null) {
        throw new SkeletonSyntaxException("maximum integer digits are not supported");
      } else if (matcher.group(5) != null) {
        throw new SkeletonSyntaxException("exact integer digits are not supported");
      }
    }
  }

  private static void parseConciseNumberStem(
      NumberSkeletonToken token, Map<String, Object> result) {
    String stem = token.stem();
    if (CONCISE_INTEGER_WIDTH.matcher(stem).matches()) {
      result.put("minimumIntegerDigits", stem.length());
      return;
    }

    Matcher fraction = FRACTION_PRECISION.matcher(stem);
    if (fraction.matches()) {
      if (token.options().size() > 1) {
        throw new SkeletonSyntaxException("fraction precision accepts at most one option");
      }
      if (fraction.group(2) != null) {
        result.put("minimumFractionDigits", fraction.group(1).length());
      } else if (fraction.group(3) != null) {
        result.put("maximumFractionDigits", fraction.group(3).length());
      } else if (fraction.group(4) != null && fraction.group(5) != null) {
        result.put("minimumFractionDigits", fraction.group(4).length());
        result.put(
            "maximumFractionDigits", fraction.group(4).length() + fraction.group(5).length());
      } else {
        result.put("minimumFractionDigits", fraction.group(1).length());
        result.put("maximumFractionDigits", fraction.group(1).length());
      }
      if (!token.options().isEmpty()) {
        String option = token.options().get(0);
        if (option.equals("w")) {
          result.put("trailingZeroDisplay", "stripIfInteger");
        } else {
          result.putAll(parseSignificantPrecision(option));
        }
      }
      return;
    }

    if (SIGNIFICANT_PRECISION.matcher(stem).matches()) {
      result.putAll(parseSignificantPrecision(stem));
      return;
    }

    result.putAll(parseSign(stem));
    result.putAll(parseConciseScientificOrEngineering(stem));
  }

  private static Map<String, Object> parseSignificantPrecision(String value) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (value.endsWith("r")) {
      result.put("roundingPriority", "morePrecision");
    } else if (value.endsWith("s")) {
      result.put("roundingPriority", "lessPrecision");
    }

    Matcher matcher = SIGNIFICANT_PRECISION.matcher(value);
    if (!matcher.matches()) {
      return result;
    }
    String required = matcher.group(1);
    String optional = matcher.group(2);
    if (optional == null) {
      if (required == null) {
        throw new SkeletonSyntaxException("Malformed significant precision");
      }
      result.put("minimumSignificantDigits", required.length());
      result.put("maximumSignificantDigits", required.length());
    } else if (optional.equals("+")) {
      if (required == null) {
        throw new SkeletonSyntaxException("Malformed significant precision");
      }
      result.put("minimumSignificantDigits", required.length());
    } else if (required == null) {
      // 2.1.9 throws while reading the absent first group for concise "###".
      throw new SkeletonSyntaxException("Malformed significant precision");
    } else {
      result.put("minimumSignificantDigits", required.length());
      result.put("maximumSignificantDigits", required.length() + optional.length());
    }
    return result;
  }

  private static Map<String, Object> parseSign(String value) {
    Map<String, Object> result = new LinkedHashMap<>();
    switch (value) {
      case "sign-auto" -> result.put("signDisplay", "auto");
      case "sign-accounting", "()" -> result.put("currencySign", "accounting");
      case "sign-always", "+!" -> result.put("signDisplay", "always");
      case "sign-accounting-always", "()!" -> {
        result.put("signDisplay", "always");
        result.put("currencySign", "accounting");
      }
      case "sign-except-zero", "+?" -> result.put("signDisplay", "exceptZero");
      case "sign-accounting-except-zero", "()?" -> {
        result.put("signDisplay", "exceptZero");
        result.put("currencySign", "accounting");
      }
      case "sign-never", "+_" -> result.put("signDisplay", "never");
      default -> {
        // Unknown notation options are ignored by 2.1.9.
      }
    }
    return result;
  }

  private static Map<String, Object> parseConciseScientificOrEngineering(String value) {
    Map<String, Object> result = new LinkedHashMap<>();
    String remaining = value;
    if (remaining.startsWith("EE")) {
      result.put("notation", "engineering");
      remaining = remaining.substring(2);
    } else if (remaining.startsWith("E")) {
      result.put("notation", "scientific");
      remaining = remaining.substring(1);
    } else {
      return result;
    }

    if (remaining.startsWith("+!")) {
      result.put("signDisplay", "always");
      remaining = remaining.substring(2);
    } else if (remaining.startsWith("+?")) {
      result.put("signDisplay", "exceptZero");
      remaining = remaining.substring(2);
    }
    if (!CONCISE_INTEGER_WIDTH.matcher(remaining).matches()) {
      throw new SkeletonSyntaxException("Malformed concise eng/scientific notation");
    }
    result.put("minimumIntegerDigits", remaining.length());
    return result;
  }

  private static double parseJavaScriptFloat(String value) {
    Matcher matcher = JAVASCRIPT_FLOAT_PREFIX.matcher(trimEcmaScriptStart(value));
    return matcher.find() ? Double.parseDouble(matcher.group()) : Double.NaN;
  }

  private static String trimEcmaScriptStart(String value) {
    int index = 0;
    while (index < value.length()) {
      int current = value.codePointAt(index);
      if (!isEcmaScriptTrimWhitespace(current)) {
        break;
      }
      index += Character.charCount(current);
    }
    return value.substring(index);
  }

  private static boolean isEcmaScriptTrimWhitespace(int value) {
    return (value >= 0x09 && value <= 0x0D)
        || value == 0x20
        || value == 0xA0
        || value == 0x1680
        || (value >= 0x2000 && value <= 0x200A)
        || value == 0x2028
        || value == 0x2029
        || value == 0x202F
        || value == 0x205F
        || value == 0x3000
        || value == 0xFEFF;
  }

  private static void putHour(Map<String, Object> result, String cycle, int length) {
    result.put("hourCycle", cycle);
    result.put("hour", length == 1 ? "numeric" : "2-digit");
  }

  static final class SkeletonSyntaxException extends IllegalArgumentException {

    private SkeletonSyntaxException(String message) {
      super(message);
    }
  }

  static final class NumberSkeletonTokenizationException extends IllegalArgumentException {

    private NumberSkeletonTokenizationException(String message) {
      super(message);
    }
  }
}
