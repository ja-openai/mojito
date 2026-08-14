package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Exact Foundation XML source ownership for independent plural, width, and device rules. */
final class AppleStringsdictSourceSkeleton {

  private static final Set<String> PLURAL_CATEGORIES =
      Set.of("zero", "one", "two", "few", "many", "other");

  private final String source;
  private final SourceSkeletonEncoding encoding;
  private final Map<List<String>, SlotIdentity> expected;
  private final List<LocalizationSourceSlot> slots = new ArrayList<>();
  private Set<String> removedMessages = Set.of();
  private final List<Removal> removals = new ArrayList<>();

  private AppleStringsdictSourceSkeleton(
      String source, SourceSkeletonEncoding encoding, Map<List<String>, SlotIdentity> expected) {
    this.source = source;
    this.encoding = encoding;
    this.expected = expected;
  }

  static LocalizationSourceSkeleton extract(byte[] bytes) {
    return extract(bytes, false);
  }

  static LocalizationSourceSkeleton extract(byte[] bytes, boolean allVariations) {
    if (AppleBinaryPlistParser.matches(bytes)) {
      throw invalid(
          "UNSUPPORTED_SKELETON_SOURCE", "Binary strings dictionaries require binary value slots");
    }
    var declared =
        LocalizationFileConverters.xmlCharset(LocalizationFileFormat.APPLE_STRINGSDICT, bytes);
    String source = LocalizationFileConverters.decode(bytes, declared);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes, declared);
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.APPLE_STRINGSDICT, bytes);
    Map<List<String>, SlotIdentity> expected = expectedPaths(catalog, allVariations);
    AppleStringsdictSourceSkeleton scanner =
        new AppleStringsdictSourceSkeleton(source, encoding, expected);
    scanner.scan();
    if (scanner.slots.size() != expected.size()) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing owned Foundation stringsdict value");
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.APPLE_STRINGSDICT.id(), encoding.name(), source, scanner.slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported Foundation stringsdict source version");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.APPLE_STRINGSDICT, original);
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (!known.add(slot.translationKey())) {
        throw invalid("INVALID_SKELETON", "Duplicated Foundation stringsdict value slot");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no Foundation stringsdict value");
    }

    ByteArrayOutputStream result = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Overlapping Foundation stringsdict value slots");
      }
      result.write(original, previous, slot.start() - previous);
      String translation = translations.get(slot.translationKey());
      if (translation == null) {
        result.write(original, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null || !hasCategory(message, slot)) {
          throw invalid("INVALID_SKELETON", "Missing canonical Foundation stringsdict descriptor");
        }
        String nativeValue;
        if (slot.selector() != null && slot.selector().startsWith("@device=")) {
          String device = slot.selector().substring("@device=".length());
          nativeValue =
              AppleStringsdictWriter.restore(
                  translation,
                  isDeviceWidth(message, device)
                      ? deviceWidthMessage(message, device, slot.variant())
                      : devicePluralMessage(message, device, slot.variant()));
        } else if ("@device".equals(slot.selector()) || "@width".equals(slot.selector())) {
          nativeValue =
              AppleStringsdictWriter.restore(
                  translation, variationMessage(message, slot.selector(), slot.variant()));
        } else {
          String selector =
              slot.selector() != null
                  ? slot.selector()
                  : message.metadata() != null
                          && message.metadata().get("pluralVariable") instanceof String variable
                      ? variable
                      : null;
          nativeValue =
              AppleStringsdictWriter.restore(translation, message, selector, slot.variant());
        }
        String body = encoding.decode(original, slot.start(), slot.end());
        String replacement;
        if (body.startsWith("/") && body.endsWith(">")) {
          String prefix = encoding.decode(original, encoding.bom().length, slot.start());
          int opening = prefix.lastIndexOf('<');
          if (opening < 0) {
            throw invalid("INVALID_SKELETON", "Self-closing stringsdict has no opening tag");
          }
          String name = prefix.substring(opening + 1).stripLeading().split("\\s+", 2)[0];
          replacement = ">" + AppleSourceSkeleton.xmlText(nativeValue) + "</" + name + ">";
        } else {
          replacement =
              body.contains("<![CDATA[")
                  ? AppleSourceSkeleton.preserveCdata(body, nativeValue)
                  : AppleSourceSkeleton.xmlText(nativeValue);
        }
        byte[] encoded = replacement.getBytes(encoding.charset());
        result.write(encoded, 0, encoded.length);
      }
      previous = slot.end();
    }
    result.write(original, previous, original.length - previous);
    return result.toByteArray();
  }

  static byte[] removeMessages(LocalizationSourceSkeleton skeleton, Set<String> removed) {
    if ("BINARY_PLIST".equals(skeleton.encoding())) {
      throw invalid(
          "UNSUPPORTED_SKELETON_SOURCE",
          "Cannot safely remove messages from a binary Foundation strings dictionary");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    AppleStringsdictSourceSkeleton scanner =
        new AppleStringsdictSourceSkeleton(skeleton.source(), encoding, Map.of());
    scanner.removedMessages = removed;
    scanner.scan();
    String source = skeleton.source();
    StringBuilder result = new StringBuilder(source.length());
    int previous = 0;
    for (Removal removal : scanner.removals) {
      int start = removal.start();
      while (start > previous
          && (source.charAt(start - 1) == ' ' || source.charAt(start - 1) == '\t')) {
        start--;
      }
      if (start > previous
          && source.charAt(start - 1) != '\n'
          && source.charAt(start - 1) != '\r') {
        start = removal.start();
      }
      result.append(source, previous, start);
      previous = removal.end();
      while (previous < source.length()
          && (source.charAt(previous) == ' ' || source.charAt(previous) == '\t')) {
        previous++;
      }
      if (previous < source.length() && source.charAt(previous) == '\r') {
        previous++;
      }
      if (previous < source.length() && source.charAt(previous) == '\n') {
        previous++;
      }
    }
    return encoding.encode(result.append(source, previous, source.length()).toString());
  }

  static byte[] retainPluralCategories(byte[] original, Set<String> categories) {
    if (categories.isEmpty() || AppleBinaryPlistParser.matches(original)) {
      return original;
    }
    original = completePluralCategories(original, categories);
    LocalizationSourceSkeleton skeleton = extract(original, true);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    String source = skeleton.source();
    for (int index = skeleton.slots().size() - 1; index >= 0; index--) {
      LocalizationSourceSlot slot = skeleton.slots().get(index);
      if (slot.variant() == null
          || !PLURAL_CATEGORIES.contains(slot.variant())
          || categories.contains(slot.variant())
          || "@width".equals(slot.selector())
          || "@device".equals(slot.selector())) {
        continue;
      }
      int valueStart = encoding.decode(original, encoding.bom().length, slot.start()).length();
      int valueEnd = encoding.decode(original, encoding.bom().length, slot.end()).length();
      int valueOpening = source.lastIndexOf('<', valueStart - 1);
      int keyClosing = source.lastIndexOf("</key>", valueOpening);
      int keyOpening = source.lastIndexOf("<key", keyClosing);
      if (valueOpening < 0 || keyClosing < 0 || keyOpening < 0) {
        throw invalid("INVALID_SKELETON", "Foundation plural value is missing its source key");
      }
      int keyBody = source.indexOf('>', keyOpening) + 1;
      if (keyBody <= keyOpening
          || !source.substring(keyBody, keyClosing).trim().equals(slot.variant())
          || !source.substring(keyClosing + "</key>".length(), valueOpening).isBlank()) {
        throw invalid("INVALID_SKELETON", "Foundation plural key does not own its source value");
      }
      int end;
      if (source.startsWith("</string>", valueEnd)) {
        end = valueEnd + "</string>".length();
      } else if (source.charAt(valueStart) == '/' && source.charAt(valueEnd - 1) == '>') {
        end = valueEnd;
      } else {
        throw invalid("INVALID_SKELETON", "Foundation plural value has no closing string tag");
      }
      int lineStart =
          Math.max(
                  source.lastIndexOf('\n', keyOpening - 1),
                  source.lastIndexOf('\r', keyOpening - 1))
              + 1;
      int start = source.substring(lineStart, keyOpening).isBlank() ? lineStart : keyOpening;
      int next = end;
      while (next < source.length()
          && (source.charAt(next) == ' ' || source.charAt(next) == '\t')) {
        next++;
      }
      if (next < source.length() && source.charAt(next) == '\r') {
        next++;
      }
      if (next < source.length() && source.charAt(next) == '\n') {
        next++;
        end = next;
      }
      source = source.substring(0, start) + source.substring(end);
    }
    return encoding.encode(source);
  }

  private static byte[] completePluralCategories(byte[] original, Set<String> categories) {
    LocalizationSourceSkeleton skeleton = extract(original, true);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    String source = skeleton.source();
    Map<List<String>, List<PluralSourceValue>> groups = new LinkedHashMap<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.variant() == null
          || !PLURAL_CATEGORIES.contains(slot.variant())
          || "@width".equals(slot.selector())
          || "@device".equals(slot.selector())) {
        continue;
      }
      groups
          .computeIfAbsent(
              List.of(slot.id(), slot.selector() == null ? "" : slot.selector()),
              ignored -> new ArrayList<>())
          .add(pluralSourceValue(original, source, encoding, slot));
    }
    Map<Integer, StringBuilder> insertions = new TreeMap<>();
    List<String> order = List.of("zero", "one", "two", "few", "many", "other");
    for (List<PluralSourceValue> group : groups.values()) {
      PluralSourceValue fallback =
          group.stream().filter(value -> "other".equals(value.category())).findFirst().orElse(null);
      if (fallback == null) {
        continue;
      }
      for (String category : order) {
        if (!categories.contains(category)
            || group.stream().anyMatch(value -> category.equals(value.category()))) {
          continue;
        }
        int rank = order.indexOf(category);
        PluralSourceValue next =
            group.stream()
                .filter(value -> order.indexOf(value.category()) > rank)
                .findFirst()
                .orElse(null);
        int position = next == null ? group.get(group.size() - 1).end() : next.start();
        String template = source.substring(fallback.start(), fallback.end());
        int keyStart = fallback.keyStart() - fallback.start();
        int keyEnd = fallback.keyEnd() - fallback.start();
        String cloned = template.substring(0, keyStart) + category + template.substring(keyEnd);
        insertions.computeIfAbsent(position, ignored -> new StringBuilder()).append(cloned);
      }
    }
    if (insertions.isEmpty()) {
      return original;
    }
    StringBuilder completed = new StringBuilder(source);
    List<Integer> positions = new ArrayList<>(insertions.keySet());
    for (int index = positions.size() - 1; index >= 0; index--) {
      int position = positions.get(index);
      completed.insert(position, insertions.get(position));
    }
    return encoding.encode(completed.toString());
  }

  private static PluralSourceValue pluralSourceValue(
      byte[] original,
      String source,
      SourceSkeletonEncoding encoding,
      LocalizationSourceSlot slot) {
    int valueStart = encoding.decode(original, encoding.bom().length, slot.start()).length();
    int valueEnd = encoding.decode(original, encoding.bom().length, slot.end()).length();
    int valueOpening = source.lastIndexOf('<', valueStart - 1);
    int keyClosing = source.lastIndexOf("</key>", valueOpening);
    int keyOpening = source.lastIndexOf("<key", keyClosing);
    int keyBody = source.indexOf('>', keyOpening) + 1;
    if (valueOpening < 0
        || keyClosing < 0
        || keyOpening < 0
        || keyBody <= keyOpening
        || !source.substring(keyBody, keyClosing).trim().equals(slot.variant())) {
      throw invalid("INVALID_SKELETON", "Foundation plural value has no owned source key");
    }
    int end;
    if (source.startsWith("</string>", valueEnd)) {
      end = valueEnd + "</string>".length();
    } else if (source.charAt(valueStart) == '/' && source.charAt(valueEnd - 1) == '>') {
      end = valueEnd;
    } else {
      throw invalid("INVALID_SKELETON", "Foundation plural value has no closing string tag");
    }
    int lineStart =
        Math.max(source.lastIndexOf('\n', keyOpening - 1), source.lastIndexOf('\r', keyOpening - 1))
            + 1;
    int start = source.substring(lineStart, keyOpening).isBlank() ? lineStart : keyOpening;
    while (end < source.length() && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) {
      end++;
    }
    if (end < source.length() && source.charAt(end) == '\r') {
      end++;
    }
    if (end < source.length() && source.charAt(end) == '\n') {
      end++;
    }
    return new PluralSourceValue(slot.variant(), start, end, keyBody, keyClosing);
  }

  private record PluralSourceValue(String category, int start, int end, int keyStart, int keyEnd) {}

  static Map<List<String>, SlotIdentity> expectedPaths(LocalizationCatalog catalog) {
    return expectedPaths(catalog, false);
  }

  private static Map<List<String>, SlotIdentity> expectedPaths(
      LocalizationCatalog catalog, boolean allVariations) {
    Map<List<String>, SlotIdentity> result = new HashMap<>();
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      String id = entry.getKey();
      LocalizationMessage message = entry.getValue();
      Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
      if (metadata.get("deviceMixedVariants") instanceof Map<?, ?> devices) {
        String selected = metadata.get("defaultDevice") instanceof String name ? name : null;
        for (Map.Entry<?, ?> device : devices.entrySet()) {
          if (!(device.getKey() instanceof String name)) {
            throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device branch");
          }
          if (!allVariations && !name.equals(selected)) {
            continue;
          }
          if (device.getValue() instanceof String) {
            result.put(
                List.of(id, "NSStringDeviceSpecificRuleType", name),
                new SlotIdentity(
                    id, allVariations ? "@device" : null, allVariations ? name : null));
          } else if (device.getValue() instanceof Map<?, ?> branch
              && branch.get("NSStringVariableWidthRuleType") instanceof Map<?, ?> widths) {
            for (Map.Entry<?, ?> width : widths.entrySet()) {
              if (!(width.getKey() instanceof String identity)
                  || !(width.getValue() instanceof String)) {
                throw invalid(
                    "UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device width value");
              }
              result.put(
                  List.of(
                      id,
                      "NSStringDeviceSpecificRuleType",
                      name,
                      "NSStringVariableWidthRuleType",
                      identity),
                  new SlotIdentity(id, "@device=" + name, identity));
            }
          } else if (device.getValue() instanceof Map<?, ?> branch) {
            addDevicePluralPaths(result, id, name, branch);
          } else {
            throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device variation");
          }
        }
      } else if (metadata.get("deviceWidthVariants") instanceof Map<?, ?> devices) {
        String selected = metadata.get("defaultDevice") instanceof String name ? name : null;
        for (Map.Entry<?, ?> device : devices.entrySet()) {
          if (!(device.getKey() instanceof String name)
              || !(device.getValue() instanceof Map<?, ?> branch)
              || !(branch.get("NSStringVariableWidthRuleType") instanceof Map<?, ?> widths)) {
            throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device width");
          }
          if (!allVariations && !name.equals(selected)) {
            continue;
          }
          for (Map.Entry<?, ?> width : widths.entrySet()) {
            if (!(width.getKey() instanceof String identity)
                || !(width.getValue() instanceof String)) {
              throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device width value");
            }
            result.put(
                List.of(
                    id,
                    "NSStringDeviceSpecificRuleType",
                    name,
                    "NSStringVariableWidthRuleType",
                    identity),
                new SlotIdentity(id, "@device=" + name, identity));
          }
        }
      } else if (metadata.get("devicePluralVariants") instanceof Map<?, ?> devices) {
        if (allVariations) {
          for (Map.Entry<?, ?> device : devices.entrySet()) {
            if (!(device.getKey() instanceof String name)
                || !(device.getValue() instanceof Map<?, ?> branch)) {
              throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device plural");
            }
            addDevicePluralPaths(result, id, name, branch);
          }
        } else {
          if (!(metadata.get("defaultDevice") instanceof String name)
              || !(devices.get(name) instanceof Map<?, ?> branch)) {
            throw invalid(
                "UNSUPPORTED_SKELETON_SOURCE", "Missing selected Foundation device plural");
          }
          addDevicePluralPaths(result, id, name, branch);
        }
      } else if (metadata.get("pluralVariables") instanceof List<?> variables) {
        if (!(metadata.get("applePluralRules") instanceof Map<?, ?> rules)) {
          throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Foundation plural definitions");
        }
        for (Object item : variables) {
          if (!(item instanceof String variable)
              || !(rules.get(variable) instanceof Map<?, ?> definition)
              || !(definition.get("variants") instanceof Map<?, ?> categories)) {
            throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Foundation plural categories");
          }
          for (Object itemCategory : categories.keySet()) {
            if (!(itemCategory instanceof String category)) {
              throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation plural category");
            }
            result.put(List.of(id, variable, category), new SlotIdentity(id, variable, category));
          }
        }
      } else if (metadata.get("pluralVariable") instanceof String variable) {
        if (message.variants() == null) {
          throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Foundation plural categories");
        }
        for (String category : message.variants().keySet()) {
          result.put(List.of(id, variable, category), new SlotIdentity(id, null, category));
        }
      } else if (metadata.get("defaultDevice") instanceof String device) {
        if (allVariations && metadata.get("deviceVariants") instanceof Map<?, ?> variants) {
          for (Object branch : variants.keySet()) {
            if (!(branch instanceof String name)) {
              throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation device branch");
            }
            result.put(
                List.of(id, "NSStringDeviceSpecificRuleType", name),
                new SlotIdentity(id, "@device", name));
          }
        } else {
          result.put(
              List.of(id, "NSStringDeviceSpecificRuleType", device),
              new SlotIdentity(id, null, null));
        }
      } else if (metadata.get("defaultWidth") instanceof Number width) {
        if (allVariations && metadata.get("widthVariants") instanceof Map<?, ?> variants) {
          for (Object branch : variants.keySet()) {
            if (!(branch instanceof String name)) {
              throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Invalid Foundation width branch");
            }
            result.put(
                List.of(id, "NSStringVariableWidthRuleType", name),
                new SlotIdentity(id, "@width", name));
          }
        } else {
          String identifier =
              metadata.get("defaultWidthKey") instanceof String original
                  ? original
                  : width.toString();
          result.put(
              List.of(id, "NSStringVariableWidthRuleType", identifier),
              new SlotIdentity(id, null, null));
        }
      } else {
        throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unsupported Foundation stringsdict rule");
      }
    }
    return result;
  }

  private static void addDevicePluralPaths(
      Map<List<String>, SlotIdentity> result, String id, String device, Map<?, ?> branch) {
    for (Map.Entry<?, ?> entry : branch.entrySet()) {
      if (!(entry.getKey() instanceof String variable)
          || !(entry.getValue() instanceof Map<?, ?> rule)
          || !"NSStringPluralRuleType".equals(rule.get("NSStringFormatSpecTypeKey"))) {
        continue;
      }
      for (String category : List.of("zero", "one", "two", "few", "many", "other")) {
        if (rule.get(category) instanceof String) {
          result.put(
              List.of(id, "NSStringDeviceSpecificRuleType", device, variable, category),
              new SlotIdentity(id, "@device=" + device, category));
        }
      }
    }
  }

  static boolean hasCategory(LocalizationMessage message, LocalizationSourceSlot slot) {
    if (slot.selector() == null) {
      return slot.variant() == null
          || message.variants() != null && message.variants().containsKey(slot.variant());
    }
    if ("@device".equals(slot.selector()) || "@width".equals(slot.selector())) {
      String kind = "@device".equals(slot.selector()) ? "deviceVariants" : "widthVariants";
      return slot.variant() != null
          && message.metadata() != null
          && deviceOrVariationBranches(message, kind) instanceof Map<?, ?> branches
          && branches.get(slot.variant()) instanceof String;
    }
    if (slot.selector().startsWith("@device=")) {
      if (message.metadata() != null
          && deviceOrVariationBranches(message, "deviceWidthVariants") instanceof Map<?, ?> devices
          && devices.get(slot.selector().substring("@device=".length())) instanceof Map<?, ?> branch
          && branch.get("NSStringVariableWidthRuleType") instanceof Map<?, ?> widths) {
        return slot.variant() != null && widths.get(slot.variant()) instanceof String;
      }
      return slot.variant() != null
          && message.metadata() != null
          && deviceOrVariationBranches(message, "devicePluralVariants") instanceof Map<?, ?> devices
          && devices.get(slot.selector().substring("@device=".length())) instanceof Map<?, ?> branch
          && branch.values().stream()
              .anyMatch(
                  value ->
                      value instanceof Map<?, ?> rule
                          && "NSStringPluralRuleType".equals(rule.get("NSStringFormatSpecTypeKey"))
                          && rule.get(slot.variant()) instanceof String);
    }
    return slot.variant() != null
        && message.metadata() != null
        && message.metadata().get("applePluralRules") instanceof Map<?, ?> rules
        && rules.get(slot.selector()) instanceof Map<?, ?> definition
        && definition.get("variants") instanceof Map<?, ?> categories
        && categories.containsKey(slot.variant());
  }

  private static LocalizationMessage variationMessage(
      LocalizationMessage message, String selector, String branch) {
    String kind = "@device".equals(selector) ? "deviceVariants" : "widthVariants";
    if (message.metadata() == null
        || !(deviceOrVariationBranches(message, kind) instanceof Map<?, ?> variations)
        || !(variations.get(branch) instanceof String source)) {
      throw invalid("INVALID_SKELETON", "Missing Foundation variation branch");
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized = PlaceholderNormalizer.normalizeFoundation(source, placeholders);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPrintfLineSeparators(source);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    return LocalizationMessage.of(normalized, null, null, placeholders, metadata);
  }

  private static LocalizationMessage devicePluralMessage(
      LocalizationMessage message, String device, String category) {
    if (message.metadata() == null
        || !(deviceOrVariationBranches(message, "devicePluralVariants")
            instanceof Map<?, ?> devices)
        || !(devices.get(device) instanceof Map<?, ?> branch)) {
      throw invalid("INVALID_SKELETON", "Missing Foundation device plural branch");
    }
    String variable = null;
    String source = null;
    for (Map.Entry<?, ?> candidate : branch.entrySet()) {
      if (candidate.getKey() instanceof String name
          && candidate.getValue() instanceof Map<?, ?> rule
          && "NSStringPluralRuleType".equals(rule.get("NSStringFormatSpecTypeKey"))
          && rule.get(category) instanceof String value) {
        variable = name;
        source = value;
        break;
      }
    }
    if (source == null) {
      throw invalid("INVALID_SKELETON", "Missing Foundation device plural category");
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized =
        PlaceholderNormalizer.normalizeFoundationPlural(source, placeholders, variable, null);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPluralPrintfLineSeparators(source, variable, null);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    return LocalizationMessage.of(normalized, null, null, placeholders, metadata);
  }

  private static LocalizationMessage deviceWidthMessage(
      LocalizationMessage message, String device, String width) {
    if (message.metadata() == null
        || !(deviceOrVariationBranches(message, "deviceWidthVariants") instanceof Map<?, ?> devices)
        || !(devices.get(device) instanceof Map<?, ?> branch)
        || !(branch.get("NSStringVariableWidthRuleType") instanceof Map<?, ?> widths)
        || !(widths.get(width) instanceof String source)) {
      throw invalid("INVALID_SKELETON", "Missing Foundation device width branch");
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized = PlaceholderNormalizer.normalizeFoundation(source, placeholders);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPrintfLineSeparators(source);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    return LocalizationMessage.of(normalized, null, null, placeholders, metadata);
  }

  private static Object deviceOrVariationBranches(LocalizationMessage message, String kind) {
    if (message.metadata() == null) {
      return null;
    }
    if (kind.startsWith("device")
        && message.metadata().get("deviceMixedVariants") instanceof Map<?, ?> branches) {
      return branches;
    }
    return message.metadata().get(kind);
  }

  private static boolean isDeviceWidth(LocalizationMessage message, String device) {
    return deviceOrVariationBranches(message, "deviceWidthVariants") instanceof Map<?, ?> devices
        && devices.get(device) instanceof Map<?, ?> branch
        && branch.get("NSStringVariableWidthRuleType") instanceof Map<?, ?>;
  }

  private void scan() {
    Deque<XmlElement> stack = new ArrayDeque<>();
    for (int position = 0; position < source.length(); ) {
      if (source.charAt(position) != '<') {
        position++;
        continue;
      }
      if (source.startsWith("<!--", position)) {
        position = sectionEnd(position, "-->");
        continue;
      }
      if (source.startsWith("<![CDATA[", position)) {
        position = sectionEnd(position, "]]>");
        continue;
      }
      if (source.startsWith("<?", position)) {
        position = sectionEnd(position, "?>");
        continue;
      }
      int end = tagEnd(position);
      String token = source.substring(position + 1, end).trim();
      if (token.startsWith("!")) {
        position = end + 1;
        continue;
      }
      if (token.startsWith("/")) {
        XmlElement current = stack.pop();
        XmlElement parent = stack.peek();
        if ("key".equals(current.name) && parent != null && "dict".equals(parent.name)) {
          parent.pendingKey = xmlKey(source.substring(current.bodyStart, position));
          parent.pendingKeyStart = current.openingStart;
        } else if ("string".equals(current.name)) {
          addSlot(current.path, current.bodyStart, position);
        } else if ("dict".equals(current.name)
            && current.path.size() == 1
            && removedMessages.contains(current.path.get(0))) {
          removals.add(new Removal(current.entryStart, end + 1));
        }
      } else {
        boolean empty = token.endsWith("/");
        if (empty) {
          token = token.substring(0, token.length() - 1).trim();
        }
        String name = token.split("\\s+", 2)[0];
        XmlElement parent = stack.peek();
        int entryStart =
            parent != null
                    && "dict".equals(parent.name)
                    && parent.path.isEmpty()
                    && !"key".equals(name)
                ? parent.pendingKeyStart
                : -1;
        List<String> path = valuePath(parent, name);
        if (empty) {
          if ("string".equals(name)) {
            addSlot(path, source.lastIndexOf('/', end), end + 1);
          }
        } else {
          stack.push(new XmlElement(name, position, end + 1, path, entryStart));
        }
      }
      position = end + 1;
    }
  }

  private List<String> valuePath(XmlElement parent, String name) {
    if (parent == null || !"dict".equals(parent.name) || "key".equals(name)) {
      return parent == null ? List.of() : parent.path;
    }
    if (parent.pendingKey == null) {
      throw invalid("INVALID_SKELETON", "Foundation dictionary value is missing its key");
    }
    List<String> result = new ArrayList<>(parent.path);
    result.add(parent.pendingKey);
    parent.pendingKey = null;
    return List.copyOf(result);
  }

  private void addSlot(List<String> path, int start, int end) {
    SlotIdentity identity = expected.get(path);
    if (identity != null) {
      slots.add(
          new LocalizationSourceSlot(
              identity.id(),
              identity.selector(),
              identity.variant(),
              encoding.offset(source, start),
              encoding.offset(source, end)));
    }
  }

  private String xmlKey(String body) {
    LocalizationCatalog parsed =
        new AppleStringsParser("<dict><key>" + body + "</key><string/></dict>").parse();
    return parsed.messages().keySet().iterator().next();
  }

  private int tagEnd(int start) {
    char quote = 0;
    for (int position = start + 1; position < source.length(); position++) {
      char value = source.charAt(position);
      if (quote == 0 && (value == '\'' || value == '"')) {
        quote = value;
      } else if (value == quote) {
        quote = 0;
      } else if (value == '>' && quote == 0) {
        return position;
      }
    }
    throw invalid("INVALID_SKELETON", "Unterminated Foundation stringsdict tag");
  }

  private int sectionEnd(int start, String delimiter) {
    int end = source.indexOf(delimiter, start);
    if (end < 0) {
      throw invalid("INVALID_SKELETON", "Unterminated Foundation stringsdict section");
    }
    return end + delimiter.length();
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  record SlotIdentity(String id, String selector, String variant) {}

  private record Removal(int start, int end) {}

  private static final class XmlElement {

    private final String name;
    private final int openingStart;
    private final int bodyStart;
    private final List<String> path;
    private final int entryStart;
    private String pendingKey;
    private int pendingKeyStart;

    private XmlElement(
        String name, int openingStart, int bodyStart, List<String> path, int entryStart) {
      this.name = name;
      this.openingStart = openingStart;
      this.bodyStart = bodyStart;
      this.path = path;
      this.entryStart = entryStart;
    }
  }
}
