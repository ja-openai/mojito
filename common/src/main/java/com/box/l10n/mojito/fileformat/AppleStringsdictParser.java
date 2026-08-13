package com.box.l10n.mojito.fileformat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class AppleStringsdictParser {

  private static final List<String> PLURAL_CATEGORIES =
      List.of("zero", "one", "two", "few", "many", "other");
  private static final Pattern PLURAL_MARKER = Pattern.compile("%(?:(\\d+)\\$)?#@([^@]*)@");
  private static final Pattern PLURAL_NAME = Pattern.compile("[A-Za-z0-9_]+");
  private static final BigInteger MIN_INTEGER = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger MAX_INTEGER =
      BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

  LocalizationCatalog parse(String source) {
    Document document = SecureXmlParser.parseApplePlist(source);
    rejectSelfClosingData(source);
    Element plist = document.getDocumentElement();
    if (!"plist".equals(plist.getTagName())) {
      throw invalid("Expected an Apple plist document");
    }
    Element dictionary = firstElement(plist);
    if (dictionary == null || !"dict".equals(dictionary.getTagName())) {
      throw invalid("Expected a top-level Apple stringsdict dictionary");
    }
    return parse(dictionary(dictionary));
  }

  LocalizationCatalog parse(Map<String, Object> entries) {
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.APPLE_STRINGSDICT);
    for (Map.Entry<String, Object> entry : entries.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?> rawMessage)) {
        throw invalid("Strings dictionary entry must be a dictionary: " + entry.getKey());
      }
      parseMessage(catalog, entry.getKey(), castDictionary(rawMessage));
    }
    return catalog;
  }

  private void parseMessage(LocalizationCatalog catalog, String id, Map<String, Object> message) {
    Map<String, Object> deviceRules = deviceRules(message);
    if (!deviceRules.isEmpty()) {
      parseDeviceVariationMessage(catalog, id, deviceRules);
      return;
    }
    Object rawPattern = message.get("NSStringLocalizedFormatKey");
    Map<String, String> widths = variationValues(message, "NSStringVariableWidthRuleType", true);
    Map<String, String> devices = variationValues(message, "NSStringDeviceSpecificRuleType", false);
    if (rawPattern != null && !(rawPattern instanceof String)) {
      throw invalid("Apple localized format must be a string: " + id);
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    String widestWidthKey = widths.isEmpty() ? null : widestWidthKey(widths);
    if (!widths.isEmpty()) {
      metadata.put("widthVariants", widths);
      int defaultWidth = Integer.parseInt(widestWidthKey);
      metadata.put("defaultWidth", defaultWidth);
      if (!Integer.toString(defaultWidth).equals(widestWidthKey)) {
        metadata.put("defaultWidthKey", widestWidthKey);
      }
    }
    if (!devices.isEmpty()) {
      metadata.put("deviceVariants", devices);
      metadata.put("defaultDevice", defaultDevice(devices));
    }
    String pattern;
    if (rawPattern instanceof String value) {
      pattern = value;
      metadata.put("appleLocalizedFormat", value);
    } else if (!devices.isEmpty()) {
      pattern = devices.get(defaultDevice(devices));
    } else if (!widths.isEmpty()) {
      pattern = widths.get(widestWidthKey);
    } else {
      throw invalid("Apple stringsdict entry has no plural, width, or device rule: " + id);
    }
    List<String> variables = new ArrayList<>();
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    Map<String, String> singleVariants = null;
    Map<String, Map<String, Object>> pluralRules = new LinkedHashMap<>();
    Map<String, Map<String, List<Map<String, Object>>>> disabledConversions = new LinkedHashMap<>();
    Map<String, Integer> positions = pluralPositions(pattern, message);
    Map<String, String> expansions = new LinkedHashMap<>();
    String valueType = null;
    for (Map.Entry<String, Object> entry : message.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?> rawVariable)) {
        continue;
      }
      Map<String, Object> variable = castDictionary(rawVariable);
      if (!"NSStringPluralRuleType".equals(variable.get("NSStringFormatSpecTypeKey"))) {
        continue;
      }
      String name = entry.getKey();
      if (!positions.containsKey(name)) {
        throw invalid("Apple stringsdict plural is not referenced: " + name);
      }
      variables.add(name);
      Integer position = positions.get(name);
      String declaredType =
          variable.get("NSStringFormatValueTypeKey") instanceof String type ? type : null;
      if (variable.containsKey("NSStringFormatValueTypeKey") && declaredType == null) {
        throw invalid("Apple stringsdict plural value type must be a string: " + name);
      }
      if (declaredType != null) {
        List<LocalizationPlaceholder> typed = PlaceholderNormalizer.placeholders();
        PlaceholderNormalizer.normalize("%" + declaredType, typed, name);
        if (typed.size() != 1 || !numeric(typed.getFirst())) {
          throw invalid("Apple stringsdict plural requires a numeric value type: " + name);
        }
      }
      Map<String, String> variants = new LinkedHashMap<>();
      Map<String, String> sourceVariants = new LinkedHashMap<>();
      Map<String, Object> ruleExtras = new LinkedHashMap<>();
      for (Map.Entry<String, Object> field : variable.entrySet()) {
        String key = field.getKey();
        if (!"NSStringFormatSpecTypeKey".equals(key)
            && !"NSStringFormatValueTypeKey".equals(key)
            && !PLURAL_CATEGORIES.contains(key)) {
          if (field.getValue() instanceof String && !key.startsWith("NSString")) {
            throw new LocalizationParseException(
                "INVALID_PLURAL_CATEGORY", "Unsupported Apple plural category: " + key);
          }
          ruleExtras.put(key, metadataValue(field.getValue()));
        } else if (PLURAL_CATEGORIES.contains(key) && !(field.getValue() instanceof String)) {
          throw invalid("Apple stringsdict plural category must be a string: " + key);
        }
      }
      int previousPlaceholders = placeholders.size();
      for (String category : PLURAL_CATEGORIES) {
        if (variable.get(category) instanceof String text) {
          String normalized =
              PlaceholderNormalizer.normalizeFoundationPlural(text, placeholders, name, position);
          List<Map<String, Object>> conversions =
              PlaceholderNormalizer.foundationPluralPrintfLineSeparators(text, name, position);
          if (!conversions.isEmpty()) {
            List<Map<String, Object>> disabled = new ArrayList<>();
            normalized =
                AppleStringsParser.withoutDisabledPrintfConversions(
                    normalized, conversions, disabled);
            disabledConversions
                .computeIfAbsent(name, unused -> new LinkedHashMap<>())
                .put(category, disabled);
          }
          variants.put(category, normalized);
          sourceVariants.put(category, text);
        }
      }
      if (!variants.containsKey("other")) {
        throw new LocalizationParseException(
            "MISSING_OTHER_VARIANT", "Apple plural is missing other");
      }
      boolean foundNumeric =
          placeholders.subList(previousPlaceholders, placeholders.size()).stream()
              .anyMatch(placeholder -> name.equals(placeholder.name()) && numeric(placeholder));
      if (!foundNumeric) {
        if (declaredType == null) {
          throw invalid("Apple stringsdict plural requires a numeric format argument: " + name);
        }
        List<LocalizationPlaceholder> typed = PlaceholderNormalizer.placeholders();
        PlaceholderNormalizer.normalizePlural("%" + declaredType, typed, name, position);
        LocalizationPlaceholder selector = typed.getFirst();
        if (!placeholders.contains(selector)) {
          placeholders.add(selector);
        }
      }
      expansions.put(name, PlaceholderNormalizer.plural(name, variants));
      Map<String, Object> rule = new LinkedHashMap<>();
      if (declaredType != null) {
        rule.put("valueType", declaredType);
      }
      rule.put("variants", sourceVariants);
      if (!ruleExtras.isEmpty()) {
        rule.put("applePlistExtras", ruleExtras);
      }
      pluralRules.put(name, rule);
      if (variables.size() == 1) {
        singleVariants = variants;
        valueType = declaredType;
      }
    }
    if (variables.isEmpty() && widths.isEmpty() && devices.isEmpty()) {
      throw invalid("Apple stringsdict entry does not contain a plural variable: " + id);
    }
    if (variables.size() == 1) {
      metadata.put("applePluralRules", pluralRules);
      metadata.put("pluralVariable", variables.get(0));
      if (valueType != null) {
        metadata.put("valueType", valueType);
      }
    } else if (variables.size() > 1) {
      singleVariants = null;
      metadata.put("applePluralRules", pluralRules);
      metadata.put("pluralVariables", variables);
    }
    if (!disabledConversions.isEmpty()) {
      metadata.put("applePluralDisabledPrintfConversions", disabledConversions);
    }
    Map<String, Object> messageExtras = new LinkedHashMap<>();
    for (Map.Entry<String, Object> field : message.entrySet()) {
      if (!"NSStringLocalizedFormatKey".equals(field.getKey())
          && !"NSStringVariableWidthRuleType".equals(field.getKey())
          && !"NSStringDeviceSpecificRuleType".equals(field.getKey())
          && !variables.contains(field.getKey())) {
        messageExtras.put(field.getKey(), metadataValue(field.getValue()));
      }
    }
    if (!messageExtras.isEmpty()) {
      metadata.put("applePlistExtras", messageExtras);
    }
    if (!variables.isEmpty()) {
      Matcher markers = PLURAL_MARKER.matcher(pattern);
      StringBuilder masked = new StringBuilder();
      while (markers.find()) {
        markers.appendReplacement(
            masked, Matcher.quoteReplacement("\u0001" + markers.group(2) + "\u0001"));
      }
      markers.appendTail(masked);
      List<LocalizationPlaceholder> outer = PlaceholderNormalizer.placeholders();
      String normalized = PlaceholderNormalizer.normalize(masked.toString(), outer);
      if (!outer.isEmpty()) {
        pattern = normalized;
        for (LocalizationPlaceholder placeholder : outer) {
          if (!placeholders.contains(placeholder)) {
            placeholders.add(placeholder);
          }
        }
      } else {
        pattern = masked.toString();
      }
      for (Map.Entry<String, String> expansion : expansions.entrySet()) {
        pattern = pattern.replace("\u0001" + expansion.getKey() + "\u0001", expansion.getValue());
      }
    } else {
      pattern = PlaceholderNormalizer.normalizeFoundation(pattern, placeholders);
      List<Map<String, Object>> conversions =
          PlaceholderNormalizer.foundationPrintfLineSeparators(
              rawPattern instanceof String value
                  ? value
                  : !devices.isEmpty()
                      ? devices.get(defaultDevice(devices))
                      : widths.get(widestWidthKey));
      if (!conversions.isEmpty()) {
        List<Map<String, Object>> disabled = new ArrayList<>();
        pattern =
            AppleStringsParser.withoutDisabledPrintfConversions(pattern, conversions, disabled);
        metadata.put("appleDisabledPrintfConversions", disabled);
      }
    }
    catalog.add(id, LocalizationMessage.of(pattern, null, singleVariants, placeholders, metadata));
  }

  private Map<String, Object> deviceRules(Map<String, Object> message) {
    Object raw = message.get("NSStringDeviceSpecificRuleType");
    if (!(raw instanceof Map<?, ?> devices)) {
      return Map.of();
    }
    Map<String, Object> rules = new LinkedHashMap<>();
    boolean dictionaries = false;
    for (Map.Entry<?, ?> device : devices.entrySet()) {
      if (!(device.getKey() instanceof String name)) {
        throw invalid("Apple device rule must have string keys");
      }
      if (device.getValue() instanceof Map<?, ?> dictionary) {
        rules.put(name, castDictionary(dictionary));
        dictionaries = true;
      } else if (device.getValue() instanceof String value) {
        rules.put(name, value);
      } else {
        throw invalid("Apple device rule must contain strings or variation dictionaries");
      }
    }
    return dictionaries ? rules : Map.of();
  }

  private void parseDeviceVariationMessage(
      LocalizationCatalog catalog, String id, Map<String, Object> devices) {
    String selected = defaultDevice(devices);
    LocalizationCatalog nested = new LocalizationCatalog(LocalizationFileFormat.APPLE_STRINGSDICT);
    Object branch = devices.get(selected);
    if (branch instanceof Map<?, ?> dictionary) {
      parseMessage(nested, id, castDictionary(dictionary));
    } else if (branch instanceof String value) {
      parseMessage(nested, id, Map.of("NSStringDeviceSpecificRuleType", Map.of(selected, value)));
    }
    LocalizationMessage message = nested.messages().get(id);
    boolean mixed =
        devices.values().stream()
                .map(
                    value ->
                        value instanceof Map<?, ?> dictionary
                                && dictionary.containsKey("NSStringVariableWidthRuleType")
                            ? "width"
                            : value instanceof Map<?, ?> ? "plural" : "scalar")
                .distinct()
                .limit(2)
                .count()
            > 1;
    if (message == null
        || !mixed
            && message.variants() == null
            && (message.metadata() == null || !message.metadata().containsKey("widthVariants"))) {
      throw invalid("Apple device-specific dictionary must contain plural or width rules");
    }
    Map<String, Object> metadata = new LinkedHashMap<>(message.metadata());
    metadata.put("defaultDevice", selected);
    metadata.put(
        mixed
            ? "deviceMixedVariants"
            : message.variants() != null ? "devicePluralVariants" : "deviceWidthVariants",
        devices);
    catalog.add(
        id,
        LocalizationMessage.of(
            message.defaultMessage(),
            message.description(),
            message.variants(),
            message.placeholders(),
            metadata));
  }

  private static Map<String, Integer> pluralPositions(String pattern, Map<String, Object> message) {
    Map<String, Integer> positions = new LinkedHashMap<>();
    Matcher markers = PLURAL_MARKER.matcher(pattern);
    while (markers.find()) {
      String name = markers.group(2);
      if (!PLURAL_NAME.matcher(name).matches()) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER", "Apple stringsdict plural name is not safe for Foundation");
      }
      if (!(message.get(name) instanceof Map<?, ?> definition)
          || !"NSStringPluralRuleType".equals(definition.get("NSStringFormatSpecTypeKey"))) {
        throw invalid("Apple stringsdict plural has no matching definition: " + name);
      }
      Integer position = null;
      if (markers.group(1) != null) {
        try {
          position = Integer.parseInt(markers.group(1));
        } catch (NumberFormatException exception) {
          throw new LocalizationParseException(
              "INVALID_PLACEHOLDER", "Apple stringsdict plural position must be positive");
        }
        if (position <= 0) {
          throw new LocalizationParseException(
              "INVALID_PLACEHOLDER", "Apple stringsdict plural position must be positive");
        }
      }
      if (positions.containsKey(name) && !Objects.equals(positions.get(name), position)) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER", "Apple stringsdict plural has conflicting positions: " + name);
      }
      positions.put(name, position);
    }
    return positions;
  }

  private static boolean numeric(LocalizationPlaceholder placeholder) {
    return "integer".equals(placeholder.kind()) || "number".equals(placeholder.kind());
  }

  private static Map<String, String> variationValues(
      Map<String, Object> message, String rule, boolean validateWidths) {
    Object raw = message.get(rule);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> values) || values.isEmpty()) {
      throw invalid("Apple variation rule must contain at least one value: " + rule);
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String value)) {
        throw invalid("Apple variation rule must contain string values: " + rule);
      }
      if (validateWidths) {
        try {
          if (Integer.parseInt(name) <= 0) {
            throw invalid("Apple presentation width must be positive: " + name);
          }
        } catch (NumberFormatException exception) {
          throw invalid("Apple presentation width must be numeric: " + name);
        }
      }
      result.put(name, value);
    }
    return result;
  }

  private static String widestWidthKey(Map<String, String> widths) {
    return widths.keySet().stream()
        .max(
            java.util.Comparator.<String>comparingInt(Integer::parseInt)
                .thenComparing(java.util.Comparator.naturalOrder()))
        .orElseThrow();
  }

  private static String defaultDevice(Map<String, ?> devices) {
    for (String device :
        List.of("iphone", "ipad", "mac", "applewatch", "applevision", "appletv", "ipod")) {
      if (devices.containsKey(device)) {
        return device;
      }
    }
    return devices.keySet().stream().min(java.util.Comparator.naturalOrder()).orElseThrow();
  }

  private Map<String, Object> dictionary(Element dictionary) {
    Map<String, Object> result = new LinkedHashMap<>();
    List<Element> children = childElements(dictionary);
    if (children.size() % 2 != 0) {
      throw invalid("Apple plist dictionary must contain key/value pairs");
    }
    for (int index = 0; index < children.size(); index += 2) {
      Element key = children.get(index);
      Element value = children.get(index + 1);
      if (!"key".equals(key.getTagName())) {
        throw invalid("Expected an Apple plist dictionary key");
      }
      validateScalar(key, true, false);
      Object decoded = value(value);
      if (result.putIfAbsent(key.getTextContent(), decoded) != null) {
        throw new LocalizationParseException("DUPLICATE_MESSAGE_ID", "Duplicate Apple plist key");
      }
    }
    return result;
  }

  private Object value(Element element) {
    String type = element.getTagName();
    if (!"dict".equals(type) && !"array".equals(type)) {
      validateScalar(
          element,
          "string".equals(type) || "real".equals(type),
          "true".equals(type) || "false".equals(type));
    }
    return switch (type) {
      case "dict" -> dictionary(element);
      case "array" -> {
        List<Object> result = new ArrayList<>();
        for (Element child : childElements(element)) {
          result.add(value(child));
        }
        yield result;
      }
      case "string" -> element.getTextContent();
      case "integer" -> integer(element.getTextContent());
      case "real" -> real(element.getTextContent());
      case "data" -> data(element.getTextContent());
      case "date" -> date(element.getTextContent());
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      default -> throw invalid("Unsupported Apple plist value " + element.getTagName());
    };
  }

  static Object metadataValue(Object source) {
    return switch (source) {
      case String value -> value;
      case Boolean value -> value;
      case Integer value -> value;
      case Long value -> value;
      case BigInteger value -> value;
      case PlistReal value ->
          Map.of(
              "$applePlistType",
              "real",
              "bits",
              String.format("%016x", Double.doubleToRawLongBits(value.value())));
      case PlistData value ->
          Map.of(
              "$applePlistType",
              "data",
              "base64",
              Base64.getEncoder().encodeToString(value.bytes()));
      case PlistDate value -> Map.of("$applePlistType", "date", "value", value.value().toString());
      case List<?> values -> values.stream().map(AppleStringsdictParser::metadataValue).toList();
      case Map<?, ?> fields -> {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
          if (!(entry.getKey() instanceof String key)) {
            throw invalid("Apple property-list dictionary keys must be strings");
          }
          result.put(key, metadataValue(entry.getValue()));
        }
        if (!result.containsKey("$applePlistType")) {
          yield result;
        }
        List<Map<String, Object>> entries =
            result.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(
                    entry ->
                        Map.<String, Object>of("key", entry.getKey(), "value", entry.getValue()))
                .toList();
        yield Map.of("$applePlistType", "dictionary", "entries", entries);
      }
      case null, default -> throw invalid("Unsupported Apple property-list metadata value");
    };
  }

  static PlistData data(String source) {
    java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
    int accumulator = 0;
    int count = 0;
    int padding = 0;
    for (int index = 0; index < source.length(); index++) {
      int character = source.charAt(index);
      if (character >= 128) {
        throw invalid("Invalid Apple property-list base64 data");
      }
      if (character == '=') {
        padding++;
      } else if (!" \\t\\n\\r\\f".contains(Character.toString(character))) {
        padding = 0;
      }
      int decoded =
          character >= 'A' && character <= 'Z'
              ? character - 'A'
              : character >= 'a' && character <= 'z'
                  ? character - 'a' + 26
                  : character >= '0' && character <= '9'
                      ? character - '0' + 52
                      : character == '+' ? 62 : character == '/' ? 63 : character == '=' ? 0 : -1;
      if (decoded < 0) {
        continue;
      }
      accumulator = accumulator << 6 | decoded;
      if (++count % 4 == 0) {
        result.write(accumulator >> 16 & 0xff);
        if (padding < 2) {
          result.write(accumulator >> 8 & 0xff);
        }
        if (padding < 1) {
          result.write(accumulator & 0xff);
        }
        if (result.size() > 1_000_000) {
          throw invalid("Apple property-list data exceeds its maximum size");
        }
      }
    }
    return new PlistData(result.toByteArray());
  }

  static PlistDate date(String source) {
    if (!source.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) {
      throw invalid("Invalid Apple property-list UTC date");
    }
    try {
      int year = Integer.parseInt(source.substring(0, 4));
      int month = Integer.parseInt(source.substring(5, 7));
      int day = Integer.parseInt(source.substring(8, 10));
      int hour = Integer.parseInt(source.substring(11, 13));
      int minute = Integer.parseInt(source.substring(14, 16));
      int second = Integer.parseInt(source.substring(17, 19));
      java.time.LocalDate start = java.time.LocalDate.of(year, 1, 1);
      int monthOffset =
          month >= 1 && month <= 12
              ? java.time.LocalDate.of(year, month, 1).getDayOfYear() - 1
              : month == 13 ? start.lengthOfYear() : 0;
      Instant normalized =
          start
              .plusDays((long) monthOffset + day - 1)
              .atStartOfDay(java.time.ZoneOffset.UTC)
              .toInstant()
              .plusSeconds(hour * 3600L + minute * 60L + second);
      return new PlistDate(normalized);
    } catch (java.time.DateTimeException exception) {
      throw invalid("Invalid Apple property-list UTC date");
    }
  }

  static PlistReal real(String source) {
    String lowercase = source.toLowerCase(java.util.Locale.ROOT);
    return switch (lowercase) {
      case "nan" -> new PlistReal(Double.NaN);
      case "inf", "+inf", "infinity", "+infinity" -> new PlistReal(Double.POSITIVE_INFINITY);
      case "-inf", "-infinity" -> new PlistReal(Double.NEGATIVE_INFINITY);
      default -> {
        if (!source.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
          throw invalid("Invalid Apple property-list real value");
        }
        try {
          yield new PlistReal(Double.parseDouble(source));
        } catch (NumberFormatException exception) {
          throw invalid("Invalid Apple property-list real value");
        }
      }
    };
  }

  static Number integer(String source) {
    if (!source.matches("[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)")) {
      throw invalid("Invalid Apple property-list integer");
    }
    boolean negative = source.startsWith("-");
    String digits = source.startsWith("+") || negative ? source.substring(1) : source;
    int radix = digits.startsWith("0x") || digits.startsWith("0X") ? 16 : 10;
    if (radix == 16) {
      digits = digits.substring(2);
    }
    BigInteger result = new BigInteger(digits, radix);
    if (negative) {
      result = result.negate();
    }
    if (result.compareTo(MIN_INTEGER) < 0 || result.compareTo(MAX_INTEGER) > 0) {
      throw invalid("Apple property-list integer is outside its 64-bit range");
    }
    if (result.bitLength() <= Integer.SIZE - 1) {
      return result.intValue();
    }
    if (result.bitLength() <= Long.SIZE - 1) {
      return result.longValue();
    }
    return result;
  }

  private static List<Element> childElements(Element parent) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int index = 0; index < nodes.getLength(); index++) {
      Node child = nodes.item(index);
      if (child instanceof Element element) {
        result.add(element);
      } else if (child.getNodeType() == Node.CDATA_SECTION_NODE) {
        throw invalid("CDATA is not allowed between Apple plist container values");
      } else if (child.getNodeType() != Node.COMMENT_NODE
          && child.getNodeType() != Node.PROCESSING_INSTRUCTION_NODE
          && !xmlWhitespace(child.getTextContent())) {
        throw invalid("Unexpected text inside Apple plist container");
      }
    }
    return result;
  }

  private static void validateScalar(Element element, boolean allowCdata, boolean empty) {
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (empty
          || child.getNodeType() == Node.ELEMENT_NODE
          || child.getNodeType() == Node.COMMENT_NODE
          || child.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
          || child.getNodeType() == Node.CDATA_SECTION_NODE && !allowCdata) {
        throw invalid("Unexpected content inside Apple plist " + element.getTagName());
      }
    }
  }

  private static void rejectSelfClosingData(String source) {
    for (int index = source.indexOf('<'); index >= 0; index = source.indexOf('<', index + 1)) {
      if (source.startsWith("<!--", index)) {
        index = source.indexOf("-->", index + 4) + 2;
        continue;
      }
      if (source.startsWith("<![CDATA[", index)) {
        index = source.indexOf("]]>", index + 9) + 2;
        continue;
      }
      if (source.startsWith("<?", index)) {
        index = source.indexOf("?>", index + 2) + 1;
        continue;
      }
      int end = index + 1;
      char quote = 0;
      while (end < source.length()) {
        char character = source.charAt(end);
        if (quote == 0 && (character == '\'' || character == '"')) {
          quote = character;
        } else if (character == quote) {
          quote = 0;
        } else if (character == '>' && quote == 0) {
          break;
        }
        end++;
      }
      String tag = source.substring(index + 1, end).strip();
      if (tag.endsWith("/")
          && tag.substring(0, tag.length() - 1).strip().matches("(?s)data(?:\\s.*)?")) {
        throw invalid("Apple plist data requires an explicit closing tag");
      }
      index = end;
    }
  }

  private static boolean xmlWhitespace(String value) {
    return value
        .chars()
        .allMatch(
            character ->
                character == ' ' || character == '\t' || character == '\n' || character == '\r');
  }

  private static Element firstElement(Element parent) {
    List<Element> children = childElements(parent);
    return children.isEmpty() ? null : children.get(0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castDictionary(Map<?, ?> raw) {
    return (Map<String, Object>) raw;
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_APPLE_STRINGSDICT", message);
  }

  record PlistData(byte[] bytes) {}

  record PlistDate(Instant value) {}

  record PlistReal(double value) {}
}
