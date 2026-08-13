package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic XML plist regeneration checked against Apple's Foundation parser. */
final class AppleStringsdictWriter {

  private static final List<String> PLURAL_CATEGORIES =
      List.of("zero", "one", "two", "few", "many", "other");
  private static final Pattern ARGUMENT = Pattern.compile("\\{([\\p{L}\\p{N}\\p{M}\\p{So}_]+)\\}");
  private static final Comparator<String> UNICODE_SCALAR_ORDER =
      (left, right) -> {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
          int first = left.codePointAt(leftIndex);
          int second = right.codePointAt(rightIndex);
          if (first != second) {
            return Integer.compare(first, second);
          }
          leftIndex += Character.charCount(first);
          rightIndex += Character.charCount(second);
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
      };

  String write(LocalizationCatalog catalog) {
    if (!LocalizationFileFormat.APPLE_STRINGSDICT.id().equals(catalog.sourceFormat())) {
      throw invalid(
          "INVALID_SOURCE_FORMAT", "Apple stringsdict writer requires a stringsdict catalog");
    }
    TreeMap<String, LocalizationMessage> messages = new TreeMap<>(UNICODE_SCALAR_ORDER);
    messages.putAll(catalog.messages());
    StringBuilder output =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<dict>\n");
    for (Map.Entry<String, LocalizationMessage> entry : messages.entrySet()) {
      LocalizationMessage message = entry.getValue();
      if (message.description() != null) {
        throw invalid(
            "UNSUPPORTED_APPLE_STRINGSDICT_DESCRIPTION",
            "Apple stringsdict cannot preserve translator descriptions");
      }
      Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
      line(output, 1, "key", entry.getKey());
      open(output, 1, "dict");
      Object nestedDevices =
          metadata.containsKey("deviceMixedVariants")
              ? metadata.get("deviceMixedVariants")
              : metadata.containsKey("devicePluralVariants")
                  ? metadata.get("devicePluralVariants")
                  : metadata.get("deviceWidthVariants");
      if (nestedDevices instanceof Map<?, ?> branches) {
        line(output, 2, "key", "NSStringDeviceSpecificRuleType");
        plistValue(output, 2, branches);
        extras(output, metadata.get("applePlistExtras"), 2, false, List.of());
        close(output, 1, "dict");
        continue;
      }
      if (metadata.get("appleLocalizedFormat") instanceof String format) {
        line(output, 2, "key", "NSStringLocalizedFormatKey");
        line(output, 2, "string", format);
      }
      if (metadata.get("applePluralRules") instanceof Map<?, ?> rawRules) {
        List<String> variables = variables(metadata);
        if (variables.isEmpty()) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Missing Apple plural variables");
        }
        Map<String, Map<String, String>> canonical = canonicalVariants(message, variables);
        for (String variable : variables) {
          if (!(rawRules.get(variable) instanceof Map<?, ?> rule)
              || !(rule.get("variants") instanceof Map<?, ?> sourceVariants)) {
            throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Missing Apple plural definition");
          }
          Map<String, String> variants = canonical.get(variable);
          if (variants == null || !variants.containsKey("other")) {
            throw invalid("MISSING_OTHER_VARIANT", "Apple plural is missing other");
          }
          line(output, 2, "key", variable);
          open(output, 2, "dict");
          line(output, 3, "key", "NSStringFormatSpecTypeKey");
          line(output, 3, "string", "NSStringPluralRuleType");
          if (rule.get("valueType") instanceof String valueType) {
            line(output, 3, "key", "NSStringFormatValueTypeKey");
            line(output, 3, "string", valueType);
          }
          for (String category : PLURAL_CATEGORIES) {
            if (!variants.containsKey(category)) {
              continue;
            }
            String translated = variants.get(category);
            String source = sourceVariants.get(category) instanceof String value ? value : null;
            String nativeValue =
                source != null && normalized(source, variable).equals(translated)
                    ? source
                    : restore(translated, message, variable, category);
            line(output, 3, "key", category);
            line(output, 3, "string", nativeValue);
          }
          extras(output, rule.get("applePlistExtras"), 3, true, List.of());
          close(output, 2, "dict");
        }
      } else if (message.variants() != null) {
        throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Missing Apple plural definitions");
      }
      variation(output, metadata, "widthVariants", "NSStringVariableWidthRuleType", true);
      variation(output, metadata, "deviceVariants", "NSStringDeviceSpecificRuleType", false);
      extras(output, metadata.get("applePlistExtras"), 2, false, variables(metadata));
      if (!metadata.containsKey("applePluralRules")
          && !metadata.containsKey("widthVariants")
          && !metadata.containsKey("deviceVariants")) {
        throw invalid(
            "INVALID_APPLE_STRINGSDICT_METADATA",
            "Apple stringsdict requires a plural, width, or device rule");
      }
      close(output, 1, "dict");
    }
    output.append("</dict>\n</plist>\n");
    return output.toString();
  }

  private static List<String> variables(Map<String, Object> metadata) {
    if (metadata.get("pluralVariable") instanceof String variable) {
      return List.of(variable);
    }
    List<String> result = new ArrayList<>();
    if (metadata.get("pluralVariables") instanceof List<?> values) {
      for (Object value : values) {
        if (value instanceof String variable) {
          result.add(variable);
        }
      }
    }
    return result;
  }

  private static void extras(
      StringBuilder output, Object raw, int depth, boolean pluralRule, List<String> variables) {
    if (raw == null) {
      return;
    }
    if (!(raw instanceof Map<?, ?> fields)) {
      throw invalid(
          "INVALID_APPLE_STRINGSDICT_METADATA", "Apple plist extras must be dictionaries");
    }
    TreeMap<String, Object> sorted = new TreeMap<>(UNICODE_SCALAR_ORDER);
    for (Map.Entry<?, ?> field : fields.entrySet()) {
      if (!(field.getKey() instanceof String key)) {
        throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Apple plist keys must be strings");
      }
      boolean reserved =
          pluralRule
              ? "NSStringFormatSpecTypeKey".equals(key)
                  || "NSStringFormatValueTypeKey".equals(key)
                  || PLURAL_CATEGORIES.contains(key)
              : depth == 2
                  && ("NSStringLocalizedFormatKey".equals(key)
                      || "NSStringVariableWidthRuleType".equals(key)
                      || "NSStringDeviceSpecificRuleType".equals(key)
                      || variables.contains(key));
      if (reserved
          || pluralRule && field.getValue() instanceof String && !key.startsWith("NSString")) {
        throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple plist metadata key");
      }
      sorted.put(key, field.getValue());
    }
    for (Map.Entry<String, Object> field : sorted.entrySet()) {
      line(output, depth, "key", field.getKey());
      plistValue(output, depth, field.getValue());
    }
  }

  private static void plistValue(StringBuilder output, int depth, Object value) {
    switch (value) {
      case String text -> line(output, depth, "string", text);
      case Boolean flag ->
          output.append("  ".repeat(depth)).append(flag ? "<true/>\n" : "<false/>\n");
      case java.math.BigInteger number ->
          line(
              output,
              depth,
              "integer",
              AppleStringsdictParser.integer(number.toString()).toString());
      case Integer number -> line(output, depth, "integer", number.toString());
      case Long number -> line(output, depth, "integer", number.toString());
      case List<?> values -> {
        open(output, depth, "array");
        for (Object entry : values) {
          plistValue(output, depth + 1, entry);
        }
        close(output, depth, "array");
      }
      case Map<?, ?> dictionary -> {
        if (dictionary.containsKey("$applePlistType")) {
          taggedValue(output, depth, dictionary);
        } else {
          open(output, depth, "dict");
          extras(output, dictionary, depth + 1, false, List.of());
          close(output, depth, "dict");
        }
      }
      case null, default ->
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Unsupported Apple plist value");
    }
  }

  private static void taggedValue(StringBuilder output, int depth, Map<?, ?> tagged) {
    if (!(tagged.get("$applePlistType") instanceof String type)) {
      throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid tagged Apple plist value");
    }
    switch (type) {
      case "data" -> {
        if (tagged.size() != 2 || !(tagged.get("base64") instanceof String encoded)) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple plist data metadata");
        }
        try {
          String canonical =
              java.util.Base64.getEncoder()
                  .encodeToString(AppleStringsdictParser.data(encoded).bytes());
          if (!canonical.equals(encoded)) {
            throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Noncanonical Apple plist data");
          }
          line(output, depth, "data", encoded);
        } catch (LocalizationParseException exception) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple plist data metadata");
        }
      }
      case "date" -> {
        if (tagged.size() != 2 || !(tagged.get("value") instanceof String value)) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple plist date metadata");
        }
        try {
          if (!AppleStringsdictParser.date(value).value().toString().equals(value)) {
            throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Noncanonical Apple plist date");
          }
          line(output, depth, "date", value);
        } catch (LocalizationParseException exception) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple plist date metadata");
        }
      }
      case "real" -> {
        if (tagged.size() != 2
            || !(tagged.get("bits") instanceof String bits)
            || !bits.matches("[0-9a-f]{16}")) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple plist real metadata");
        }
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16));
        String formatted =
            Double.isNaN(value)
                ? "nan"
                : value == Double.POSITIVE_INFINITY
                    ? "infinity"
                    : value == Double.NEGATIVE_INFINITY ? "-infinity" : finiteReal(value);
        if (Double.isNaN(value)
            && Double.doubleToRawLongBits(value) != Double.doubleToRawLongBits(Double.NaN)) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Noncanonical Apple plist NaN");
        }
        line(output, depth, "real", formatted);
      }
      case "dictionary" -> {
        if (tagged.size() != 2 || !(tagged.get("entries") instanceof List<?> entries)) {
          throw invalid(
              "INVALID_APPLE_STRINGSDICT_METADATA", "Invalid escaped Apple plist dictionary");
        }
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (Object raw : entries) {
          if (!(raw instanceof Map<?, ?> entry)
              || entry.size() != 2
              || !(entry.get("key") instanceof String key)
              || !entry.containsKey("value")
              || fields.putIfAbsent(key, entry.get("value")) != null) {
            throw invalid(
                "INVALID_APPLE_STRINGSDICT_METADATA", "Invalid escaped Apple plist dictionary");
          }
        }
        open(output, depth, "dict");
        extras(output, fields, depth + 1, false, List.of());
        close(output, depth, "dict");
      }
      default ->
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Unknown Apple plist metadata tag");
    }
  }

  private static String finiteReal(double value) {
    if (value == 0.0) {
      return Double.doubleToRawLongBits(value) < 0
          ? "-0.00000000000000000e+0"
          : "0.00000000000000000e+0";
    }
    java.math.BigDecimal decimal =
        new java.math.BigDecimal(value)
            .round(new java.math.MathContext(18, java.math.RoundingMode.HALF_EVEN));
    int exponent = decimal.precision() - decimal.scale() - 1;
    String mantissa =
        decimal
            .movePointLeft(exponent)
            .setScale(17, java.math.RoundingMode.HALF_EVEN)
            .toPlainString();
    return mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
  }

  private static Map<String, Map<String, String>> canonicalVariants(
      LocalizationMessage message, List<String> variables) {
    Map<String, Map<String, String>> result = new HashMap<>();
    if (variables.size() == 1 && message.variants() != null) {
      result.put(variables.getFirst(), message.variants());
      return result;
    }
    for (String variable : variables) {
      String marker = "{" + variable + ", plural,";
      int start = message.defaultMessage().indexOf(marker);
      if (start < 0) {
        throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Plural is missing from ICU pattern");
      }
      Map<String, String> values = new HashMap<>();
      int cursor = start + marker.length();
      while (cursor < message.defaultMessage().length()) {
        while (cursor < message.defaultMessage().length()
            && Character.isWhitespace(message.defaultMessage().charAt(cursor))) {
          cursor++;
        }
        if (cursor >= message.defaultMessage().length()
            || message.defaultMessage().charAt(cursor) == '}') {
          break;
        }
        int selectorStart = cursor;
        while (cursor < message.defaultMessage().length()
            && !Character.isWhitespace(message.defaultMessage().charAt(cursor))) {
          cursor++;
        }
        String selector = message.defaultMessage().substring(selectorStart, cursor);
        while (cursor < message.defaultMessage().length()
            && Character.isWhitespace(message.defaultMessage().charAt(cursor))) {
          cursor++;
        }
        if (cursor >= message.defaultMessage().length()
            || message.defaultMessage().charAt(cursor++) != '{') {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid ICU plural branch");
        }
        int contentStart = cursor;
        int depth = 1;
        while (cursor < message.defaultMessage().length() && depth > 0) {
          char value = message.defaultMessage().charAt(cursor++);
          if (value == '{') {
            depth++;
          } else if (value == '}') {
            depth--;
          }
        }
        if (depth != 0) {
          throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Unclosed ICU plural branch");
        }
        values.put(selector, message.defaultMessage().substring(contentStart, cursor - 1));
      }
      result.put(variable, values);
    }
    return result;
  }

  private static String normalized(String source, String name) {
    String value =
        PlaceholderNormalizer.normalize(source, PlaceholderNormalizer.placeholders(), name);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.printfLineSeparators(source, name, null);
    return conversions.isEmpty()
        ? value
        : AppleStringsParser.withoutDisabledPrintfConversions(
            value, conversions, new ArrayList<>());
  }

  static String restore(
      String value, LocalizationMessage message, String selector, String category) {
    if (selector == null || category == null || message.metadata() == null) {
      return restore(value, message);
    }
    if (!(message.metadata().get("applePluralRules") instanceof Map<?, ?> definitions)
        || !(definitions.get(selector) instanceof Map<?, ?> definition)
        || !(definition.get("variants") instanceof Map<?, ?> variants)
        || !(variants.get(category) instanceof String original)) {
      throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Missing disabled plural conversion");
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (message.metadata().get("applePluralDisabledPrintfConversions") instanceof Map<?, ?> rules
        && rules.get(selector) instanceof Map<?, ?> categories
        && categories.get(category) instanceof List<?> conversions) {
      metadata.put("appleDisabledPrintfConversions", conversions);
    }
    LocalizationMessage scoped =
        LocalizationMessage.of(
            normalized(original, selector), null, null, message.placeholders(), metadata);
    return AppleStringsWriter.nativeValue(scoped, value);
  }

  static String restore(String value, LocalizationMessage message) {
    if (message.metadata() != null
        && message.metadata().get("appleDisabledPrintfConversions")
            instanceof List<?> conversions) {
      int sourceLength =
          message.defaultMessage().codePointCount(0, message.defaultMessage().length());
      int targetLength = value.codePointCount(0, value.length());
      List<Map<String, Object>> translatedConversions = new ArrayList<>();
      for (Object item : conversions) {
        Map<?, ?> occurrence = (Map<?, ?>) item;
        int originalPosition = ((Number) occurrence.get("position")).intValue();
        int translatedPosition =
            sourceLength == 0
                ? 0
                : (int)
                    (((long) originalPosition * targetLength + sourceLength / 2L) / sourceLength);
        if (message.placeholders() != null) {
          int closestAnchor = Integer.MAX_VALUE;
          for (LocalizationPlaceholder placeholder : message.placeholders()) {
            String argument = "{" + placeholder.name() + "}";
            int sourceArgument = message.defaultMessage().indexOf(argument);
            int targetArgument = value.indexOf(argument);
            if (sourceArgument < 0 || targetArgument < 0) {
              continue;
            }
            int sourceStart = message.defaultMessage().codePointCount(0, sourceArgument);
            int targetStart = value.codePointCount(0, targetArgument);
            int argumentLength = argument.codePointCount(0, argument.length());
            int sourceEnd = sourceStart + argumentLength;
            int distance;
            int candidate;
            if (originalPosition >= sourceEnd) {
              distance = originalPosition - sourceEnd;
              candidate = targetStart + argumentLength + distance;
            } else if (originalPosition <= sourceStart) {
              distance = sourceStart - originalPosition;
              candidate = targetStart - distance;
            } else {
              continue;
            }
            if (distance < closestAnchor) {
              closestAnchor = distance;
              translatedPosition = Math.max(0, Math.min(targetLength, candidate));
              if (distance == 0) {
                break;
              }
            }
          }
        }
        Map<String, Object> translated = new LinkedHashMap<>();
        translated.put("position", translatedPosition);
        translated.put("source", occurrence.get("source"));
        if (occurrence.containsKey("argumentPosition")) {
          translated.put("argumentPosition", occurrence.get("argumentPosition"));
        }
        translatedConversions.add(translated);
      }
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("appleDisabledPrintfConversions", translatedConversions);
      LocalizationMessage scoped =
          LocalizationMessage.of(value, null, null, message.placeholders(), metadata);
      return AppleStringsWriter.nativeValue(scoped, value);
    }
    if (value.indexOf('%') >= 0) {
      return AppleStringsWriter.nativeValue(message, value);
    }
    Map<String, List<LocalizationPlaceholder>> placeholders = new HashMap<>();
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        placeholders
            .computeIfAbsent(placeholder.name(), unused -> new ArrayList<>())
            .add(placeholder);
      }
    }
    Map<String, Integer> occurrences = new HashMap<>();
    Matcher matcher = ARGUMENT.matcher(value);
    StringBuilder result = new StringBuilder();
    int previous = 0;
    while (matcher.find()) {
      List<LocalizationPlaceholder> choices = placeholders.get(matcher.group(1));
      if (choices == null) {
        continue;
      }
      result.append(value, previous, matcher.start());
      int index = occurrences.getOrDefault(matcher.group(1), 0);
      occurrences.put(matcher.group(1), index + 1);
      result.append(choices.get(Math.min(index, choices.size() - 1)).source());
      previous = matcher.end();
    }
    return result.append(value, previous, value.length()).toString();
  }

  private static void variation(
      StringBuilder output,
      Map<String, Object> metadata,
      String source,
      String rule,
      boolean widths) {
    if (!(metadata.get(source) instanceof Map<?, ?> values)) {
      return;
    }
    Comparator<String> ordering =
        widths
            ? Comparator.<String>comparingInt(Integer::parseInt).thenComparing(UNICODE_SCALAR_ORDER)
            : UNICODE_SCALAR_ORDER;
    TreeMap<String, String> entries = new TreeMap<>(ordering);
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String value)) {
        throw invalid("INVALID_APPLE_STRINGSDICT_METADATA", "Invalid Apple variation value");
      }
      entries.put(name, value);
    }
    line(output, 2, "key", rule);
    open(output, 2, "dict");
    for (Map.Entry<String, String> entry : entries.entrySet()) {
      line(output, 3, "key", entry.getKey());
      line(output, 3, "string", entry.getValue());
    }
    close(output, 2, "dict");
  }

  private static void open(StringBuilder output, int depth, String tag) {
    output.append("  ".repeat(depth)).append('<').append(tag).append(">\n");
  }

  private static void close(StringBuilder output, int depth, String tag) {
    output.append("  ".repeat(depth)).append("</").append(tag).append(">\n");
  }

  private static void line(StringBuilder output, int depth, String tag, String value) {
    output
        .append("  ".repeat(depth))
        .append('<')
        .append(tag)
        .append('>')
        .append(escape(value))
        .append("</")
        .append(tag)
        .append(">\n");
  }

  private static String escape(String value) {
    StringBuilder output = new StringBuilder();
    for (int index = 0; index < value.length(); ) {
      int character = value.codePointAt(index);
      switch (character) {
        case '&' -> output.append("&amp;");
        case '<' -> output.append("&lt;");
        case '>' -> output.append("&gt;");
        case '\r' -> output.append("&#xD;");
        default -> {
          if ((character < 0x20 && character != '\n' && character != '\t')
              || (character & 0xffff) == 0xfffe
              || (character & 0xffff) == 0xffff) {
            throw invalid(
                "INVALID_APPLE_PLIST_TEXT", "Character is forbidden in XML property lists");
          }
          output.appendCodePoint(character);
        }
      }
      index += Character.charCount(character);
    }
    return output.toString();
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }
}
