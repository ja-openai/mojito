package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.lang.UProperty;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Original JSON string ownership for source-locale Xcode String Catalog values. */
final class AppleXcstringsSourceSkeleton {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern SUBSTITUTION = Pattern.compile("%(?:\\d+\\$)?#@([^@]+)@");
  private static final Pattern ARGUMENT = Pattern.compile("\\{([\\p{L}\\p{N}\\p{M}\\p{So}_]+)\\}");
  private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*");
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

  private final String source;
  private final SourceSkeletonEncoding encoding;
  private final Map<List<String>, SlotIdentity> expected;
  private final List<LocalizationSourceSlot> slots = new ArrayList<>();

  private AppleXcstringsSourceSkeleton(
      String source, SourceSkeletonEncoding encoding, Map<List<String>, SlotIdentity> expected) {
    this.source = source;
    this.encoding = encoding;
    this.expected = expected;
  }

  static LocalizationSourceSkeleton extract(byte[] bytes) {
    return extract(bytes, false);
  }

  static LocalizationSourceSkeleton extract(byte[] bytes, boolean allDevices) {
    return extract(bytes, allDevices, false, null);
  }

  static LocalizationSourceSkeleton extractWithSourceInsertion(byte[] bytes) {
    return extract(bytes, false, true, null);
  }

  static LocalizationSourceSkeleton extractWithTargetInsertion(byte[] bytes, String targetLocale) {
    if (targetLocale == null) {
      throw invalid("INVALID_XCSTRINGS_LOCALE", "Missing Xcode target locale");
    }
    return extract(bytes, false, false, targetLocale);
  }

