package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, compiler-valid Android XML regenerated from a loss-aware canonical catalog. */
final class AndroidResourcesWriter {

  private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
  private static final String XLIFF_NAMESPACE = "urn:oasis:names:tc:xliff:document:1.2";
  private static final String TOOLS_NAMESPACE = "http://schemas.android.com/tools";
  private static final Pattern ARGUMENT = Pattern.compile("\\{([\\p{L}\\p{N}\\p{M}\\p{So}_]+)\\}");
  private static final Pattern FEATURE_FLAG = Pattern.compile("!?[A-Za-z_][A-Za-z0-9_.-]*");
  private static final List<String> PLURAL_ORDER =
      List.of("zero", "one", "two", "few", "many", "other");

  String write(LocalizationCatalog catalog) {
    if (!LocalizationFileFormat.ANDROID.id().equals(catalog.sourceFormat())) {
      throw new LocalizationParseException(
          "INVALID_SOURCE_FORMAT", "Android writer requires an Android canonical catalog");
    }
    TreeMap<String, LocalizationMessage> messages = new TreeMap<>(catalog.messages());
    validatePathFeatureFlags(messages);
    StringBuilder output =
        new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources");
    if (messages.values().stream()
        .anyMatch(
            message ->
                featureFlag(message) != null
                    || (message.metadata() != null
                        && message.metadata().containsKey("androidArrayFeatureFlags")))) {
      output.append(" xmlns:android=\"").append(ANDROID_NAMESPACE).append('"');
    }
    if (requiresProtectedPlaceholders(messages)) {
      output.append(" xmlns:xliff=\"").append(XLIFF_NAMESPACE).append('"');
    }
    if (catalog.locale() != null) {
      output
          .append(" xmlns:tools=\"")
          .append(TOOLS_NAMESPACE)
          .append("\" tools:locale=\"")
          .append(escapeAttribute(catalog.locale()))
          .append('"');
    }
    output.append(">\n");
    AndroidAttributeDependencies.write(output, messages);

    Set<String> handled = new HashSet<>();
    for (Map.Entry<String, LocalizationMessage> entry : messages.entrySet()) {
      if (!handled.add(entry.getKey())) {
        continue;
      }
      LocalizationMessage message = entry.getValue();
      String arrayName = stringMetadata(message, "arrayName");
      validateRuntimeIdentity(entry.getKey(), message, arrayName);
      if (arrayName != null) {
        writeArray(
            output,
            messages,
            handled,
            arrayName,
            stringMetadata(message, "androidProduct"),
            runtimeFeatureFlag(message));
      } else if (message.variants() != null) {
        writePlural(output, resourceName(entry.getKey(), message), message);
      } else {
        writeString(output, resourceName(entry.getKey(), message), message);
      }
    }
    return output.append("</resources>\n").toString();
  }

