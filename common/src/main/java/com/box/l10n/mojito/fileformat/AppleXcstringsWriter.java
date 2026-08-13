package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Xcode String Catalog regeneration verified by Apple's xcstringstool. */
final class AppleXcstringsWriter {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern ARGUMENT =
      Pattern.compile("\\{([\\p{L}\\p{N}\\p{M}\\p{So}_.-]+)\\}");
  private static final Pattern SUBSTITUTION_MARKER = Pattern.compile("%(?:[1-9][0-9]*\\$)?#@");
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
    if (!LocalizationFileFormat.APPLE_XCSTRINGS.id().equals(catalog.sourceFormat())) {
      throw invalid("INVALID_SOURCE_FORMAT", "Xcode writer requires an Xcode String Catalog");
    }
    if (catalog.locale() == null || catalog.locale().isBlank()) {
      throw invalid("INVALID_XCSTRINGS_METADATA", "Xcode String Catalog requires a source locale");
    }
    Map<String, Object> root = new LinkedHashMap<>();
    TreeMap<String, Object> strings = new TreeMap<>(UNICODE_SCALAR_ORDER);
    String sourceLanguage = catalog.locale();
    Object version = "1.0";
    Object declaredVersion = null;
    Map<String, Object> rootMetadata = null;
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      LocalizationMessage message = entry.getValue();
      if (message.metadata() != null
          && message.metadata().containsKey("appleDisabledPrintfConversions")) {
        AppleStringsWriter.nativeValue(message, message.defaultMessage());
      }
      Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
      if (metadata.get("appleSourceLanguage") instanceof String identifier) {
        sourceLanguage = identifier;
      }
      if (metadata.containsKey("appleCatalogVersion")) {
        Object declared = metadata.get("appleCatalogVersion");
        if (!(declared instanceof String) && !(declared instanceof Number)) {
          throw invalid(
              "INVALID_XCSTRINGS_METADATA", "Xcode catalog version must be a string or number");
        }
        if (declaredVersion != null && !declaredVersion.equals(declared)) {
          throw invalid(
              "INVALID_XCSTRINGS_METADATA", "Xcode catalog versions must match across descriptors");
        }
        declaredVersion = declared;
        version = declared;
      }
      if (metadata.get("appleCatalogMetadata") instanceof Map<?, ?> extras) {
        Map<String, Object> current = copy(extras);
        if (rootMetadata != null && !rootMetadata.equals(current)) {
          throw invalid(
              "INVALID_XCSTRINGS_METADATA", "Xcode root metadata must match across descriptors");
        }
        rootMetadata = current;
      }
      strings.put(entry.getKey(), descriptor(sourceLanguage, message));
    }
    if (rootMetadata != null) {
      root.putAll(rootMetadata);
    }
    root.put("sourceLanguage", sourceLanguage);
    root.put("strings", strings);
    root.put("version", version);
    try {
      return render(root, 0) + "\n";
    } catch (Exception exception) {
      if (exception instanceof LocalizationParseException parse) {
        throw parse;
      }
      throw new LocalizationParseException(
          "INVALID_XCSTRINGS_METADATA", "Unable to serialize Xcode String Catalog", exception);
    }
  }

  private static Map<String, Object> descriptor(
      String sourceLanguage, LocalizationMessage message) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Map<String, Object> descriptor =
        metadata.get("appleDescriptorMetadata") instanceof Map<?, ?> extras
            ? copy(extras)
            : new LinkedHashMap<>();
    if (message.description() != null) {
      descriptor.put("comment", message.description());
    }
    if (metadata.get("extractionState") instanceof String state) {
      descriptor.put("extractionState", state);
    }
    Map<String, Object> localizations = new LinkedHashMap<>();
    if (metadata.get("appleSourceLocalization") instanceof Map<?, ?> originalSource) {
      Map<String, Object> source = copy(originalSource);
      applySource(source, message, metadata);
      String sourceIdentifier =
          metadata.get("appleSourceLocalizationIdentifier") instanceof String identifier
              ? identifier
              : sourceLanguage;
      localizations.put(sourceIdentifier, source);
    }
    if (metadata.get("localizations") instanceof Map<?, ?> translations) {
      Map<?, ?> originals =
          metadata.get("appleLocalizationSources") instanceof Map<?, ?> sources
              ? sources
              : Map.of();
      Map<?, ?> identifiers =
          metadata.get("appleLocalizationIdentifiers") instanceof Map<?, ?> names
              ? names
              : Map.of();
      for (Map.Entry<?, ?> entry : translations.entrySet()) {
        if (!(entry.getKey() instanceof String locale)
            || !(entry.getValue() instanceof Map<?, ?> value)) {
          throw invalid("INVALID_XCSTRINGS_METADATA", "Invalid Xcode localization descriptor");
        }
        Map<String, Object> localization =
            originals.get(locale) instanceof Map<?, ?> original
                ? copy(original)
                : new LinkedHashMap<>();
        applyTranslation(localization, value);
        String identifier = identifiers.get(locale) instanceof String original ? original : locale;
        localizations.put(identifier, localization);
      }
    }
    if (!localizations.isEmpty()) {
      descriptor.put("localizations", localizations);
    }
    return descriptor;
  }

  private static void applySource(
      Map<String, Object> source, LocalizationMessage message, Map<String, Object> metadata) {
    Map<String, Object> effective = source;
    Map<String, Object> variations = dictionary(source.get("variations"));
    if (!variations.containsKey("plural")
        && metadata.get("defaultDevice") instanceof String device
        && dictionary(variations.get("device")).get(device) instanceof Map<?, ?> selected) {
      effective = mutable(selected);
      dictionary(variations.get("device")).put(device, effective);
    }
    if (message.variants() != null) {
      if (!message.variants().containsKey("other")) {
        throw invalid("MISSING_OTHER_VARIANT", "Xcode plural is missing other");
      }
      Map<String, Object> branches =
          ensureDictionary(ensureDictionary(effective, "variations"), "plural");
      for (Map.Entry<String, String> category : message.variants().entrySet()) {
        Map<String, Object> branch = ensureDictionary(branches, category.getKey());
        Map<String, Object> unit = ensureDictionary(branch, "stringUnit");
        String original = unit.get("value") instanceof String value ? value : null;
        if (metadata.get("applePluralDisabledPrintfConversions") instanceof Map<?, ?> rules
            && rules.get("count") instanceof Map<?, ?> categories
            && categories.containsKey(category.getKey())) {
          restore(category.getValue(), message, category.getKey());
        }
        String nativeValue =
            original != null && normalizedVariant(original).equals(category.getValue())
                ? original
                : restore(category.getValue(), message, category.getKey());
        unit.put("value", nativeValue);
        if (!(unit.get("state") instanceof String)) {
          unit.put("state", pluralState(metadata, category.getKey()));
        }
      }
    } else {
      Map<String, Object> unit = ensureDictionary(effective, "stringUnit");
      String original = unit.get("value") instanceof String value ? value : null;
      JsonNode substitutions = JSON.valueToTree(effective.get("substitutions"));
      if (!substitutions.isObject()) {
        substitutions = JSON.valueToTree(source.get("substitutions"));
      }
      String value =
          original != null
                  && normalizedSource(original, substitutions).equals(message.defaultMessage())
              ? original
              : restore(message.defaultMessage(), message);
      unit.put("value", value);
      if (!(unit.get("state") instanceof String)) {
        unit.put(
            "state", metadata.get("sourceState") instanceof String state ? state : "translated");
      }
    }
  }

  private static String pluralState(Map<String, Object> metadata, String category) {
    return metadata.get("sourcePluralStates") instanceof Map<?, ?> states
            && states.get(category) instanceof String state
        ? state
        : "translated";
  }

  private static void applyTranslation(Map<String, Object> localization, Map<?, ?> metadata) {
    if (metadata.get("value") instanceof String value) {
      Map<String, Object> unit = ensureDictionary(localization, "stringUnit");
      unit.put("value", value);
      unit.put("state", metadata.get("state") instanceof String state ? state : "translated");
    }
    if (metadata.get("variants") instanceof Map<?, ?> variants) {
      Map<String, Object> branches =
          ensureDictionary(ensureDictionary(localization, "variations"), "plural");
      Map<?, ?> states =
          metadata.get("variantStates") instanceof Map<?, ?> values ? values : Map.of();
      for (Map.Entry<?, ?> entry : variants.entrySet()) {
        if (!(entry.getKey() instanceof String category)
            || !(entry.getValue() instanceof String value)) {
          throw invalid("INVALID_XCSTRINGS_METADATA", "Invalid Xcode translated plural");
        }
        Map<String, Object> unit =
            ensureDictionary(ensureDictionary(branches, category), "stringUnit");
        unit.put("value", value);
        unit.put("state", states.get(category) instanceof String state ? state : "translated");
      }
    }
    if (metadata.get("variationAxes") instanceof Map<?, ?> axes) {
      ensureDictionary(localization, "variations").putAll(copy(axes));
    }
  }

  static String restore(String value, LocalizationMessage message) {
    if (message.metadata() != null
        && message.metadata().containsKey("appleDisabledPrintfConversions")) {
      return AppleStringsWriter.nativeValue(message, value);
    }
    return value.indexOf('%') >= 0 && !SUBSTITUTION_MARKER.matcher(value).find()
        ? AppleStringsWriter.nativeValue(message, value)
        : restoreArguments(value, message);
  }

  static String restore(String value, LocalizationMessage message, String category) {
    if (message.metadata() == null
        || !(message.metadata().get("applePluralDisabledPrintfConversions")
            instanceof Map<?, ?> rules)
        || !(rules.get("count") instanceof Map<?, ?> categories)
        || !(categories.get(category) instanceof List<?> conversions)) {
      return value.indexOf('%') >= 0 && !SUBSTITUTION_MARKER.matcher(value).find()
          ? AppleStringsWriter.nativeValue(message, value)
          : restoreArguments(value, message);
    }
    if (message.variants() == null || !message.variants().containsKey(category)) {
      throw invalid("INVALID_APPLE_PRINTF_CONVERSION", "Missing disabled Xcode plural category");
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("appleDisabledPrintfConversions", conversions);
    LocalizationMessage scoped =
        LocalizationMessage.of(
            message.variants().get(category), null, null, message.placeholders(), metadata);
    return AppleStringsWriter.nativeValue(scoped, value);
  }

  static String restore(
      String value, LocalizationMessage message, String selector, String category) {
    if (message.metadata() == null
        || !(message.metadata().get("sourceSubstitutions") instanceof Map<?, ?> substitutions)
        || !(substitutions.get(selector) instanceof Map<?, ?> definition)
        || !(definition.get("variations") instanceof Map<?, ?> variations)
        || !(variations.get("plural") instanceof Map<?, ?> categories)
        || !(categories.get(category) instanceof Map<?, ?> branch)
        || !(branch.get("stringUnit") instanceof Map<?, ?> unit)
        || !(unit.get("value") instanceof String original)) {
      return restore(value, message);
    }
    if (!(message.metadata().get("applePluralDisabledPrintfConversions") instanceof Map<?, ?> rules)
        || !(rules.get(selector) instanceof Map<?, ?> owned)
        || !(owned.get(category) instanceof List<?> disabledConversions)) {
      return restore(value, message);
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    Integer position =
        definition.get("argNum") instanceof Number valuePosition
            ? valuePosition.intValue()
            : substitutionPosition(message, selector);
    String normalized =
        PlaceholderNormalizer.normalizeFoundationSubstitution(
            original, placeholders, selector, position);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationSubstitutionPrintfLineSeparators(
            original, selector, position);
    if (!conversions.isEmpty()) {
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(
              normalized, conversions, new ArrayList<>());
    }
    int sourceLength = normalized.codePointCount(0, normalized.length());
    int targetLength = value.codePointCount(0, value.length());
    String argument = "{" + selector + "}";
    int sourceArgument = normalized.indexOf(argument);
    int targetArgument = value.indexOf(argument);
    int sourceStart = sourceArgument < 0 ? -1 : normalized.codePointCount(0, sourceArgument);
    int targetStart = targetArgument < 0 ? -1 : value.codePointCount(0, targetArgument);
    int argumentLength = argument.codePointCount(0, argument.length());
    List<Map<String, Object>> translatedConversions = new ArrayList<>();
    for (Object disabledConversion : disabledConversions) {
      Map<?, ?> occurrence = (Map<?, ?>) disabledConversion;
      int originalPosition = ((Number) occurrence.get("position")).intValue();
      int translatedPosition =
          sourceLength == 0
              ? 0
              : (int) (((long) originalPosition * targetLength + sourceLength / 2L) / sourceLength);
      if (sourceStart >= 0 && targetStart >= 0) {
        if (originalPosition >= sourceStart + argumentLength) {
          translatedPosition = Math.max(translatedPosition, targetStart + argumentLength);
        } else if (originalPosition <= sourceStart) {
          translatedPosition = Math.min(translatedPosition, targetStart);
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
        LocalizationMessage.of(
            value,
            null,
            null,
            correctedSubstitutionPlaceholders(placeholders, selector, position),
            metadata);
    return AppleStringsWriter.nativeValue(scoped, value);
  }

  private static List<LocalizationPlaceholder> correctedSubstitutionPlaceholders(
      List<LocalizationPlaceholder> placeholders, String selector, int position) {
    List<LocalizationPlaceholder> corrected = new ArrayList<>();
    for (LocalizationPlaceholder placeholder : placeholders) {
      corrected.add(
          Integer.valueOf(position).equals(placeholder.position())
              ? new LocalizationPlaceholder(
                  selector,
                  placeholder.source(),
                  placeholder.kind(),
                  position,
                  placeholder.example())
              : placeholder);
    }
    return corrected;
  }

  private static int substitutionPosition(LocalizationMessage message, String selector) {
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        if (selector.equals(placeholder.name()) && placeholder.position() != null) {
          return placeholder.position();
        }
      }
    }
    return 1;
  }

  private static String normalizedSource(String source, JsonNode substitutions) {
    String normalized =
        AppleXcstringsParser.normalizeSource(
            source, substitutions, PlaceholderNormalizer.placeholders());
    List<Map<String, Object>> conversions = PlaceholderNormalizer.printfLineSeparators(source);
    return conversions.isEmpty()
        ? normalized
        : AppleStringsParser.withoutDisabledPrintfConversions(
            normalized, conversions, new ArrayList<>());
  }

  private static String normalizedVariant(String source) {
    String normalized =
        PlaceholderNormalizer.normalize(source, PlaceholderNormalizer.placeholders(), "count");
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.printfLineSeparators(source, "count", null);
    return conversions.isEmpty()
        ? normalized
        : AppleStringsParser.withoutDisabledPrintfConversions(
            normalized, conversions, new ArrayList<>());
  }

  private static String restoreArguments(String value, LocalizationMessage message) {
    Map<String, List<LocalizationPlaceholder>> placeholders = new HashMap<>();
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        placeholders
            .computeIfAbsent(placeholder.name(), ignored -> new ArrayList<>())
            .add(placeholder);
      }
    }
    Matcher matcher = ARGUMENT.matcher(value);
    Map<String, Integer> occurrences = new HashMap<>();
    StringBuilder output = new StringBuilder();
    int previous = 0;
    while (matcher.find()) {
      List<LocalizationPlaceholder> choices = placeholders.get(matcher.group(1));
      if (choices == null) {
        continue;
      }
      output.append(value, previous, matcher.start());
      int occurrence = occurrences.getOrDefault(matcher.group(1), 0);
      occurrences.put(matcher.group(1), occurrence + 1);
      output.append(choices.get(Math.min(occurrence, choices.size() - 1)).source());
      previous = matcher.end();
    }
    return output.append(value, previous, value.length()).toString();
  }

  private static String render(Object value, int depth) throws Exception {
    if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        return "{}";
      }
      TreeMap<String, Object> sorted = new TreeMap<>(UNICODE_SCALAR_ORDER);
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) {
          throw invalid("INVALID_XCSTRINGS_METADATA", "Xcode JSON keys must be strings");
        }
        sorted.put(key, entry.getValue());
      }
      StringBuilder output = new StringBuilder("{\n");
      int index = 0;
      for (Map.Entry<String, Object> entry : sorted.entrySet()) {
        if (index++ > 0) {
          output.append(",\n");
        }
        output
            .append("  ".repeat(depth + 1))
            .append(JSON.writeValueAsString(entry.getKey()))
            .append(": ")
            .append(render(entry.getValue(), depth + 1));
      }
      return output.append('\n').append("  ".repeat(depth)).append('}').toString();
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        return "[]";
      }
      StringBuilder output = new StringBuilder("[\n");
      for (int index = 0; index < list.size(); index++) {
        if (index > 0) {
          output.append(",\n");
        }
        output.append("  ".repeat(depth + 1)).append(render(list.get(index), depth + 1));
      }
      return output.append('\n').append("  ".repeat(depth)).append(']').toString();
    }
    return JSON.writeValueAsString(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mutable(Map<?, ?> value) {
    return (Map<String, Object>) value;
  }

  private static Map<String, Object> dictionary(Object value) {
    return value instanceof Map<?, ?> map ? mutable(map) : Map.of();
  }

  private static Map<String, Object> ensureDictionary(Map<String, Object> parent, String key) {
    if (parent.get(key) instanceof Map<?, ?> existing) {
      return mutable(existing);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    parent.put(key, result);
    return result;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> copy(Map<?, ?> input) {
    return JSON.convertValue(input, LinkedHashMap.class);
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }
}