  private static LocalizationSourceSkeleton extract(
      byte[] bytes, boolean allDevices, boolean insertSourceLocales, String targetLocale) {
    String source = LocalizationFileConverters.decode(bytes, null);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes);
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.APPLE_XCSTRINGS, bytes);
    try {
      JsonNode root = JSON.readTree(source);
      String resolvedTarget = targetLocale == null ? null : targetLocale(root, targetLocale);
      Map<List<String>, SlotIdentity> expected =
          expectedPaths(root, catalog, allDevices, insertSourceLocales, resolvedTarget);
      AppleXcstringsSourceSkeleton scanner =
          new AppleXcstringsSourceSkeleton(source, encoding, expected);
      try (JsonParser parser = JSON.createParser(source)) {
        parser.nextToken();
        scanner.scan(parser, new ArrayList<>());
      }
      if (scanner.slots.size() != expected.size()) {
        throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Xcode source-locale value slot");
      }
      return new LocalizationSourceSkeleton(
          1,
          LocalizationFileFormat.APPLE_XCSTRINGS.id(),
          encoding.name(),
          source,
          null,
          null,
          resolvedTarget,
          scanner.slots);
    } catch (IOException exception) {
      throw new LocalizationParseException(
          "INVALID_SKELETON", "Cannot locate source-owned Xcode JSON values", exception);
    }
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported Xcode source-skeleton version");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.APPLE_XCSTRINGS, original);
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (!known.add(slot.translationKey())) {
        throw invalid("INVALID_SKELETON", "Duplicated Xcode source value");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no Xcode source value");
    }

    ByteArrayOutputStream result = new ByteArrayOutputStream(original.length);
    List<LocalizationSourceSlot> insertionOwners = null;
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Overlapping or out-of-range Xcode JSON source value");
      }
      result.write(original, previous, slot.start() - previous);
      String translation = translations.get(slot.translationKey());
      if (translation == null) {
        result.write(original, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null || skeleton.appleTargetLocale() == null && !owns(message, slot)) {
          throw invalid("INVALID_SKELETON", "Xcode value has no canonical source descriptor");
        }
        if (skeleton.appleTargetLocale() != null) {
          if (insertionOwners == null) {
            insertionOwners =
                extractWithTargetInsertion(original, skeleton.appleTargetLocale()).slots();
          }
          if (!insertionOwners.contains(slot)) {
            throw invalid(
                "INVALID_SKELETON", "Xcode target slot does not own its requested locale");
          }
        }
        boolean targetDeviceInsertion =
            skeleton.appleTargetLocale() != null
                && message.metadata() != null
                && message.metadata().containsKey("sourceVariationAxes")
                && !hasSubstitutions(message)
                && slot.selector() == null
                && slot.variant() == null;
        boolean targetPluralInsertion =
            skeleton.appleTargetLocale() != null
                && message.variants() != null
                && !targetDeviceInsertion
                && slot.selector() == null
                && slot.variant() == null;
        boolean targetSubstitutionInsertion =
            skeleton.appleTargetLocale() != null
                && hasSubstitutions(message)
                && slot.selector() == null
                && slot.variant() == null
                && (slot.start() == slot.end()
                    || "null"
                        .equals(
                            new String(
                                original,
                                slot.start(),
                                slot.end() - slot.start(),
                                encoding.charset())));
        String nativeValue =
            targetDeviceInsertion || targetPluralInsertion || targetSubstitutionInsertion
                ? null
                : skeleton.appleTargetLocale() != null
                        && hasSubstitutions(message)
                        && (slot.selector() == null || "@device".equals(slot.selector()))
                    ? restoreTargetSubstitutionRoot(
                        translation,
                        message,
                        skeleton.appleTargetLocale(),
                        "@device".equals(slot.selector()) ? slot.variant() : null)
                    : skeleton.appleTargetLocale() != null
                            && hasSubstitutions(message)
                            && slot.selector() != null
                        ? restoreTargetSubstitutionCategory(
                            translation,
                            message,
                            skeleton.appleTargetLocale(),
                            slot.selector(),
                            slot.variant())
                        : skeleton.appleTargetLocale() != null && "@device".equals(slot.selector())
                            ? restoreTargetDeviceRoot(
                                translation, message, skeleton.appleTargetLocale(), slot.variant())
                            : skeleton.appleTargetLocale() != null
                                    && slot.selector() != null
                                    && slot.selector().startsWith("@device=")
                                ? restoreTargetDevicePlural(
                                    translation,
                                    message,
                                    skeleton.appleTargetLocale(),
                                    slot.selector().substring("@device=".length()),
                                    slot.variant())
                                : skeleton.appleTargetLocale() != null
                                        && slot.selector() == null
                                        && slot.variant() != null
                                    ? restoreTargetPlural(
                                        translation,
                                        message,
                                        skeleton.appleTargetLocale(),
                                        slot.variant())
                                    : "@device".equals(slot.selector())
                                        ? restoreDeviceRoot(translation, message, slot.variant())
                                        : slot.selector() != null
                                                && slot.selector().startsWith("@device=")
                                            ? restoreDevicePlural(
                                                translation,
                                                message,
                                                slot.selector().substring("@device=".length()),
                                                slot.variant())
                                            : slot.selector() == null && hasSubstitutions(message)
                                                ? restoreSubstitutionRoot(translation, message)
                                                : slot.selector() != null && slot.variant() != null
                                                    ? AppleXcstringsWriter.restore(
                                                        translation,
                                                        message,
                                                        slot.selector(),
                                                        slot.variant())
                                                    : slot.variant() != null
                                                        ? AppleXcstringsWriter.restore(
                                                            translation, message, slot.variant())
                                                        : AppleXcstringsWriter.restore(
                                                            translation, message);
        try {
          String originalValue =
              new String(original, slot.start(), slot.end() - slot.start(), encoding.charset());
          String replacementValue;
          if ("null".equals(originalValue) || originalValue.isEmpty()) {
            if (insertionOwners == null) {
              insertionOwners = extractWithSourceInsertion(original).slots();
            }
            if (slot.selector() != null
                || slot.variant() != null
                || skeleton.appleTargetLocale() == null
                    && message.metadata() != null
                    && message.metadata().containsKey("appleSourceLocalization")
                || !insertionOwners.contains(slot)) {
              throw invalid(
                  "INVALID_SKELETON", "Xcode insertion slot does not own a source locale");
            }
            ObjectNode inserted;
            if (targetSubstitutionInsertion) {
              inserted =
                  insertedTargetSubstitution(
                      translation, message, catalog, skeleton.appleTargetLocale());
            } else if (targetDeviceInsertion) {
              inserted =
                  insertedTargetDevice(translation, message, catalog, skeleton.appleTargetLocale());
            } else if (targetPluralInsertion) {
              inserted =
                  insertedTargetPlural(translation, message, catalog, skeleton.appleTargetLocale());
            } else {
              inserted = JSON.createObjectNode();
              inserted.putObject("stringUnit").put("state", "translated").put("value", nativeValue);
            }
            replacementValue = JSON.writeValueAsString(inserted);
            if (originalValue.isEmpty()) {
              String sourceIdentifier =
                  skeleton.appleTargetLocale() != null
                      ? skeleton.appleTargetLocale()
                      : message.metadata() != null
                              && message.metadata().get("appleSourceLanguage")
                                  instanceof String value
                          ? value
                          : catalog.locale();
              replacementValue =
                  "," + JSON.writeValueAsString(sourceIdentifier) + ":" + replacementValue;
            }
          } else {
            String quoted = JSON.writeValueAsString(nativeValue);
            replacementValue = quoted.substring(1, quoted.length() - 1);
          }
          byte[] replacement = replacementValue.getBytes(encoding.charset());
          result.write(replacement, 0, replacement.length);
        } catch (IOException exception) {
          throw new LocalizationParseException(
              "INVALID_SKELETON", "Cannot serialize source-preserving Xcode JSON value", exception);
        }
      }
      previous = slot.end();
    }
    result.write(original, previous, original.length - previous);
    return result.toByteArray();
  }

  private static Map<List<String>, SlotIdentity> expectedPaths(
      JsonNode root,
      LocalizationCatalog catalog,
      boolean allDevices,
      boolean insertSourceLocales,
      String targetLocale) {
    Map<List<String>, SlotIdentity> result = new HashMap<>();
    for (Map.Entry<String, LocalizationMessage> entry : catalog.messages().entrySet()) {
      String id = entry.getKey();
      LocalizationMessage message = entry.getValue();
      JsonNode localizations = root.path("strings").path(id).path("localizations");
      if (targetLocale != null) {
        JsonNode target = localizations.path(targetLocale);
        if (hasSubstitutions(message)) {
          if (target.isMissingNode() || target.isNull()) {
            for (String selector : sourceSubstitutions(message).keySet()) {
              if (targetSubstitutionEvidence(catalog, targetLocale, selector, message) == null
                  && targetPluralCategories(catalog, targetLocale).isEmpty()) {
                throw invalid(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Xcode target substitution insertion requires native category evidence");
              }
            }
            result.put(
                target.isNull()
                    ? List.of("strings", id, "localizations", targetLocale)
                    : List.of("strings", id, "localizations"),
                new SlotIdentity(id, null, null));
            continue;
          }
          JsonNode substitutions = target.path("substitutions");
          if (!substitutions.isObject()
              || substitutions.size() != sourceSubstitutions(message).size()) {
            throw invalid(
                "UNSUPPORTED_SKELETON_SOURCE", "Xcode target requires matching substitutions");
          }
          for (String selector : sourceSubstitutions(message).keySet()) {
            JsonNode plural = substitutions.path(selector).path("variations").path("plural");
            if (!plural.isObject()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE",
                  "Xcode target substitution requires plural values");
            }
            if (!plural.has("other")) {
              throw invalid(
                  "MISSING_OTHER_VARIANT", "Xcode target substitution plural requires other");
            }
            var categories = plural.fields();
            while (categories.hasNext()) {
              var category = categories.next();
              if (!category.getValue().path("stringUnit").path("value").isTextual()) {
                throw invalid(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Xcode target substitution requires string-valued branches");
              }
              result.put(
                  List.of(
                      "strings",
                      id,
                      "localizations",
                      targetLocale,
                      "substitutions",
                      selector,
                      "variations",
                      "plural",
                      category.getKey(),
                      "stringUnit",
                      "value"),
                  new SlotIdentity(id, selector, category.getKey()));
            }
          }
          JsonNode sourceLocalizations = root.path("strings").path(id).path("localizations");
          String sourceIdentifier =
              AppleXcstringsParser.sourceLocalization(
                  sourceLocalizations, root.path("sourceLanguage").asText());
          JsonNode sourceDevices =
              sourceLocalizations.path(sourceIdentifier).path("variations").path("device");
          if (target.path("stringUnit").path("value").isTextual()) {
            if (sourceDevices.isObject()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE",
                  "Xcode target substitution requires matching device roots");
            }
            result.put(
                List.of("strings", id, "localizations", targetLocale, "stringUnit", "value"),
                new SlotIdentity(id, null, null));
          } else {
            JsonNode devices = target.path("variations").path("device");
            if (!devices.isObject()
                || devices.isEmpty()
                || !sourceDevices.isObject()
                || devices.size() != sourceDevices.size()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE",
                  "Xcode target substitution requires matching scalar or device roots");
            }
            var branches = devices.fields();
            while (branches.hasNext()) {
              var device = branches.next();
              if (!sourceDevices.has(device.getKey())
                  || !device.getValue().path("stringUnit").path("value").isTextual()) {
                throw invalid(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Xcode target substitution device requires a matching scalar root");
              }
              result.put(
                  List.of(
                      "strings",
                      id,
                      "localizations",
                      targetLocale,
                      "variations",
                      "device",
                      device.getKey(),
                      "stringUnit",
                      "value"),
                  new SlotIdentity(id, "@device", device.getKey()));
            }
          }
          continue;
        }
        if (message.metadata() != null && message.metadata().containsKey("sourceVariationAxes")) {
          if (target.isMissingNode() || target.isNull()) {
            if (message.variants() != null
                && targetPluralCategories(catalog, targetLocale).isEmpty()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE",
                  "Xcode target device plural insertion requires native category evidence");
            }
            result.put(
                target.isNull()
                    ? List.of("strings", id, "localizations", targetLocale)
                    : List.of("strings", id, "localizations"),
                new SlotIdentity(id, null, null));
            continue;
          }
          JsonNode devices = target.path("variations").path("device");
          if (!devices.isObject() || devices.isEmpty()) {
            throw invalid(
                "UNSUPPORTED_SKELETON_SOURCE", "Xcode target locale requires device branches");
          }
          var branches = devices.fields();
          while (branches.hasNext()) {
            var device = branches.next();
            JsonNode scalar = device.getValue().path("stringUnit").path("value");
            if (scalar.isTextual() && message.variants() == null) {
              result.put(
                  List.of(
                      "strings",
                      id,
                      "localizations",
                      targetLocale,
                      "variations",
                      "device",
                      device.getKey(),
                      "stringUnit",
                      "value"),
                  new SlotIdentity(id, "@device", device.getKey()));
              continue;
            }
            JsonNode plural = device.getValue().path("variations").path("plural");
            if (!plural.isObject() || message.variants() == null) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE",
                  "Xcode target device requires matching scalar or plural branches");
            }
            if (!plural.has("other")) {
              throw invalid("MISSING_OTHER_VARIANT", "Xcode target device plural requires other");
            }
            var categories = plural.fields();
            while (categories.hasNext()) {
              var category = categories.next();
              if (!category.getValue().path("stringUnit").path("value").isTextual()) {
                throw invalid(
                    "UNSUPPORTED_SKELETON_SOURCE",
                    "Xcode target device plural requires string-valued branches");
              }
              result.put(
                  List.of(
                      "strings",
                      id,
                      "localizations",
                      targetLocale,
                      "variations",
                      "device",
                      device.getKey(),
                      "variations",
                      "plural",
                      category.getKey(),
                      "stringUnit",
                      "value"),
                  new SlotIdentity(id, "@device=" + device.getKey(), category.getKey()));
            }
          }
          continue;
        }
        if (message.variants() != null) {
          JsonNode plural = target.path("variations").path("plural");
          if (!plural.isObject()) {
            if (!target.isMissingNode() && !target.isNull()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE", "Xcode target locale requires plural branches");
            }
            if (targetPluralCategories(catalog, targetLocale).isEmpty()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE",
                  "Xcode target plural insertion requires native category evidence");
            }
            result.put(
                target.isNull()
                    ? List.of("strings", id, "localizations", targetLocale)
                    : List.of("strings", id, "localizations"),
                new SlotIdentity(id, null, null));
            continue;
          }
          if (!plural.has("other")) {
            throw invalid("MISSING_OTHER_VARIANT", "Xcode target plural requires other");
          }
          plural
              .fields()
              .forEachRemaining(
                  category -> {
                    if (!category.getValue().path("stringUnit").path("value").isTextual()) {
                      throw invalid(
                          "UNSUPPORTED_SKELETON_SOURCE",
                          "Xcode target plural requires string-valued branches");
                    }
                    result.put(
                        List.of(
                            "strings",
                            id,
                            "localizations",
                            targetLocale,
                            "variations",
                            "plural",
                            category.getKey(),
                            "stringUnit",
                            "value"),
                        new SlotIdentity(id, null, category.getKey()));
                  });
          continue;
        }
        if (target.isMissingNode()) {
          result.put(List.of("strings", id, "localizations"), new SlotIdentity(id, null, null));
        } else if (target.isNull()) {
          result.put(
              List.of("strings", id, "localizations", targetLocale),
              new SlotIdentity(id, null, null));
        } else if (target.path("stringUnit").path("value").isTextual()) {
          result.put(
              List.of("strings", id, "localizations", targetLocale, "stringUnit", "value"),
              new SlotIdentity(id, null, null));
        } else {
          throw invalid(
              "UNSUPPORTED_SKELETON_SOURCE", "Xcode target locale requires a scalar string unit");
        }
        continue;
      }
      String identifier =
          AppleXcstringsParser.sourceLocalization(
              localizations, root.get("sourceLanguage").asText());
      JsonNode localization = localizations.path(identifier);
      if (!localization.isObject()) {
        if (insertSourceLocales) {
          if (localization.isNull()) {
            result.put(
                List.of("strings", id, "localizations", identifier),
                new SlotIdentity(id, null, null));
            continue;
          }
          if (localization.isMissingNode()) {
            result.put(List.of("strings", id, "localizations"), new SlotIdentity(id, null, null));
            continue;
          }
        }
        throw invalid(
            "UNSUPPORTED_SKELETON_SOURCE", "Xcode fallback keys have no source-locale value");
      }
      List<String> prefix = new ArrayList<>(List.of("strings", id, "localizations", identifier));
      List<String> localizationPrefix = List.copyOf(prefix);
      String device =
          message.metadata() != null
                  && message.metadata().get("defaultDevice") instanceof String idValue
              ? idValue
              : null;
      boolean topLevelPlural = localization.path("variations").path("plural").isObject();
      if (device != null && !topLevelPlural) {
        JsonNode devices = localization.path("variations").path("device");
        if (allDevices) {
          var branches = devices.fields();
          while (branches.hasNext()) {
            var branch = branches.next();
            if (message.variants() == null) {
              if (!branch.getValue().path("stringUnit").path("value").isTextual()) {
                throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Xcode device source value");
              }
              List<String> path = new ArrayList<>(prefix);
              path.addAll(List.of("variations", "device", branch.getKey(), "stringUnit", "value"));
              result.put(List.copyOf(path), new SlotIdentity(id, "@device", branch.getKey()));
            } else {
              JsonNode plural = branch.getValue().path("variations").path("plural");
              if (!plural.isObject()) {
                throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Xcode device plural axis");
              }
              var categories = plural.fields();
              while (categories.hasNext()) {
                var category = categories.next();
                if (!category.getValue().path("stringUnit").path("value").isTextual()) {
                  throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing Xcode device plural value");
                }
                List<String> path = new ArrayList<>(prefix);
                path.addAll(
                    List.of(
                        "variations",
                        "device",
                        branch.getKey(),
                        "variations",
                        "plural",
                        category.getKey(),
                        "stringUnit",
                        "value"));
                result.put(
                    List.copyOf(path),
                    new SlotIdentity(id, "@device=" + branch.getKey(), category.getKey()));
              }
            }
          }
        }
        prefix.addAll(List.of("variations", "device", device));
        localization = devices.path(device);
      }
      if (message.variants() != null) {
        if (hasSubstitutions(message)) {
          throw invalid(
              "UNSUPPORTED_SKELETON_SOURCE",
              "Xcode top-level plurals with substitutions require nested-axis ownership");
        }
        JsonNode plural = localization.path("variations").path("plural");
        if (!plural.isObject()) {
          throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing source-owned Xcode plural axis");
        }
        if (!allDevices || device == null) {
          for (String category : message.variants().keySet()) {
            if (!plural.path(category).path("stringUnit").path("value").isTextual()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE", "Missing source-owned Xcode plural value");
            }
            List<String> path = new ArrayList<>(prefix);
            path.addAll(List.of("variations", "plural", category, "stringUnit", "value"));
            result.put(List.copyOf(path), new SlotIdentity(id, null, category));
          }
        }
      } else {
        if (!localization.path("stringUnit").path("value").isTextual()) {
          throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing source-owned Xcode scalar value");
        }
        if (!allDevices || device == null) {
          prefix.addAll(List.of("stringUnit", "value"));
          result.put(List.copyOf(prefix), new SlotIdentity(id, null, null));
        }
        JsonNode substitutions =
            root.path("strings")
                .path(id)
                .path("localizations")
                .path(identifier)
                .path("substitutions");
        if (hasSubstitutions(message)) {
          if (!substitutions.isObject()) {
            throw invalid(
                "UNSUPPORTED_SKELETON_SOURCE", "Missing source-owned Xcode substitutions");
          }
          for (Map.Entry<String, Object> substitution : sourceSubstitutions(message).entrySet()) {
            JsonNode plural =
                substitutions.path(substitution.getKey()).path("variations").path("plural");
            if (!plural.isObject()) {
              throw invalid(
                  "UNSUPPORTED_SKELETON_SOURCE", "Missing Xcode substitution plural axis");
            }
            var categories = plural.fields();
            while (categories.hasNext()) {
              var category = categories.next();
              if (!category.getValue().path("stringUnit").path("value").isTextual()) {
                throw invalid(
                    "UNSUPPORTED_SKELETON_SOURCE", "Missing Xcode substitution plural value");
              }
              List<String> path = new ArrayList<>(localizationPrefix);
              path.addAll(
                  List.of(
                      "substitutions",
                      substitution.getKey(),
                      "variations",
                      "plural",
                      category.getKey(),
                      "stringUnit",
                      "value"));
              result.put(
                  List.copyOf(path),
                  new SlotIdentity(id, substitution.getKey(), category.getKey()));
            }
          }
        }
      }
    }
    return result;
  }

  private static String targetLocale(JsonNode root, String requested) {
    String normalized = requested.replace('_', '-');
    String nativeLocale = AppleXcstringsParser.nativeBundleLocale(requested);
    String existing = null;
    String fallback = null;
    if (!LOCALE.matcher(normalized).matches()
        || nativeLocale.equals(
            AppleXcstringsParser.nativeBundleLocale(root.path("sourceLanguage").asText()))) {
      throw invalid("INVALID_XCSTRINGS_LOCALE", "Invalid or source-owned Xcode target locale");
    }
    var entries = root.path("strings").elements();
    while (entries.hasNext()) {
      var localizations = entries.next().path("localizations").fieldNames();
      while (localizations.hasNext()) {
        String locale = localizations.next();
        if (AppleXcstringsParser.nativeBundleLocale(locale).equals(nativeLocale)) {
          if (existing != null && !existing.equals(locale)) {
            throw invalid("INVALID_XCSTRINGS_LOCALE", "Ambiguous normalized Xcode target locale");
          }
          existing = locale;
        } else if (locale.replace('_', '-').equals(normalized)) {
          if (fallback != null && !fallback.equals(locale)) {
            throw invalid("INVALID_XCSTRINGS_LOCALE", "Ambiguous normalized Xcode target locale");
          }
          fallback = locale;
        }
      }
    }
    return existing != null ? existing : fallback != null ? fallback : requested;
  }

  private void scan(JsonParser parser, List<String> path) throws IOException {
    JsonToken token = parser.currentToken();
    if (token == JsonToken.START_OBJECT) {
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String field = parser.currentName();
        parser.nextToken();
        path.add(field);
        scan(parser, path);
        path.remove(path.size() - 1);
      }
      SlotIdentity identity = expected.get(path);
      if (identity != null) {
        int insertion = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
        while (insertion > 0
            && switch (source.charAt(insertion - 1)) {
              case ' ', '\t', '\r', '\n' -> true;
              default -> false;
            }) {
          insertion--;
        }
        int offset = encoding.offset(source, insertion);
        slots.add(
            new LocalizationSourceSlot(
                identity.id(), identity.selector(), identity.variant(), offset, offset));
      }
    } else if (token == JsonToken.START_ARRAY) {
      int index = 0;
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        path.add(Integer.toString(index++));
        scan(parser, path);
        path.remove(path.size() - 1);
      }
    } else if (token == JsonToken.VALUE_STRING) {
      SlotIdentity identity = expected.get(path);
      if (identity != null) {
        int opening = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
        int closing = closingQuote(opening);
        slots.add(
            new LocalizationSourceSlot(
                identity.id(),
                identity.selector(),
                identity.variant(),
                encoding.offset(source, opening + 1),
                encoding.offset(source, closing)));
      }
    } else if (token == JsonToken.VALUE_NULL) {
      SlotIdentity identity = expected.get(path);
      if (identity != null) {
        int start = Math.toIntExact(parser.currentTokenLocation().getCharOffset());
        slots.add(
            new LocalizationSourceSlot(
                identity.id(),
                identity.selector(),
                identity.variant(),
                encoding.offset(source, start),
                encoding.offset(source, start + "null".length())));
      }
    }
  }

  private int closingQuote(int start) {
    boolean escaped = false;
    for (int position = start + 1; position < source.length(); position++) {
      char value = source.charAt(position);
      if (escaped) {
        escaped = false;
      } else if (value == '\\') {
        escaped = true;
      } else if (value == '"') {
        return position;
      }
    }
    throw invalid("INVALID_SKELETON", "Unterminated Xcode JSON string value");
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private static boolean owns(LocalizationMessage message, LocalizationSourceSlot slot) {
    if (slot.selector() != null) {
      if ("@device".equals(slot.selector())) {
        return slot.variant() != null
            && message.metadata() != null
            && JSON.valueToTree(message.metadata().get("sourceVariationAxes"))
                .path("device")
                .path(slot.variant())
                .path("stringUnit")
                .path("value")
                .isTextual();
      }
      if (slot.selector().startsWith("@device=")) {
        return slot.variant() != null
            && message.metadata() != null
            && JSON.valueToTree(message.metadata().get("sourceVariationAxes"))
                .path("device")
                .path(slot.selector().substring("@device=".length()))
                .path("variations")
                .path("plural")
                .path(slot.variant())
                .path("stringUnit")
                .path("value")
                .isTextual();
      }
      return slot.variant() != null
          && JSON.valueToTree(sourceSubstitutions(message))
              .path(slot.selector())
              .path("variations")
              .path("plural")
              .path(slot.variant())
              .path("stringUnit")
              .path("value")
              .isTextual();
    }
    return slot.variant() == null
        || message.variants() != null && message.variants().containsKey(slot.variant());
  }

  private static boolean hasSubstitutions(LocalizationMessage message) {
    return !sourceSubstitutions(message).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sourceSubstitutions(LocalizationMessage message) {
    return message.metadata() != null
            && message.metadata().get("sourceSubstitutions") instanceof Map<?, ?> substitutions
        ? (Map<String, Object>) substitutions
        : Map.of();
  }

  private static String restoreSubstitutionRoot(String translated, LocalizationMessage message) {
    return restoreSubstitutionRoot(translated, message, null);
  }

  private static String restoreDeviceRoot(
      String translated, LocalizationMessage message, String device) {
    JsonNode source =
        JSON.valueToTree(message.metadata().get("sourceVariationAxes"))
            .path("device")
            .path(device)
            .path("stringUnit")
            .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing source-owned Xcode device branch");
    }
    if (hasSubstitutions(message)) {
      return restoreSubstitutionRoot(translated, message, device);
    }
    return AppleXcstringsWriter.restore(
        translated, AppleStringsParser.message(source.asText(), null));
  }

  private static String restoreDevicePlural(
      String translated, LocalizationMessage message, String device, String category) {
    JsonNode source =
        JSON.valueToTree(message.metadata().get("sourceVariationAxes"))
            .path("device")
            .path(device)
            .path("variations")
            .path("plural")
            .path(category)
            .path("stringUnit")
            .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing source-owned Xcode device plural branch");
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized =
        PlaceholderNormalizer.normalizeFoundationPlural(
            source.asText(), placeholders, "count", null);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPluralPrintfLineSeparators(source.asText(), "count", null);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    LocalizationMessage scoped =
        LocalizationMessage.of(normalized, null, null, placeholders, metadata);
    return AppleXcstringsWriter.restore(translated, scoped);
  }

  private static String restoreTargetPlural(
      String translated, LocalizationMessage message, String targetLocale, String category) {
    JsonNode source =
        JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
            .path(targetLocale.replace('_', '-'))
            .path("variations")
            .path("plural")
            .path(category)
            .path("stringUnit")
            .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing target-owned Xcode plural branch");
    }
    return restoreTargetPluralValue(translated, source.asText());
  }

  private static String restoreTargetDeviceRoot(
      String translated, LocalizationMessage message, String targetLocale, String device) {
    JsonNode source =
        JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
            .path(targetLocale.replace('_', '-'))
            .path("variations")
            .path("device")
            .path(device)
            .path("stringUnit")
            .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing target-owned Xcode device branch");
    }
    return restoreTargetDeviceRootValue(translated, source.asText());
  }

  private static String restoreTargetSubstitutionRoot(
      String translated, LocalizationMessage message, String targetLocale, String device) {
    JsonNode target =
        JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
            .path(targetLocale.replace('_', '-'));
    JsonNode source =
        device == null
            ? target.path("stringUnit").path("value")
            : target
                .path("variations")
                .path("device")
                .path(device)
                .path("stringUnit")
                .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing target-owned Xcode substitution root");
    }
    Map<String, ArrayDeque<String>> owned = new HashMap<>();
    Matcher nativeMarkers = SUBSTITUTION.matcher(source.asText());
    while (nativeMarkers.find()) {
      owned
          .computeIfAbsent(nativeMarkers.group(1), ignored -> new ArrayDeque<>())
          .add(nativeMarkers.group());
    }
    Matcher arguments = ARGUMENT.matcher(translated);
    StringBuffer restored = new StringBuffer();
    while (arguments.find()) {
      String selector = arguments.group(1);
      if (sourceSubstitutions(message).containsKey(selector)) {
        ArrayDeque<String> markers = owned.get(selector);
        if (markers == null || markers.isEmpty()) {
          throw invalid("INVALID_SKELETON_SUBSTITUTION", "Duplicated Xcode target substitution");
        }
        arguments.appendReplacement(restored, Matcher.quoteReplacement(markers.removeFirst()));
      }
    }
    arguments.appendTail(restored);
    if (owned.values().stream().anyMatch(markers -> !markers.isEmpty())) {
      throw invalid("INVALID_SKELETON_SUBSTITUTION", "Missing Xcode target substitution");
    }
    return AppleXcstringsWriter.restore(restored.toString(), message);
  }

  private static String restoreTargetSubstitutionCategory(
      String translated,
      LocalizationMessage message,
      String targetLocale,
      String selector,
      String category) {
    JsonNode definition =
        JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
            .path(targetLocale.replace('_', '-'))
            .path("substitutions")
            .path(selector);
    return restoreTargetSubstitutionCategory(translated, definition, selector, category);
  }

  private static String restoreTargetSubstitutionCategory(
      String translated, JsonNode definition, String selector, String category) {
    JsonNode source =
        definition
            .path("variations")
            .path("plural")
            .path(category)
            .path("stringUnit")
            .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing target-owned Xcode substitution category");
    }
    int position = definition.path("argNum").asInt();
    if (position <= 0) {
      throw invalid("INVALID_SKELETON", "Missing target-owned Xcode substitution argument");
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized =
        PlaceholderNormalizer.normalizeFoundationSubstitution(
            source.asText(), placeholders, selector, position);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationSubstitutionPrintfLineSeparators(
            source.asText(), selector, position);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put(
          "appleDisabledPrintfConversions",
          targetDisabledConversions(normalized, translated, disabled));
      normalized = translated;
    }
    LocalizationMessage scoped =
        LocalizationMessage.of(normalized, null, null, placeholders, metadata);
    return AppleXcstringsWriter.restore(translated, scoped);
  }

  private static String restoreTargetDeviceRootValue(String translated, String original) {
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized = PlaceholderNormalizer.normalizeFoundation(original, placeholders);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPrintfLineSeparators(original);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put(
          "appleDisabledPrintfConversions",
          targetDisabledConversions(normalized, translated, disabled));
      normalized = translated;
    }
    LocalizationMessage scoped =
        LocalizationMessage.of(normalized, null, null, placeholders, metadata);
    return AppleXcstringsWriter.restore(translated, scoped);
  }

  private static String restoreTargetDevicePlural(
      String translated,
      LocalizationMessage message,
      String targetLocale,
      String device,
      String category) {
    JsonNode source =
        JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
            .path(targetLocale.replace('_', '-'))
            .path("variations")
            .path("device")
            .path(device)
            .path("variations")
            .path("plural")
            .path(category)
            .path("stringUnit")
            .path("value");
    if (!source.isTextual()) {
      throw invalid("INVALID_SKELETON", "Missing target-owned Xcode device plural branch");
    }
    return restoreTargetPluralValue(translated, source.asText());
  }

  private static String restoreTargetPluralValue(String translated, String original) {
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    String normalized =
        PlaceholderNormalizer.normalizeFoundationPlural(original, placeholders, "count", null);
    List<Map<String, Object>> conversions =
        PlaceholderNormalizer.foundationPluralPrintfLineSeparators(original, "count", null);
    Map<String, Object> metadata = new HashMap<>();
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put(
          "appleDisabledPrintfConversions",
          targetDisabledConversions(normalized, translated, disabled));
      normalized = translated;
    }
    LocalizationMessage scoped =
        LocalizationMessage.of(normalized, null, null, placeholders, metadata);
    return AppleXcstringsWriter.restore(translated, scoped);
  }

  private static ObjectNode insertedTargetSubstitution(
      String translation,
      LocalizationMessage message,
      LocalizationCatalog catalog,
      String targetLocale) {
    JsonNode source = JSON.valueToTree(message.metadata().get("appleSourceLocalization"));
    JsonNode sourceDevices = source.path("variations").path("device");
    Map<String, SubstitutionTranslation> branches = new TreeMap<>();
    if (sourceDevices.isObject()) {
      Map<String, String> devices = targetDeviceBranches(translation);
      Set<String> required = new HashSet<>();
      sourceDevices.fieldNames().forEachRemaining(required::add);
      required.add("other");
      String fallback = (String) message.metadata().get("defaultDevice");
      if (!devices.keySet().equals(required)
          || fallback == null
          || !devices.get("other").equals(devices.get(fallback))) {
        throw invalid("INVALID_SKELETON", "Xcode target substitutions require all source devices");
      }
      for (Map.Entry<String, String> device : devices.entrySet()) {
        if (!"other".equals(device.getKey())) {
          branches.put(device.getKey(), targetSubstitutionBranches(device.getValue(), message));
        }
      }
    } else {
      branches.put("", targetSubstitutionBranches(translation, message));
    }
    SubstitutionTranslation first = branches.values().iterator().next();
    if (branches.values().stream()
        .anyMatch(branch -> !branch.categories().equals(first.categories()))) {
      throw invalid("INVALID_SKELETON", "Device roots require identical shared substitution rules");
    }

    Set<String> placeholders = new HashSet<>();
    if (message.placeholders() != null) {
      message.placeholders().forEach(placeholder -> placeholders.add(placeholder.name()));
    }
    ObjectNode target = JSON.createObjectNode();
    if (!sourceDevices.isObject()) {
      validateTargetArguments(first.root(), placeholders);
      target
          .putObject("stringUnit")
          .put("state", "translated")
          .put("value", restoreSubstitutionRoot(first.root(), message, null));
    }

    ObjectNode substitutions = target.putObject("substitutions");
    for (String selector : new TreeMap<>(sourceSubstitutions(message)).keySet()) {
      JsonNode evidence = targetSubstitutionEvidence(catalog, targetLocale, selector, message);
      Set<String> required = new HashSet<>();
      if (evidence == null) {
        required.addAll(CldrCardinalCategories.forLocale(targetLocale));
      } else {
        evidence.path("variations").path("plural").fieldNames().forEachRemaining(required::add);
      }
      if (required.isEmpty()) {
        throw invalid(
            "UNSUPPORTED_SKELETON_SOURCE", "Missing target substitution category evidence");
      }
      Map<String, String> categories = first.categories().get(selector);
      if (categories == null || !categories.containsKey("other")) {
        throw invalid("MISSING_OTHER_VARIANT", "Xcode target substitution requires other");
      }
      if (!categories.keySet().equals(required)) {
        throw invalid(
            "INVALID_SKELETON", "Xcode target substitution categories differ from evidence");
      }
      JsonNode original = JSON.valueToTree(sourceSubstitutions(message).get(selector));
      if (!original.isObject()) {
        throw invalid("INVALID_SKELETON", "Missing source-owned Xcode substitution definition");
      }
      ObjectNode definition = ((ObjectNode) original).deepCopy();
      ObjectNode variations = (ObjectNode) definition.path("variations");
      ObjectNode plural = variations.putObject("plural");
      for (Map.Entry<String, String> category : new TreeMap<>(categories).entrySet()) {
        validateTargetArguments(category.getValue(), placeholders);
        plural
            .putObject(category.getKey())
            .putObject("stringUnit")
            .put("state", "translated")
            .put(
                "value",
                restoreTargetSubstitutionCategory(
                    category.getValue(),
                    evidence == null ? original : evidence,
                    selector,
                    evidence == null
                            && !original.path("variations").path("plural").has(category.getKey())
                        ? "other"
                        : category.getKey()));
      }
      substitutions.set(selector, definition);
    }

    if (sourceDevices.isObject()) {
      ObjectNode devices = target.putObject("variations").putObject("device");
      for (Map.Entry<String, SubstitutionTranslation> device : branches.entrySet()) {
        validateTargetArguments(device.getValue().root(), placeholders);
        devices
            .putObject(device.getKey())
            .putObject("stringUnit")
            .put("state", "translated")
            .put(
                "value",
                restoreSubstitutionRoot(device.getValue().root(), message, device.getKey()));
      }
    }
    return target;
  }

  private static SubstitutionTranslation targetSubstitutionBranches(
      String translation, LocalizationMessage message) {
    Map<String, Map<String, String>> categories = new HashMap<>();
    StringBuilder root = new StringBuilder();
    int cursor = 0;
    while (cursor < translation.length()) {
      int opening = translation.indexOf('{', cursor);
      if (opening < 0) {
        root.append(translation, cursor, translation.length());
        break;
      }
      root.append(translation, cursor, opening);
      int comma = translation.indexOf(',', opening + 1);
      int simple = translation.indexOf('}', opening + 1);
      if (comma < 0 || simple >= 0 && simple < comma) {
        if (simple < 0) {
          throw invalid("INVALID_SKELETON", "Unclosed Xcode target substitution argument");
        }
        root.append(translation, opening, simple + 1);
        cursor = simple + 1;
        continue;
      }
      String selector = translation.substring(opening + 1, comma).trim();
      String prefix = "{" + selector + ", plural,";
      if (!translation.startsWith(prefix, opening)
          || !sourceSubstitutions(message).containsKey(selector)) {
        throw invalid(
            "INVALID_SKELETON_SUBSTITUTION", "Unknown Xcode target substitution selector");
      }
      int end = opening + prefix.length();
      int depth = 1;
      while (end < translation.length() && depth > 0) {
        char character = translation.charAt(end++);
        if (character == '{') {
          depth++;
        } else if (character == '}') {
          depth--;
        }
      }
      if (depth != 0) {
        throw invalid("INVALID_SKELETON", "Unclosed Xcode target substitution plural");
      }
      Map<String, String> variants =
          targetPluralBranches(
              "{count, plural," + translation.substring(opening + prefix.length(), end));
      Map<String, String> previous = categories.putIfAbsent(selector, variants);
      if (previous != null && !previous.equals(variants)) {
        throw invalid("INVALID_SKELETON", "Repeated Xcode target substitution has different rules");
      }
      root.append('{').append(selector).append('}');
      cursor = end;
    }
    if (!categories.keySet().equals(sourceSubstitutions(message).keySet())) {
      throw invalid("INVALID_SKELETON_SUBSTITUTION", "Missing Xcode target substitution selector");
    }
    return new SubstitutionTranslation(root.toString(), categories);
  }

  private static JsonNode targetSubstitutionEvidence(
      LocalizationCatalog catalog,
      String targetLocale,
      String selector,
      LocalizationMessage source) {
    JsonNode required = JSON.valueToTree(sourceSubstitutions(source).get(selector));
    for (LocalizationMessage message : catalog.messages().values()) {
      if (message.metadata() == null) {
        continue;
      }
      JsonNode definition =
          JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
              .path(targetLocale.replace('_', '-'))
              .path("substitutions")
              .path(selector);
      JsonNode categories = definition.path("variations").path("plural");
      if (categories.isObject()
          && categories.has("other")
          && definition.path("argNum").equals(required.path("argNum"))
          && definition.path("formatSpecifier").equals(required.path("formatSpecifier"))) {
        return definition;
      }
    }
    return null;
  }

  private static ObjectNode insertedTargetPlural(
      String translation,
      LocalizationMessage message,
      LocalizationCatalog catalog,
      String targetLocale) {
    Map<String, String> branches = targetPluralBranches(translation);
    Set<String> required = targetPluralCategories(catalog, targetLocale);
    if (!branches.containsKey("other")) {
      throw invalid("MISSING_OTHER_VARIANT", "Xcode target plural insertion requires other");
    }
    if (!branches.keySet().equals(required)) {
      throw invalid(
          "INVALID_SKELETON", "Xcode target plural categories differ from native evidence");
    }
    Set<String> placeholders = new HashSet<>();
    if (message.placeholders() != null) {
      message.placeholders().forEach(placeholder -> placeholders.add(placeholder.name()));
    }
    ObjectNode target = JSON.createObjectNode();
    ObjectNode plural = target.putObject("variations").putObject("plural");
    JsonNode originals =
        JSON.valueToTree(message.metadata().get("appleSourceLocalization"))
            .path("variations")
            .path("plural");
    for (Map.Entry<String, String> branch : new TreeMap<>(branches).entrySet()) {
      Matcher arguments = ARGUMENT.matcher(branch.getValue());
      while (arguments.find()) {
        if (!placeholders.contains(arguments.group(1))) {
          throw invalid("INVALID_PLACEHOLDER", "Unknown Xcode target plural argument");
        }
      }
      String stripped = ARGUMENT.matcher(branch.getValue()).replaceAll("");
      if (stripped.indexOf('{') >= 0 || stripped.indexOf('}') >= 0) {
        throw invalid("INVALID_SKELETON", "Unsupported nested Xcode target plural argument");
      }
      JsonNode original =
          originals
              .path(message.variants().containsKey(branch.getKey()) ? branch.getKey() : "other")
              .path("stringUnit")
              .path("value");
      if (!original.isTextual()) {
        throw invalid("INVALID_SKELETON", "Missing source-owned target plural template");
      }
      plural
          .putObject(branch.getKey())
          .putObject("stringUnit")
          .put("state", "translated")
          .put("value", restoreTargetPluralValue(branch.getValue(), original.asText()));
    }
    return target;
  }

  private static ObjectNode insertedTargetDevice(
      String translation,
      LocalizationMessage message,
      LocalizationCatalog catalog,
      String targetLocale) {
    Map<String, String> branches = targetDeviceBranches(translation);
    JsonNode devices =
        JSON.valueToTree(message.metadata().get("sourceVariationAxes")).path("device");
    if (!devices.isObject() || devices.isEmpty()) {
      throw invalid("INVALID_SKELETON", "Missing source-owned Xcode device templates");
    }
    Set<String> required = new HashSet<>();
    devices.fieldNames().forEachRemaining(required::add);
    required.add("other");
    if (!branches.keySet().equals(required)) {
      throw invalid("INVALID_SKELETON", "Xcode target devices differ from source-owned templates");
    }
    String fallback = (String) message.metadata().get("defaultDevice");
    boolean explicitFallback = devices.has("other");
    if (fallback == null
        || !devices.has(fallback)
        || !explicitFallback && !branches.get("other").equals(branches.get(fallback))) {
      throw invalid("INVALID_SKELETON", "Xcode device fallback must match its default branch");
    }
    Set<String> placeholders = new HashSet<>();
    if (message.placeholders() != null) {
      message.placeholders().forEach(placeholder -> placeholders.add(placeholder.name()));
    }
    ObjectNode target = JSON.createObjectNode();
    ObjectNode variations = target.putObject("variations").putObject("device");
    TreeMap<String, String> orderedBranches = new TreeMap<>(UNICODE_SCALAR_ORDER);
    orderedBranches.putAll(branches);
    for (String device : orderedBranches.keySet()) {
      if ("other".equals(device) && !explicitFallback) {
        continue;
      }
      String value = branches.get(device);
      JsonNode sourceDevice = devices.path(device);
      if (message.variants() == null) {
        validateTargetArguments(value, placeholders);
        JsonNode original = sourceDevice.path("stringUnit").path("value");
        if (!original.isTextual()) {
          throw invalid("INVALID_SKELETON", "Missing source-owned Xcode device scalar template");
        }
        variations
            .putObject(device)
            .putObject("stringUnit")
            .put("state", "translated")
            .put("value", restoreTargetDeviceRootValue(value, original.asText()));
        continue;
      }
      Map<String, String> categories = targetPluralBranches(value);
      if (!categories.containsKey("other")) {
        throw invalid("MISSING_OTHER_VARIANT", "Xcode target device plural requires other");
      }
      if (!categories.keySet().equals(targetPluralCategories(catalog, targetLocale))) {
        throw invalid("INVALID_SKELETON", "Xcode target device plural lacks native categories");
      }
      ObjectNode plural = variations.putObject(device).putObject("variations").putObject("plural");
      JsonNode originals = sourceDevice.path("variations").path("plural");
      for (Map.Entry<String, String> category : new TreeMap<>(categories).entrySet()) {
        validateTargetArguments(category.getValue(), placeholders);
        JsonNode original =
            originals
                .path(
                    message.variants().containsKey(category.getKey()) ? category.getKey() : "other")
                .path("stringUnit")
                .path("value");
        if (!original.isTextual()) {
          throw invalid("INVALID_SKELETON", "Missing source-owned Xcode device plural template");
        }
        plural
            .putObject(category.getKey())
            .putObject("stringUnit")
            .put("state", "translated")
            .put("value", restoreTargetPluralValue(category.getValue(), original.asText()));
      }
    }
    return target;
  }

  private static void validateTargetArguments(String value, Set<String> placeholders) {
    Matcher arguments = ARGUMENT.matcher(value);
    while (arguments.find()) {
      if (!placeholders.contains(arguments.group(1))) {
        throw invalid("INVALID_PLACEHOLDER", "Unknown Xcode target device argument");
      }
    }
    String stripped = ARGUMENT.matcher(value).replaceAll("");
    if (stripped.indexOf('{') >= 0 || stripped.indexOf('}') >= 0) {
      throw invalid("INVALID_SKELETON", "Unsupported nested Xcode target device argument");
    }
  }

  private static Set<String> targetPluralCategories(
      LocalizationCatalog catalog, String targetLocale) {
    Set<String> result = new HashSet<>();
    String locale = targetLocale.replace('_', '-');
    for (LocalizationMessage message : catalog.messages().values()) {
      if (message.variants() == null || message.metadata() == null) {
        continue;
      }
      JsonNode plural =
          JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
              .path(locale)
              .path("variations")
              .path("plural");
      if (plural.isObject()) {
        plural.fieldNames().forEachRemaining(result::add);
      }
      JsonNode devices =
          JSON.valueToTree(message.metadata().get("appleLocalizationSources"))
              .path(locale)
              .path("variations")
              .path("device");
      if (devices.isObject()) {
        devices
            .elements()
            .forEachRemaining(
                device -> {
                  JsonNode categories = device.path("variations").path("plural");
                  if (categories.isObject()) {
                    categories.fieldNames().forEachRemaining(result::add);
                  }
                });
      }
    }
    if (!result.isEmpty()) {
      return result;
    }
    for (LocalizationMessage message : catalog.messages().values()) {
      if (message.metadata() == null) {
        continue;
      }
      JsonNode localizations = JSON.valueToTree(message.metadata().get("appleLocalizationSources"));
      if (localizations.path(locale).isObject()) {
        return Set.of();
      }
    }
    return CldrCardinalCategories.forLocale(locale);
  }

  private static Map<String, String> targetPluralBranches(String source) {
    String prefix = "{count, plural,";
    if (!source.startsWith(prefix)) {
      throw invalid("INVALID_SKELETON", "Xcode target insertion requires a complete ICU plural");
    }
    Map<String, String> result = new LinkedHashMap<>();
    int cursor = prefix.length();
    boolean closed = false;
    while (cursor < source.length()) {
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
        cursor++;
      }
      if (cursor < source.length() && source.charAt(cursor) == '}') {
        cursor++;
        closed = true;
        break;
      }
      int beginning = cursor;
      while (cursor < source.length() && Character.isLetter(source.charAt(cursor))) {
        cursor++;
      }
      String category = source.substring(beginning, cursor);
      if (!Set.of("zero", "one", "two", "few", "many", "other").contains(category)) {
        throw invalid("INVALID_PLURAL_CATEGORY", "Unsupported Xcode target plural category");
      }
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
        cursor++;
      }
      if (cursor >= source.length() || source.charAt(cursor++) != '{') {
        throw invalid("INVALID_SKELETON", "Invalid Xcode target plural branch");
      }
      int start = cursor;
      int depth = 1;
      while (cursor < source.length() && depth > 0) {
        char value = source.charAt(cursor++);
        if (value == '{') {
          depth++;
        } else if (value == '}') {
          depth--;
        }
      }
      if (depth != 0 || result.put(category, source.substring(start, cursor - 1)) != null) {
        throw invalid("INVALID_SKELETON", "Invalid or duplicated Xcode target plural branch");
      }
    }
    if (!closed || cursor != source.length()) {
      throw invalid("INVALID_SKELETON", "Trailing Xcode target plural content");
    }
    return result;
  }

  private static Map<String, String> targetDeviceBranches(String source) {
    String prefix = "{device, select,";
    if (!source.startsWith(prefix)) {
      throw invalid(
          "INVALID_SKELETON", "Xcode target device insertion requires a complete ICU select");
    }
    Map<String, String> result = new LinkedHashMap<>();
    int cursor = prefix.length();
    boolean closed = false;
    while (cursor < source.length()) {
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
        cursor++;
      }
      if (cursor < source.length() && source.charAt(cursor) == '}') {
        cursor++;
        closed = true;
        break;
      }
      int beginning = cursor;
      while (cursor < source.length()) {
        int codePoint = source.codePointAt(cursor);
        if (UCharacter.hasBinaryProperty(codePoint, UProperty.WHITE_SPACE)
            || UCharacter.hasBinaryProperty(codePoint, UProperty.PATTERN_SYNTAX)) {
          break;
        }
        cursor += Character.charCount(codePoint);
      }
      if (beginning == cursor) {
        throw invalid("INVALID_SKELETON", "Invalid Xcode target device identity");
      }
      String device = source.substring(beginning, cursor);
      while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
        cursor++;
      }
      if (cursor >= source.length() || source.charAt(cursor++) != '{') {
        throw invalid("INVALID_SKELETON", "Invalid Xcode target device branch");
      }
      int beginningValue = cursor;
      int depth = 1;
      while (cursor < source.length() && depth > 0) {
        char value = source.charAt(cursor++);
        if (value == '{') {
          depth++;
        } else if (value == '}') {
          depth--;
        }
      }
      if (depth != 0 || result.put(device, source.substring(beginningValue, cursor - 1)) != null) {
        throw invalid("INVALID_SKELETON", "Invalid or duplicated Xcode target device branch");
      }
    }
    if (!closed || cursor != source.length()) {
      throw invalid("INVALID_SKELETON", "Trailing Xcode target device-select content");
    }
    return result;
  }

  private static List<Map<String, Object>> targetDisabledConversions(
      String original, String translated, List<Map<String, Object>> disabled) {
    int sourceLength = original.codePointCount(0, original.length());
    int targetLength = translated.codePointCount(0, translated.length());
    List<Map<String, Object>> anchored = new ArrayList<>();
    for (Map<String, Object> occurrence : disabled) {
      int position = ((Number) occurrence.get("position")).intValue();
      int targetPosition =
          sourceLength == 0
              ? 0
              : (int) (((long) position * targetLength + sourceLength / 2L) / sourceLength);
      String previous = null;
      int previousEnd = 0;
      Matcher originalArguments = ARGUMENT.matcher(original);
      while (originalArguments.find()) {
        int end = original.codePointCount(0, originalArguments.end());
        if (end > position) {
          break;
        }
        previous = originalArguments.group(1);
        previousEnd = end;
      }
      if (previous != null) {
        Matcher translatedArguments = ARGUMENT.matcher(translated);
        while (translatedArguments.find()) {
          if (previous.equals(translatedArguments.group(1))) {
            targetPosition =
                translated.codePointCount(0, translatedArguments.end()) + position - previousEnd;
            break;
          }
        }
      }
      Map<String, Object> relocated = new HashMap<>(occurrence);
      relocated.put("position", Math.min(targetLength, targetPosition));
      anchored.add(relocated);
    }
    return anchored;
  }

  private static String restoreSubstitutionRoot(
      String translated, LocalizationMessage message, String deviceOverride) {
    JsonNode source = JSON.valueToTree(message.metadata().get("appleSourceLocalization"));
    String device =
        deviceOverride != null
            ? deviceOverride
            : message.metadata().get("defaultDevice") instanceof String selected ? selected : null;
    if (device != null && !source.path("stringUnit").path("value").isTextual()) {
      source = source.path("variations").path("device").path(device);
    }
    String original = source.path("stringUnit").path("value").asText();
    Map<String, ArrayDeque<String>> owned = new HashMap<>();
    Matcher nativeMarkers = SUBSTITUTION.matcher(original);
    while (nativeMarkers.find()) {
      owned
          .computeIfAbsent(nativeMarkers.group(1), ignored -> new ArrayDeque<>())
          .add(nativeMarkers.group());
    }

    Matcher arguments = ARGUMENT.matcher(translated);
    StringBuffer restored = new StringBuffer();
    while (arguments.find()) {
      String selector = arguments.group(1);
      if (sourceSubstitutions(message).containsKey(selector)) {
        ArrayDeque<String> markers = owned.get(selector);
        if (markers == null || markers.isEmpty()) {
          throw invalid("INVALID_SKELETON_SUBSTITUTION", "Duplicated Xcode substitution marker");
        }
        arguments.appendReplacement(restored, Matcher.quoteReplacement(markers.removeFirst()));
      }
    }
    arguments.appendTail(restored);
    if (owned.values().stream().anyMatch(markers -> !markers.isEmpty())) {
      throw invalid("INVALID_SKELETON_SUBSTITUTION", "Missing Xcode substitution marker");
    }
    if (deviceOverride == null) {
      return AppleXcstringsWriter.restore(restored.toString(), message);
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    AppleXcstringsParser.normalizeSource(
        original, JSON.valueToTree(sourceSubstitutions(message)), placeholders);
    Map<String, Object> metadata = new HashMap<>();
    List<Map<String, Object>> conversions = PlaceholderNormalizer.printfLineSeparators(original);
    String normalized = PlaceholderNormalizer.normalize(original, new ArrayList<>());
    if (!conversions.isEmpty()) {
      List<Map<String, Object>> disabled = new ArrayList<>();
      normalized =
          AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, disabled);
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    LocalizationMessage scoped =
        LocalizationMessage.of(normalized, null, null, placeholders, metadata);
    return AppleXcstringsWriter.restore(restored.toString(), scoped);
  }

  private record SlotIdentity(String id, String selector, String variant) {}

  private record SubstitutionTranslation(
      String root, Map<String, Map<String, String>> categories) {}
}