  private static void writeArray(
      StringBuilder output,
      TreeMap<String, LocalizationMessage> messages,
      Set<String> handled,
      String name,
      String product,
      String runtimeFlag) {
    TreeMap<Integer, LocalizationMessage> entries = new TreeMap<>();
    TreeMap<Integer, String> references = new TreeMap<>();
    TreeMap<Integer, String> primitives = new TreeMap<>();
    TreeMap<Integer, String> itemFlags = new TreeMap<>();
    boolean declaredReferences = false;
    Boolean genericArray = null;
    String arrayFormat = null;
    String bagType = null;
    String arrayFeatureFlag = null;
    for (Map.Entry<String, LocalizationMessage> entry : messages.entrySet()) {
      LocalizationMessage message = entry.getValue();
      if (!name.equals(stringMetadata(message, "arrayName"))
          || !java.util.Objects.equals(product, stringMetadata(message, "androidProduct"))
          || !java.util.Objects.equals(runtimeFlag, runtimeFeatureFlag(message))) {
        continue;
      }
      Object index = message.metadata().get("arrayIndex");
      if (!(index instanceof Number number)
          || number.intValue() < 0
          || entries.putIfAbsent(number.intValue(), message) != null) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array indexes must be unique nonnegative integers");
      }
      boolean currentGeneric = Boolean.TRUE.equals(message.metadata().get("androidGenericArray"));
      String currentFormat = stringMetadata(message, "androidArrayFormat");
      String currentBagType = stringMetadata(message, "androidBagType");
      String currentFeatureFlag = featureFlag(message);
      if ((currentBagType != null
              && (!Set.of("array", "string-array").contains(currentBagType)
                  || currentGeneric != "array".equals(currentBagType)))
          || (genericArray != null && !java.util.Objects.equals(bagType, currentBagType))) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_BAG", "Android array entries must agree on their native bag type");
      }
      if ((currentFormat != null && (!currentGeneric || !"string".equals(currentFormat)))
          || (genericArray != null
              && (genericArray != currentGeneric
                  || !java.util.Objects.equals(arrayFormat, currentFormat)))) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array entries must agree on their native type");
      }
      if (genericArray != null && !java.util.Objects.equals(arrayFeatureFlag, currentFeatureFlag)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_FEATURE_FLAG",
            "Android array entries must agree on their resource flag");
      }
      genericArray = currentGeneric;
      arrayFormat = currentFormat;
      bagType = currentBagType;
      arrayFeatureFlag = currentFeatureFlag;
      Map<String, String> declared = references(message, "androidArrayReferences");
      TreeMap<Integer, String> current = new TreeMap<>();
      for (Map.Entry<String, String> reference : declared.entrySet()) {
        try {
          int position = Integer.parseInt(reference.getKey());
          if (position < 0 || current.putIfAbsent(position, reference.getValue()) != null) {
            throw new NumberFormatException("negative or duplicate array reference position");
          }
        } catch (NumberFormatException invalid) {
          throw new LocalizationParseException(
              "INVALID_ANDROID_ARRAY", "Android array reference positions must be nonnegative");
        }
      }
      if (!declaredReferences) {
        references.putAll(current);
      } else if (!references.equals(current)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array entries must agree on preserved references");
      }
      TreeMap<Integer, String> currentPrimitives = primitives(message);
      TreeMap<Integer, String> currentItemFlags = arrayFeatureFlags(message);
      if (!currentPrimitives.isEmpty() && !currentGeneric) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Only generic Android arrays can preserve primitives");
      }
      if (!declaredReferences) {
        primitives.putAll(currentPrimitives);
        itemFlags.putAll(currentItemFlags);
        declaredReferences = true;
      } else if (!primitives.equals(currentPrimitives)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array entries must agree on preserved primitives");
      }
      if (declaredReferences && !itemFlags.equals(currentItemFlags)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_FEATURE_FLAG",
            "Android array entries must agree on item feature flags");
      }
      handled.add(entry.getKey());
    }
    if (bagType != null) {
      output.append("  <bag type=\"").append(bagType).append("\" name=\"");
    } else {
      output.append(
          Boolean.TRUE.equals(genericArray) ? "  <array name=\"" : "  <string-array name=\"");
    }
    output.append(escapeAttribute(name)).append('"');
    appendFeatureFlag(output, arrayFeatureFlag);
    if (arrayFormat != null) {
      output.append(" format=\"").append(arrayFormat).append('"');
    }
    appendProduct(output, product);
    output.append(">\n");
    for (Integer position : references.keySet()) {
      if (entries.containsKey(position) || primitives.containsKey(position)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array reference collides with a message");
      }
    }
    for (Integer position : primitives.keySet()) {
      if (entries.containsKey(position)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array primitive collides with a message");
      }
    }
    int size = entries.size() + references.size() + primitives.size();
    if (itemFlags.keySet().stream().anyMatch(index -> index >= size)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_FEATURE_FLAG",
          "Android item feature flags require real array positions");
    }
    for (int index = 0; index < size; index++) {
      LocalizationMessage message = entries.get(index);
      String reference = references.get(index);
      String primitive = primitives.get(index);
      if (message == null && reference == null && primitive == null) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array indexes must be contiguous");
      }
      if (reference != null) {
        output.append("    <item");
        appendFeatureFlag(output, itemFlags.get(index));
        output.append('>').append(escapeXmlText(reference)).append("</item>\n");
      } else if (primitive != null) {
        output.append("    <item");
        appendFeatureFlag(output, itemFlags.get(index));
        output.append('>').append(escapeXmlText(primitive)).append("</item>\n");
      } else {
        output.append("    <item");
        appendFeatureFlag(output, itemFlags.get(index));
        appendDescription(output, message);
        appendFormatted(output, message);
        output.append('>').append(render(message, message.defaultMessage())).append("</item>\n");
      }
    }
    if (bagType != null) {
      output.append("  </bag>\n");
    } else {
      output.append(Boolean.TRUE.equals(genericArray) ? "  </array>\n" : "  </string-array>\n");
    }
  }

  private static TreeMap<Integer, String> primitives(LocalizationMessage message) {
    if (message.metadata() == null || !message.metadata().containsKey("androidArrayPrimitives")) {
      return new TreeMap<>();
    }
    if (!(message.metadata().get("androidArrayPrimitives") instanceof Map<?, ?> values)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_ARRAY", "Android array primitives must be an object");
    }
    TreeMap<Integer, String> result = new TreeMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || !(entry.getValue() instanceof String value)
          || !AndroidResourcesParser.isNativePrimitive(value, "")) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Invalid Android array primitive metadata");
      }
      try {
        int position = Integer.parseInt(key);
        if (position < 0 || result.putIfAbsent(position, value) != null) {
          throw new NumberFormatException("negative or duplicate primitive position");
        }
      } catch (NumberFormatException invalid) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_ARRAY", "Android array primitive positions must be nonnegative");
      }
    }
    return result;
  }

  private static TreeMap<Integer, String> arrayFeatureFlags(LocalizationMessage message) {
    if (message.metadata() == null || !message.metadata().containsKey("androidArrayFeatureFlags")) {
      return new TreeMap<>();
    }
    if (!(message.metadata().get("androidArrayFeatureFlags") instanceof Map<?, ?> values)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_FEATURE_FLAG", "Android array feature flags must be an object");
    }
    TreeMap<Integer, String> result = new TreeMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || !(entry.getValue() instanceof String flag)
          || !FEATURE_FLAG.matcher(flag).matches()) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_FEATURE_FLAG", "Invalid Android array feature-flag metadata");
      }
      try {
        int index = Integer.parseInt(key);
        if (index < 0 || result.putIfAbsent(index, flag) != null) {
          throw new NumberFormatException("negative or duplicate feature-flag position");
        }
      } catch (NumberFormatException invalid) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_FEATURE_FLAG", "Android feature-flag positions must be nonnegative");
      }
    }
    return result;
  }

  private static void writeString(StringBuilder output, String name, LocalizationMessage message) {
    if (stringMetadata(message, "androidBagType") != null) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_BAG", "Scalar Android strings cannot carry bag metadata");
    }
    boolean generic =
        message.metadata() != null
            && Boolean.TRUE.equals(message.metadata().get("androidGenericString"));
    output
        .append(generic ? "  <item type=\"string\" name=\"" : "  <string name=\"")
        .append(escapeAttribute(name))
        .append('"');
    appendFeatureFlag(output, featureFlag(message));
    String genericFormat = stringMetadata(message, "androidGenericFormat");
    if (genericFormat != null) {
      if (!generic || !"string".equals(genericFormat)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_FORMAT", "Android generic format metadata must be string");
      }
      output.append(" format=\"").append(genericFormat).append('"');
    }
    appendProduct(output, stringMetadata(message, "androidProduct"));
    appendDescription(output, message);
    appendFormatted(output, message);
    output
        .append('>')
        .append(render(message, message.defaultMessage()))
        .append(generic ? "</item>\n" : "</string>\n");
  }

  private static void writePlural(StringBuilder output, String name, LocalizationMessage message) {
    String bagType = stringMetadata(message, "androidBagType");
    if (bagType != null && !"plurals".equals(bagType)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_BAG", "Android plural messages require a plural bag type");
    }
    output
        .append(bagType == null ? "  <plurals name=\"" : "  <bag type=\"plurals\" name=\"")
        .append(escapeAttribute(name))
        .append('"');
    appendFeatureFlag(output, featureFlag(message));
    appendProduct(output, stringMetadata(message, "androidProduct"));
    appendDescription(output, message);
    output.append(">\n");
    Map<String, String> references = references(message, "androidPluralReferences");
    for (String category : references.keySet()) {
      if (!PLURAL_ORDER.contains(category) || message.variants().containsKey(category)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_REFERENCE", "Invalid or duplicate Android plural reference");
      }
    }
    if (references.containsKey("other") || !message.variants().containsKey("other")) {
      throw new LocalizationParseException(
          "MISSING_OTHER_VARIANT", "Android plural writer requires a translatable other");
    }
    for (String quantity : PLURAL_ORDER) {
      String value = message.variants().get(quantity);
      String reference = references.get(quantity);
      if (value != null || reference != null) {
        output
            .append("    <item quantity=\"")
            .append(quantity)
            .append("\">")
            .append(reference == null ? render(message, value, quantity) : escapeXmlText(reference))
            .append("</item>\n");
      }
    }
    output.append(bagType == null ? "  </plurals>\n" : "  </bag>\n");
  }

  private static Map<String, String> references(LocalizationMessage message, String name) {
    if (message.metadata() == null || !message.metadata().containsKey(name)) {
      return Map.of();
    }
    Object metadata = message.metadata().get(name);
    if (!(metadata instanceof Map<?, ?> values)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_REFERENCE", "Android reference metadata must be an object");
    }
    Map<String, String> references = new TreeMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || !(entry.getValue() instanceof String value)
          || !isReference(value)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_REFERENCE", "Invalid Android resource reference metadata");
      }
      references.put(key, value);
    }
    return references;
  }

  private static boolean isReference(String value) {
    return AndroidResourceReferences.matches(value);
  }

  private static String render(LocalizationMessage message, String canonical) {
    return render(message, canonical, null);
  }

  static String render(LocalizationMessage message, String canonical, String quantity) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    String source =
        "icu-quoted-angle".equals(metadata.get("androidMarkupEscaping"))
            ? canonical.replace("'<'", "<").replace("''", "'")
            : canonical;
    AndroidAnnotationSemantics.validate(
        message, canonical.replace("'<'", "<").replace("''", "'"), quantity);
    AndroidAnnotationSemantics.validateStyles(
        message, canonical.replace("'<'", "<").replace("''", "'"), quantity);
    AndroidAnnotationSemantics.validateParagraphs(
        message, canonical.replace("'<'", "<").replace("''", "'"), quantity);
    boolean literalMarkup = Boolean.TRUE.equals(metadata.get("androidLiteralMarkup"));
    boolean formatted = !Boolean.FALSE.equals(metadata.get("formatted"));
    LineSeparatorSpelling lineSeparators =
        lineSeparatorSpelling(message, canonical, quantity, formatted);
    PercentSpelling percent = percentSpelling(message, source, quantity, formatted);
    Map<String, List<LocalizationPlaceholder>> arguments = new HashMap<>();
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        arguments.computeIfAbsent(placeholder.name(), unused -> new ArrayList<>()).add(placeholder);
      }
    }
    applyPluralPlaceholderExamples(arguments, metadata, quantity, message.variants());
    Map<String, List<Object>> protectedOccurrences =
        applyProtectedPlaceholderOccurrences(
            arguments, metadata, quantity, message.variants(), source);
    Map<String, Integer> occurrences = new HashMap<>();
    StringBuilder output = new StringBuilder();
    int textStart = 0;
    for (int index = 0; index < source.length(); index++) {
      if (!literalMarkup && isMarkupStart(source, index)) {
        int close = markupEnd(source, index + 1);
        if (close >= 0) {
          appendText(
              output,
              source.substring(textStart, index),
              arguments,
              occurrences,
              protectedOccurrences,
              formatted,
              lineSeparators,
              percent);
          output.append(
              substituteTag(
                  source.substring(index, close + 1),
                  arguments,
                  occurrences,
                  protectedOccurrences));
          index = close;
          textStart = close + 1;
        }
      }
    }
    appendText(
        output,
        source.substring(textStart),
        arguments,
        occurrences,
        protectedOccurrences,
        formatted,
        lineSeparators,
        percent);
    return output.isEmpty() ? "\"\"" : output.toString();
  }

  private static void applyPluralPlaceholderExamples(
      Map<String, List<LocalizationPlaceholder>> placeholders,
      Map<String, Object> metadata,
      String quantity,
      Map<String, String> variants) {
    Object scoped = metadata.get("androidPluralPlaceholderExamples");
    if (scoped == null) {
      return;
    }
    if (quantity == null
        || variants == null
        || !(scoped instanceof Map<?, ?> categories)
        || categories.isEmpty()
        || !variants.keySet().containsAll(categories.keySet())) {
      throw invalidPluralPlaceholderExamples();
    }
    Object value = categories.get(quantity);
    if (value == null) {
      return;
    }
    if (!(value instanceof Map<?, ?> names) || names.isEmpty()) {
      throw invalidPluralPlaceholderExamples();
    }
    for (Map.Entry<?, ?> entry : names.entrySet()) {
      if (!(entry.getKey() instanceof String name)
          || !(entry.getValue() instanceof List<?> examples)
          || examples.isEmpty()) {
        throw invalidPluralPlaceholderExamples();
      }
      List<LocalizationPlaceholder> available = placeholders.get(name);
      if (available == null) {
        throw invalidPluralPlaceholderExamples();
      }
      List<LocalizationPlaceholder> selected = new ArrayList<>();
      for (Object example : examples) {
        if (example != null && !(example instanceof String)) {
          throw invalidPluralPlaceholderExamples();
        }
        LocalizationPlaceholder placeholder =
            available.stream()
                .filter(candidate -> java.util.Objects.equals(example, candidate.example()))
                .findFirst()
                .orElseThrow(AndroidResourcesWriter::invalidPluralPlaceholderExamples);
        selected.add(placeholder);
      }
      placeholders.put(name, selected);
    }
  }

  private static LocalizationParseException invalidPluralPlaceholderExamples() {
    return new LocalizationParseException(
        "INVALID_PLACEHOLDER", "Invalid category-owned Android protected placeholder examples");
  }

  private static Map<String, List<Object>> applyProtectedPlaceholderOccurrences(
      Map<String, List<LocalizationPlaceholder>> placeholders,
      Map<String, Object> metadata,
      String quantity,
      Map<String, String> variants,
      String canonical) {
    Object scalar = metadata.get("androidProtectedPlaceholderOccurrences");
    Object plural = metadata.get("androidPluralProtectedPlaceholderOccurrences");
    if (scalar == null && plural == null) {
      return Map.of();
    }
    if (scalar != null && plural != null || quantity == null != (plural == null)) {
      throw invalidProtectedPlaceholderOccurrences();
    }
    Object scoped = scalar;
    if (plural != null) {
      if (!(plural instanceof Map<?, ?> categories)
          || categories.isEmpty()
          || variants == null
          || !variants.keySet().containsAll(categories.keySet())) {
        throw invalidProtectedPlaceholderOccurrences();
      }
      scoped = categories.get(quantity);
      if (scoped == null) {
        return Map.of();
      }
    }
    if (!(scoped instanceof Map<?, ?> names) || names.isEmpty()) {
      throw invalidProtectedPlaceholderOccurrences();
    }
    Map<String, List<Object>> result = new HashMap<>();
    for (Map.Entry<?, ?> entry : names.entrySet()) {
      if (!(entry.getKey() instanceof String name)
          || !(entry.getValue() instanceof List<?> ownership)
          || ownership.isEmpty()) {
        throw invalidProtectedPlaceholderOccurrences();
      }
      List<LocalizationPlaceholder> available = placeholders.get(name);
      Matcher matcher = ARGUMENT.matcher(canonical);
      int count = 0;
      while (matcher.find()) {
        if (name.equals(matcher.group(1))) {
          count++;
        }
      }
      if (available == null || ownership.size() != count) {
        throw invalidProtectedPlaceholderOccurrences();
      }
      List<LocalizationPlaceholder> selected = new ArrayList<>();
      boolean protectedSection = false;
      for (Object value : ownership) {
        String example = null;
        if (value != null) {
          if (!(value instanceof Map<?, ?> section)
              || !section.keySet().stream().allMatch("example"::equals)
              || section.containsKey("example") && !(section.get("example") instanceof String)) {
            throw invalidProtectedPlaceholderOccurrences();
          }
          example = (String) section.get("example");
          protectedSection = true;
        }
        String expected = example;
        LocalizationPlaceholder placeholder =
            available.stream()
                .filter(candidate -> java.util.Objects.equals(candidate.example(), expected))
                .findFirst()
                .orElseThrow(AndroidResourcesWriter::invalidProtectedPlaceholderOccurrences);
        if (placeholder.position() == null || !name.equals("arg" + (placeholder.position() - 1))) {
          throw invalidProtectedPlaceholderOccurrences();
        }
        selected.add(placeholder);
      }
      if (!protectedSection) {
        throw invalidProtectedPlaceholderOccurrences();
      }
      placeholders.put(name, selected);
      result.put(name, new ArrayList<>(ownership));
    }
    return result;
  }

  private static LocalizationParseException invalidProtectedPlaceholderOccurrences() {
    return new LocalizationParseException(
        "INVALID_PLACEHOLDER", "Invalid Android protected placeholder occurrence ownership");
  }

  private static void appendText(
      StringBuilder output,
      String source,
      Map<String, List<LocalizationPlaceholder>> placeholders,
      Map<String, Integer> occurrences,
      Map<String, List<Object>> protectedOccurrences,
      boolean formatted,
      LineSeparatorSpelling lineSeparators,
      PercentSpelling percent) {
    if (source.isEmpty()) {
      return;
    }
    Matcher matcher = ARGUMENT.matcher(source);
    StringBuilder segment = new StringBuilder();
    int previous = 0;
    while (matcher.find()) {
      LocalizationPlaceholder placeholder = next(placeholders, occurrences, matcher.group(1));
      if (placeholder == null) {
        continue;
      }
      appendEscapedText(
          segment, source.substring(previous, matcher.start()), formatted, lineSeparators, percent);
      if (protectedPlaceholder(placeholder, matcher.group(1), occurrences, protectedOccurrences)) {
        appendQuoted(output, segment);
        output.append("<xliff:g id=\"").append(escapeAttribute(placeholder.name())).append('"');
        if (placeholder.example() != null) {
          output.append(" example=\"").append(escapeAttribute(placeholder.example())).append('"');
        }
        output.append('>').append(escapeXmlText(placeholder.source())).append("</xliff:g>");
      } else {
        segment.append(escapeXmlText(placeholder.source()));
      }
      previous = matcher.end();
    }
    appendEscapedText(segment, source.substring(previous), formatted, lineSeparators, percent);
    appendQuoted(output, segment);
  }

  private static String substituteTag(
      String tag,
      Map<String, List<LocalizationPlaceholder>> placeholders,
      Map<String, Integer> occurrences,
      Map<String, List<Object>> protectedOccurrences) {
    Matcher matcher = ARGUMENT.matcher(tag);
    StringBuilder output = new StringBuilder();
    int previous = 0;
    while (matcher.find()) {
      LocalizationPlaceholder placeholder = next(placeholders, occurrences, matcher.group(1));
      if (placeholder != null) {
        if (protectedPlaceholder(
            placeholder, matcher.group(1), occurrences, protectedOccurrences)) {
          throw new LocalizationParseException(
              "INVALID_ANDROID_MARKUP",
              "Named XLIFF placeholders cannot appear in style attributes");
        }
        appendTagText(output, tag.substring(previous, matcher.start()));
        output.append(escapeAttribute(placeholder.source()));
        previous = matcher.end();
      }
    }
    appendTagText(output, tag.substring(previous));
    return output.toString();
  }

  private static void appendTagText(StringBuilder output, String source) {
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      switch (character) {
        case '\n' -> output.append("&#10;");
        case '\r' -> output.append("&#13;");
        case '\t' -> output.append("&#9;");
        default -> output.append(character);
      }
    }
  }

  private static LocalizationPlaceholder next(
      Map<String, List<LocalizationPlaceholder>> placeholders,
      Map<String, Integer> occurrences,
      String name) {
    List<LocalizationPlaceholder> choices = placeholders.get(name);
    if (choices == null) {
      return null;
    }
    int occurrence = occurrences.getOrDefault(name, 0);
    occurrences.put(name, occurrence + 1);
    return choices.get(Math.min(occurrence, choices.size() - 1));
  }

  private static boolean requiresProtectedPlaceholders(
      TreeMap<String, LocalizationMessage> messages) {
    if (messages.values().stream()
        .anyMatch(
            message ->
                message.metadata() != null
                    && (message.metadata().containsKey("androidProtectedPlaceholderOccurrences")
                        || message
                            .metadata()
                            .containsKey("androidPluralProtectedPlaceholderOccurrences")))) {
      return true;
    }
    return messages.values().stream()
        .flatMap(
            message ->
                message.placeholders() == null
                    ? java.util.stream.Stream.empty()
                    : message.placeholders().stream())
        .anyMatch(AndroidResourcesWriter::protectedPlaceholder);
  }

  private static boolean protectedPlaceholder(LocalizationPlaceholder placeholder) {
    return placeholder.example() != null
        || placeholder.position() == null
        || !placeholder.name().equals("arg" + (placeholder.position() - 1));
  }

  private static boolean protectedPlaceholder(
      LocalizationPlaceholder placeholder,
      String name,
      Map<String, Integer> occurrences,
      Map<String, List<Object>> protectedOccurrences) {
    List<Object> ownership = protectedOccurrences.get(name);
    return ownership == null
        ? protectedPlaceholder(placeholder)
        : ownership.get(occurrences.get(name) - 1) != null;
  }

  private static void appendEscapedText(
      StringBuilder output,
      String text,
      boolean formatted,
      LineSeparatorSpelling lineSeparators,
      PercentSpelling percent) {
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '&' -> output.append("&amp;");
        case '<' -> output.append("&lt;");
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\n' -> output.append(lineSeparators.next() ? "%n" : "\\n");
        case '\r' -> output.append("\\u000D");
        case '\t' -> output.append("\\t");
        case '%' -> output.append(formatted ? percent.next() : "%");
        default -> {
          if (character < 0x20) {
            output.append(String.format("\\u%04X", (int) character));
          } else {
            output.append(character);
          }
        }
      }
    }
  }

  private static LineSeparatorSpelling lineSeparatorSpelling(
      LocalizationMessage message, String canonical, String quantity, boolean formatted) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Object enabled = metadata.get("androidPrintfLineSeparator");
    Object singular = metadata.get("androidPrintfLineSeparators");
    Object plural = metadata.get("androidPluralPrintfLineSeparators");
    if (enabled != null && !Boolean.TRUE.equals(enabled)
        || !formatted && (enabled != null || singular != null || plural != null)
        || (singular != null || plural != null) && !Boolean.TRUE.equals(enabled)
        || quantity == null && plural != null
        || quantity != null && singular != null) {
      throw invalidLineSeparator();
    }
    Object positions = singular;
    boolean explicit = singular != null;
    if (quantity != null && plural != null) {
      if (!(plural instanceof Map<?, ?> variants) || variants.isEmpty()) {
        throw invalidLineSeparator();
      }
      for (Map.Entry<?, ?> entry : variants.entrySet()) {
        if (!(entry.getKey() instanceof String category)
            || message.variants() == null
            || !message.variants().containsKey(category)) {
          throw invalidLineSeparator();
        }
        validateLineSeparatorPositions(entry.getValue(), message.variants().get(category));
      }
      positions = variants.get(quantity);
      explicit = true;
    }
    List<Integer> selected =
        positions == null ? List.of() : validateLineSeparatorPositions(positions, canonical);
    return new LineSeparatorSpelling(Boolean.TRUE.equals(enabled), explicit, selected);
  }

  private static List<Integer> validateLineSeparatorPositions(Object values, String canonical) {
    if (!(values instanceof List<?> positions) || positions.isEmpty()) {
      throw invalidLineSeparator();
    }
    int newlineCount = visibleCharacterCount(canonical, '\n');
    List<Integer> selected = new ArrayList<>();
    int previous = -1;
    for (Object value : positions) {
      if (!(value instanceof Number number)
          || number.doubleValue() != number.intValue()
          || number.intValue() <= previous
          || number.intValue() >= newlineCount) {
        throw invalidLineSeparator();
      }
      previous = number.intValue();
      selected.add(previous);
    }
    return selected;
  }

  private static LocalizationParseException invalidLineSeparator() {
    return new LocalizationParseException(
        "INVALID_ANDROID_LINE_SEPARATOR", "Invalid or unsafe Android line-separator metadata");
  }

  private static final class LineSeparatorSpelling {
    private final boolean enabled;
    private final boolean explicit;
    private final List<Integer> selected;
    private int occurrence;

    private LineSeparatorSpelling(boolean enabled, boolean explicit, List<Integer> selected) {
      this.enabled = enabled;
      this.explicit = explicit;
      this.selected = selected;
    }

    private boolean next() {
      return explicit ? selected.contains(occurrence++) : enabled;
    }
  }

  private static PercentSpelling percentSpelling(
      LocalizationMessage message, String canonical, String quantity, boolean formatted) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Object singular = metadata.get("androidRawPercentOccurrences");
    Object plural = metadata.get("androidPluralRawPercentOccurrences");
    if (!formatted && (singular != null || plural != null)
        || quantity == null && plural != null
        || quantity != null && singular != null) {
      throw invalidPercent();
    }
    Object positions = singular;
    if (quantity != null && plural != null) {
      if (!(plural instanceof Map<?, ?> variants) || variants.isEmpty()) {
        throw invalidPercent();
      }
      for (Map.Entry<?, ?> entry : variants.entrySet()) {
        if (!(entry.getKey() instanceof String category)
            || message.variants() == null
            || !message.variants().containsKey(category)) {
          throw invalidPercent();
        }
        validatePercentPositions(entry.getValue(), message.variants().get(category));
      }
      positions = variants.get(quantity);
    }
    return new PercentSpelling(
        positions == null ? List.of() : validatePercentPositions(positions, canonical), formatted);
  }

  private static List<Integer> validatePercentPositions(Object values, String canonical) {
    if (!(values instanceof List<?> positions) || positions.isEmpty()) {
      throw invalidPercent();
    }
    int percentCount = visibleCharacterCount(canonical, '%');
    List<Integer> normalized = new ArrayList<>();
    int previous = -1;
    for (Object value : positions) {
      if (!(value instanceof Number number)
          || number.doubleValue() != number.intValue()
          || number.intValue() <= previous
          || number.intValue() >= percentCount) {
        throw invalidPercent();
      }
      previous = number.intValue();
      normalized.add(previous);
    }
    return normalized;
  }

  private static int visibleCharacterCount(String source, char selected) {
    String unquoted = source.replace("'<'", "<").replace("''", "'");
    int count = 0;
    for (int index = 0; index < unquoted.length(); index++) {
      if (unquoted.charAt(index) == '<' && isMarkupStart(unquoted, index)) {
        int close = markupEnd(unquoted, index + 1);
        if (close >= 0) {
          index = close;
          continue;
        }
      }
      if (unquoted.charAt(index) == selected) {
        count++;
      }
    }
    return count;
  }

  private static LocalizationParseException invalidPercent() {
    return new LocalizationParseException(
        "INVALID_ANDROID_PERCENT", "Invalid or unsafe Android literal percent metadata");
  }

  private static final class PercentSpelling {
    private final List<Integer> raw;
    private final boolean formatted;
    private int occurrence;

    private PercentSpelling(List<Integer> raw, boolean formatted) {
      this.raw = raw;
      this.formatted = formatted;
    }

    private String next() {
      return !formatted || raw.contains(occurrence++) ? "%" : "%%";
    }
  }

  private static void appendQuoted(StringBuilder output, StringBuilder segment) {
    if (!segment.isEmpty()) {
      output.append('"').append(segment).append('"');
      segment.setLength(0);
    }
  }

  private static boolean isMarkupStart(String input, int offset) {
    return input.charAt(offset) == '<'
        && offset + 1 < input.length()
        && (Character.isLetter(input.charAt(offset + 1)) || input.charAt(offset + 1) == '/');
  }

  private static int markupEnd(String input, int offset) {
    boolean quoted = false;
    for (int index = offset; index < input.length(); index++) {
      char character = input.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (character == '>' && !quoted) {
        return index;
      }
    }
    return -1;
  }

  private static void appendDescription(StringBuilder output, LocalizationMessage message) {
    if (message.description() != null) {
      output.append(" description=\"").append(escapeAttribute(message.description())).append('"');
    }
  }

  private static void appendFormatted(StringBuilder output, LocalizationMessage message) {
    if (message.metadata() != null && Boolean.FALSE.equals(message.metadata().get("formatted"))) {
      output.append(" formatted=\"false\"");
    }
  }

  private static void appendProduct(StringBuilder output, String product) {
    if (product != null) {
      output.append(" product=\"").append(escapeAttribute(product)).append('"');
    }
  }

  private static void appendFeatureFlag(StringBuilder output, String featureFlag) {
    if (featureFlag != null) {
      output.append(" android:featureFlag=\"").append(escapeAttribute(featureFlag)).append('"');
    }
  }

  private static String featureFlag(LocalizationMessage message) {
    if (message.metadata() == null || !message.metadata().containsKey("androidFeatureFlag")) {
      return null;
    }
    Object value = message.metadata().get("androidFeatureFlag");
    if (!(value instanceof String featureFlag) || !FEATURE_FLAG.matcher(featureFlag).matches()) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_FEATURE_FLAG", "Invalid Android resource feature-flag metadata");
    }
    return featureFlag;
  }

  private static void validatePathFeatureFlags(Map<String, LocalizationMessage> messages) {
    String expected = null;
    for (LocalizationMessage message : messages.values()) {
      if (message.metadata() == null || !message.metadata().containsKey("androidPathFeatureFlag")) {
        continue;
      }
      Object condition = message.metadata().get("androidPathFeatureFlag");
      Object path = message.metadata().get("androidResourcePath");
      if (!(condition instanceof String flag)
          || !FEATURE_FLAG.matcher(flag).matches()
          || !(path instanceof String resourcePath)
          || java.util.Arrays.stream(resourcePath.split("/"))
              .noneMatch(part -> part.equals("flag(" + flag + ")"))) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_PATH_FEATURE_FLAG", "Invalid Android path feature-flag metadata");
      }
      if (message.metadata().containsKey("androidFeatureFlag")
          || expected != null && !expected.equals(flag)) {
        throw new LocalizationParseException(
            "CONFLICTING_ANDROID_FEATURE_FLAG",
            "Android feature flags are not allowed in both the resource path and file");
      }
      expected = flag;
    }
  }

  private static String resourceName(String id, LocalizationMessage message) {
    String runtimeFlag = runtimeFeatureFlag(message);
    if (runtimeFlag != null) {
      String suffix = "@flag=" + runtimeFlag;
      if (!id.endsWith(suffix)) {
        throw new LocalizationParseException(
            "INVALID_ANDROID_FEATURE_FLAG", "Android runtime flag must match its canonical ID");
      }
      id = id.substring(0, id.length() - suffix.length());
    }
    String product = stringMetadata(message, "androidProduct");
    return product == null || "default".equals(product)
        ? id
        : id.substring(0, id.length() - ("@product=" + product).length());
  }

  private static void validateRuntimeIdentity(
      String id, LocalizationMessage message, String arrayName) {
    String runtimeFlag = runtimeFeatureFlag(message);
    if (runtimeFlag == null) {
      return;
    }
    String suffix = "@flag=" + runtimeFlag;
    if (arrayName != null) {
      suffix += "[" + message.metadata().get("arrayIndex") + "]";
    }
    if (!id.endsWith(suffix)) {
      throw new LocalizationParseException(
          "INVALID_ANDROID_FEATURE_FLAG", "Android runtime flag must match its canonical ID");
    }
  }

  private static String runtimeFeatureFlag(LocalizationMessage message) {
    if ("read_write".equals(stringMetadata(message, "androidFeatureFlagMode"))) {
      return featureFlag(message);
    }
    if ("read_write".equals(stringMetadata(message, "androidPathFeatureFlagMode"))) {
      return stringMetadata(message, "androidPathFeatureFlag");
    }
    return null;
  }

  private static String stringMetadata(LocalizationMessage message, String field) {
    return message.metadata() != null && message.metadata().get(field) instanceof String value
        ? value
        : null;
  }

  private static String escapeAttribute(String value) {
    return escapeXmlText(value)
        .replace("\"", "&quot;")
        .replace("\t", "&#9;")
        .replace("\n", "&#10;");
  }

  private static String escapeXmlText(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace("\r", "&#13;");
  }
}
