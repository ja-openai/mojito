package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts modern Xcode String Catalogs while retaining translations and catalog state. */
final class AppleXcstringsParser {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern SUBSTITUTION = Pattern.compile("%(?:(\\d+)\\$)?#@([^@]+)@");
  private static final Pattern SUBSTITUTION_NAME = Pattern.compile("[\\p{L}\\p{N}\\p{M}\\p{So}_]+");

  LocalizationCatalog parse(String source) {
    try {
      JsonNode root = JSON.readTree(source);
      if (root == null
          || !root.isObject()
          || !root.path("sourceLanguage").isTextual()
          || root.path("sourceLanguage").asText().isEmpty()
          || (!root.path("version").isTextual() && !root.path("version").isNumber())
          || !root.path("strings").isObject()) {
        throw invalid("Xcode catalog requires sourceLanguage, version, and a strings object");
      }
      validateDescriptors(root.get("strings"));
      validateUnits(root);
      String sourceIdentifier = root.get("sourceLanguage").asText();
      String sourceLanguage = sourceIdentifier.replace('_', '-');
      Map<String, Object> catalogMetadata = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (!List.of("sourceLanguage", "strings", "version").contains(field.getKey())) {
          catalogMetadata.put(field.getKey(), JSON.convertValue(field.getValue(), Object.class));
        }
      }
      LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.APPLE_XCSTRINGS);
      catalog.setLocale(sourceLanguage);
      Iterator<Map.Entry<String, JsonNode>> entries = root.get("strings").fields();
      while (entries.hasNext()) {
        Map.Entry<String, JsonNode> entry = entries.next();
        parseMessage(
            catalog,
            sourceLanguage,
            sourceIdentifier,
            JSON.convertValue(root.get("version"), Object.class),
            catalogMetadata,
            entry.getKey(),
            entry.getValue());
      }
      return catalog;
    } catch (LocalizationParseException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new LocalizationParseException(
          "INVALID_XCSTRINGS", "Invalid Xcode strings catalog", exception);
    }
  }

  private void parseMessage(
      LocalizationCatalog catalog,
      String sourceLanguage,
      String sourceIdentifier,
      Object version,
      Map<String, Object> catalogMetadata,
      String id,
      JsonNode descriptor) {
    if (!descriptor.isObject()) {
      throw invalid("Xcode strings entry must be an object: " + id);
    }
    if (descriptor.path("shouldTranslate").isBoolean()
        && !descriptor.get("shouldTranslate").asBoolean()) {
      return;
    }
    JsonNode localizations = descriptor.path("localizations");
    String sourceLocale = sourceLocalization(localizations, sourceIdentifier);
    JsonNode source = localizations.path(sourceLocale);
    JsonNode effectiveSource = source;
    String selectedDevice = null;
    JsonNode sourceDevices = source.path("variations").path("device");
    if (sourceDevices.isObject()) {
      selectedDevice = defaultDevice(sourceDevices);
      if (!source.path("variations").path("plural").isObject()) {
        effectiveSource = sourceDevices.get(selectedDevice);
      }
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    Map<String, String> variants = null;
    List<Map<String, Object>> disabled = new ArrayList<>();
    Map<String, List<Map<String, Object>>> disabledVariants = new LinkedHashMap<>();
    Map<String, Map<String, List<Map<String, Object>>>> disabledSubstitutions =
        new LinkedHashMap<>();
    String message;
    if (effectiveSource.path("variations").path("plural").isObject()) {
      variants = new LinkedHashMap<>();
      Map<String, JsonNode> categories = new TreeMap<>(AppleXcstringsParser::compareUnicodeScalars);
      effectiveSource
          .get("variations")
          .get("plural")
          .fields()
          .forEachRemaining(category -> categories.put(category.getKey(), category.getValue()));
      for (Map.Entry<String, JsonNode> category : categories.entrySet()) {
        JsonNode value = category.getValue().path("stringUnit").path("value");
        if (!value.isTextual()) {
          throw invalid("Xcode plural variant must have a stringUnit value");
        }
        if (SUBSTITUTION.matcher(value.asText()).find()) {
          throw invalid("Xcode plural variants cannot reference substitution definitions");
        }
        String normalized =
            PlaceholderNormalizer.normalizeFoundationPlural(
                value.asText(), placeholders, "count", null);
        List<Map<String, Object>> conversions =
            PlaceholderNormalizer.foundationPluralPrintfLineSeparators(
                value.asText(), "count", null);
        if (!conversions.isEmpty()) {
          List<Map<String, Object>> owned = new ArrayList<>();
          normalized =
              AppleStringsParser.withoutDisabledPrintfConversions(normalized, conversions, owned);
          disabledVariants.put(category.getKey(), owned);
        }
        variants.put(category.getKey(), normalized);
      }
      if (!variants.containsKey("other")) {
        throw new LocalizationParseException(
            "MISSING_OTHER_VARIANT", "Xcode plural is missing other");
      }
      if (placeholders.stream()
          .noneMatch(placeholder -> List.of("integer", "number").contains(placeholder.kind()))) {
        throw invalid("Xcode plural variation requires a numeric format argument");
      }
      message = PlaceholderNormalizer.plural("count", variants);
    } else {
      JsonNode sourceValue = effectiveSource.path("stringUnit").path("value");
      JsonNode substitutions = effectiveSource.path("substitutions");
      if (!substitutions.isObject()) {
        substitutions = source.path("substitutions");
      }
      message =
          normalizeSource(
              sourceValue.isTextual() ? sourceValue.asText() : id,
              substitutions,
              placeholders,
              disabledSubstitutions);
      String nativeSource = sourceValue.isTextual() ? sourceValue.asText() : id;
      List<Map<String, Object>> conversions =
          PlaceholderNormalizer.foundationPrintfLineSeparators(nativeSource);
      if (!conversions.isEmpty()) {
        message =
            AppleStringsParser.withoutDisabledPrintfConversions(message, conversions, disabled);
      }
    }
    String description =
        descriptor.path("comment").isTextual() ? descriptor.get("comment").asText() : null;
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (!disabled.isEmpty()) {
      metadata.put("appleDisabledPrintfConversions", disabled);
    }
    if (!disabledVariants.isEmpty()) {
      disabledSubstitutions.put("count", disabledVariants);
    }
    if (!disabledSubstitutions.isEmpty()) {
      metadata.put("applePluralDisabledPrintfConversions", disabledSubstitutions);
    }
    if (!sourceIdentifier.equals(sourceLanguage)) {
      metadata.put("appleSourceLanguage", sourceIdentifier);
    }
    if (!sourceLocale.equals(sourceIdentifier) && !source.isMissingNode()) {
      metadata.put("appleSourceLocalizationIdentifier", sourceLocale);
    }
    if (!"1.0".equals(version)) {
      metadata.put("appleCatalogVersion", version);
    }
    if (!catalogMetadata.isEmpty()) {
      metadata.put("appleCatalogMetadata", catalogMetadata);
    }
    Map<String, Object> descriptorMetadata = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> descriptorFields = descriptor.fields();
    while (descriptorFields.hasNext()) {
      Map.Entry<String, JsonNode> field = descriptorFields.next();
      if (!List.of("comment", "extractionState", "localizations", "shouldTranslate")
          .contains(field.getKey())) {
        descriptorMetadata.put(field.getKey(), JSON.convertValue(field.getValue(), Object.class));
      }
    }
    if (!descriptorMetadata.isEmpty()) {
      metadata.put("appleDescriptorMetadata", descriptorMetadata);
    }
    if (descriptor.path("extractionState").isTextual()) {
      metadata.put("extractionState", descriptor.get("extractionState").asText());
    }
    if (source.path("variations").isObject()) {
      Map<String, Object> axes = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> entries = source.get("variations").fields();
      while (entries.hasNext()) {
        Map.Entry<String, JsonNode> axis = entries.next();
        if (!"plural".equals(axis.getKey())) {
          axes.put(axis.getKey(), JSON.convertValue(axis.getValue(), Object.class));
        }
      }
      if (!axes.isEmpty()) {
        metadata.put("sourceVariationAxes", axes);
      }
    }
    if (selectedDevice != null) {
      metadata.put("defaultDevice", selectedDevice);
    }
    if (source.path("substitutions").isObject()) {
      metadata.put(
          "sourceSubstitutions", JSON.convertValue(source.get("substitutions"), Object.class));
    }
    if (source.isObject()) {
      metadata.put("appleSourceLocalization", JSON.convertValue(source, Object.class));
      JsonNode unit = effectiveSource.path("stringUnit");
      if (unit.path("state").isTextual()) {
        metadata.put("sourceState", unit.get("state").asText());
      }
      JsonNode plurals = effectiveSource.path("variations").path("plural");
      if (plurals.isObject()) {
        Map<String, String> states = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> categories = plurals.fields();
        while (categories.hasNext()) {
          Map.Entry<String, JsonNode> category = categories.next();
          JsonNode state = category.getValue().path("stringUnit").path("state");
          if (state.isTextual()) {
            states.put(category.getKey(), state.asText());
          }
        }
        if (!states.isEmpty()) {
          metadata.put("sourcePluralStates", states);
        }
      }
    }
    if (localizations.isObject()) {
      Map<String, Object> translations = new LinkedHashMap<>();
      Map<String, Object> localizationSources = new LinkedHashMap<>();
      Map<String, String> localizationIdentifiers = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> locales = localizations.fields();
      while (locales.hasNext()) {
        Map.Entry<String, JsonNode> locale = locales.next();
        String normalized = locale.getKey().replace('_', '-');
        if (!locale.getKey().equals(sourceLocale) && !locale.getValue().isNull()) {
          String identity = normalized;
          if (translations.containsKey(normalized)) {
            String previous = localizationIdentifiers.getOrDefault(normalized, normalized);
            if (!previous.equals(normalized)) {
              Object translation = translations.remove(normalized);
              Object localizationSource = localizationSources.remove(normalized);
              localizationIdentifiers.remove(normalized);
              translations.put(previous, translation);
              localizationSources.put(previous, localizationSource);
            }
            identity = locale.getKey();
          }
          if (translations.put(identity, translateMetadata(locale.getValue())) != null) {
            throw new LocalizationParseException(
                "DUPLICATE_LOCALE", "Duplicate native Xcode localization " + identity);
          }
          localizationSources.put(identity, JSON.convertValue(locale.getValue(), Object.class));
          if (!identity.equals(locale.getKey())) {
            localizationIdentifiers.put(identity, locale.getKey());
          }
        }
      }
      if (!translations.isEmpty()) {
        metadata.put("localizations", translations);
        metadata.put("appleLocalizationSources", localizationSources);
      }
      if (!localizationIdentifiers.isEmpty()) {
        metadata.put("appleLocalizationIdentifiers", localizationIdentifiers);
      }
    }
    catalog.add(id, LocalizationMessage.of(message, description, variants, placeholders, metadata));
  }

  private static Map<String, Object> translateMetadata(JsonNode localization) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    JsonNode unit = localization.path("stringUnit");
    if (unit.path("value").isTextual()) {
      metadata.put("value", unit.get("value").asText());
      if (unit.path("state").isTextual()) {
        metadata.put("state", unit.get("state").asText());
      }
    }
    JsonNode plural = localization.path("variations").path("plural");
    if (plural.isObject()) {
      Map<String, String> variants = new LinkedHashMap<>();
      Map<String, String> states = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> categories = plural.fields();
      while (categories.hasNext()) {
        Map.Entry<String, JsonNode> category = categories.next();
        JsonNode value = category.getValue().path("stringUnit").path("value");
        if (value.isTextual()) {
          variants.put(category.getKey(), value.asText());
        }
        JsonNode state = category.getValue().path("stringUnit").path("state");
        if (state.isTextual()) {
          states.put(category.getKey(), state.asText());
        }
      }
      metadata.put("variants", variants);
      if (!states.isEmpty()) {
        metadata.put("variantStates", states);
      }
    }
    if (localization.path("variations").isObject()) {
      Map<String, Object> otherAxes = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> axes = localization.get("variations").fields();
      while (axes.hasNext()) {
        Map.Entry<String, JsonNode> axis = axes.next();
        if (!"plural".equals(axis.getKey())) {
          otherAxes.put(axis.getKey(), JSON.convertValue(axis.getValue(), Object.class));
        }
      }
      if (!otherAxes.isEmpty()) {
        metadata.put("variationAxes", otherAxes);
      }
    }
    return metadata;
  }

  private static void validateDescriptors(JsonNode descriptors) {
    Iterator<Map.Entry<String, JsonNode>> entries = descriptors.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      JsonNode descriptor = entry.getValue();
      if (!descriptor.isObject()) {
        throw invalid("Xcode strings entry must be an object: " + entry.getKey());
      }
      validateOptionalText(descriptor, "comment");
      validateOptionalText(descriptor, "extractionState");
      if (descriptor.hasNonNull("shouldTranslate")
          && !descriptor.get("shouldTranslate").isBoolean()) {
        throw invalid("Xcode shouldTranslate must be a boolean or null");
      }
      JsonNode localizations = descriptor.path("localizations");
      if (!localizations.isObject()) {
        throw invalid("Xcode localizations must be an object");
      }
      boolean activeLocalization = false;
      Map<String, String> nativeLocales = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> locales = localizations.fields();
      while (locales.hasNext()) {
        Map.Entry<String, JsonNode> locale = locales.next();
        String nativeLocale = nativeBundleLocale(locale.getKey());
        String previous = nativeLocales.putIfAbsent(nativeLocale, locale.getKey());
        if (previous != null && !previous.equals(locale.getKey())) {
          throw new LocalizationParseException(
              "DUPLICATE_LOCALE",
              "Xcode localizations "
                  + previous
                  + " and "
                  + locale.getKey()
                  + " share native bundle "
                  + nativeLocale);
        }
        JsonNode localization = locale.getValue();
        if (localization.isNull()) {
          continue;
        }
        activeLocalization = true;
        if (!localization.isObject()
            || (!localization.has("stringUnit") && !localization.has("variations"))) {
          throw invalid("Xcode localization requires a stringUnit or variations");
        }
        validatePluralSubstitutionReferences(localization.path("variations"));
      }
      if (!activeLocalization) {
        throw invalid("Xcode descriptor requires an active localization");
      }
    }
  }

  static String nativeBundleLocale(String locale) {
    String normalizedLocale = locale.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    switch (normalizedLocale) {
      case "i-ami":
        return "ami";
      case "i-bnn":
        return "bnn";
      case "i-hak":
        return "hak";
      case "i-klingon":
        return "tlh";
      case "i-lux":
        return "lb";
      case "i-navajo":
        return "nv";
      case "i-pwn":
        return "pwn";
      case "i-tao":
        return "tao";
      case "i-tay":
        return "tay";
      case "i-tsu":
        return "tsu";
      case "sgn-be-fr":
        return "sfb";
      case "sgn-be-nl":
        return "vgt";
      case "sgn-ch-de":
        return "sgg";
      case "art-lojban":
        return "jbo";
      case "zh-min-nan":
        return "nan";
    }
    normalizedLocale =
        normalizedLocale
            .replaceFirst("^i-klingon-", "tlh-")
            .replaceFirst("^no-bok(?:-|$)", "nb-")
            .replaceFirst("^no-nyn(?:-|$)", "nn-")
            .replaceFirst("^zh-(?:cmn|guoyu)(?:-|$)", "zh-")
            .replaceFirst("^zh-hakka(?:-|$)", "hak-")
            .replaceFirst("^zh-xiang(?:-|$)", "hsn-")
            .replaceFirst("^zh-yue(?:-|$)", "yue-");
    if (normalizedLocale.endsWith("-")) {
      normalizedLocale = normalizedLocale.substring(0, normalizedLocale.length() - 1);
    }
    if (!normalizedLocale.equals(locale.toLowerCase(java.util.Locale.ROOT).replace('_', '-'))) {
      return nativeBundleLocale(normalizedLocale);
    }
    String[] components = locale.split("[-_]");
    List<Character> separators = new ArrayList<>();
    for (int index = 0; index < locale.length(); index++) {
      if (locale.charAt(index) == '-' || locale.charAt(index) == '_') {
        separators.add(locale.charAt(index));
      }
    }
    components[0] =
        switch (components[0].toLowerCase(java.util.Locale.ROOT)) {
          case "iw" -> "he";
          case "in" -> "id";
          case "ji" -> "yi";
          case "no" -> components.length == 1 || components[1].length() == 2 ? "nb" : "no";
          case "tl" -> "fil";
          case "jw" -> "jv";
          case "cmn" -> "zh";
          case "hbs" -> "sr";
          case "mol" -> "mo";
          default -> components[0].toLowerCase(java.util.Locale.ROOT);
        };
    if ("hbs".equalsIgnoreCase(locale.split("[-_]")[0])) {
      String[] latin = new String[components.length + 1];
      latin[0] = "sr";
      latin[1] = "Latn";
      System.arraycopy(components, 1, latin, 2, components.length - 1);
      components = latin;
      separators.add(0, '-');
    }
    for (int index = 1; index < components.length; index++) {
      components[index] =
          components[index].length() == 4
              ? components[index].substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                  + components[index].substring(1).toLowerCase(java.util.Locale.ROOT)
              : components[index].length() == 2
                  ? components[index].toUpperCase(java.util.Locale.ROOT)
                  : components[index].toLowerCase(java.util.Locale.ROOT);
    }
    if (components.length > 1 && components[1].length() == 2) {
      if ("en".equals(components[0]) && "UK".equals(components[1])) {
        components[1] = "GB";
      } else if ("cs".equals(components[0]) && "CS".equals(components[1])) {
        components[1] = "CZ";
      } else if ("sh".equals(components[0])) {
        components[0] = "sr";
      }
    } else if (components.length > 2
        && "sh".equals(components[0])
        && "Latn".equals(components[1])) {
      components[0] = "sr";
    }
    if (components.length > 4
        && "en".equals(components[0])
        && "u".equalsIgnoreCase(components[2])
        && "nu".equalsIgnoreCase(components[3])
        && "Latn".equalsIgnoreCase(components[4])) {
      components = java.util.Arrays.copyOf(components, 4);
      separators = new ArrayList<>(separators.subList(0, 3));
    }
    if (components.length > 3
        && "en".equals(components[0])
        && "u".equalsIgnoreCase(components[2])
        && "nu".equalsIgnoreCase(components[3])) {
      components[3] = "nu";
    }
    if (components.length > 1 && components[1].length() == 4) {
      boolean removeScript =
          (List.of("sr", "mn", "kk").contains(components[0]) && "Cyrl".equals(components[1]))
              || (List.of("az", "uz", "bs", "hr", "ha", "nb").contains(components[0])
                  && "Latn".equals(components[1]))
              || ("pa".equals(components[0]) && "Guru".equals(components[1]))
              || ("zh".equals(components[0]) && components.length > 2 && separators.get(1) == '_');
      if (removeScript) {
        if (components.length > 2) {
          separators.remove(0);
        }
        components =
            java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(components[0]),
                    java.util.Arrays.stream(components, 2, components.length))
                .toArray(String[]::new);
      } else {
        separators.set(0, '-');
      }
    }
    StringBuilder result = new StringBuilder(components[0]);
    for (int index = 1; index < components.length; index++) {
      result.append(separators.get(index - 1)).append(components[index]);
    }
    return result.toString();
  }

  static String sourceLocalization(JsonNode localizations, String sourceLanguage) {
    if (localizations.has(sourceLanguage)) {
      return sourceLanguage;
    }
    String alternate =
        sourceLanguage.contains("_")
            ? sourceLanguage.replace('_', '-')
            : sourceLanguage.replace('-', '_');
    if (localizations.has(alternate)) {
      return alternate;
    }
    String sourceBundle = nativeBundleLocale(sourceLanguage);
    Iterator<String> locales = localizations.fieldNames();
    while (locales.hasNext()) {
      String locale = locales.next();
      if (nativeBundleLocale(locale).equals(sourceBundle)) {
        return locale;
      }
    }
    return sourceLanguage;
  }

  private static void validatePluralSubstitutionReferences(JsonNode variations) {
    if (!variations.isObject()) {
      return;
    }
    JsonNode plural = variations.path("plural");
    if (plural.isObject()) {
      Iterator<JsonNode> categories = plural.elements();
      while (categories.hasNext()) {
        JsonNode value = categories.next().path("stringUnit").path("value");
        if (value.isTextual() && SUBSTITUTION.matcher(value.asText()).find()) {
          throw invalid("Xcode plural variants cannot reference substitution definitions");
        }
      }
    }
    JsonNode devices = variations.path("device");
    if (devices.isObject()) {
      Iterator<JsonNode> branches = devices.elements();
      while (branches.hasNext()) {
        validatePluralSubstitutionReferences(branches.next().path("variations"));
      }
    }
  }

  private static void validateOptionalText(JsonNode descriptor, String field) {
    if (descriptor.hasNonNull(field) && !descriptor.get(field).isTextual()) {
      throw invalid("Xcode " + field + " must be a string or null");
    }
  }

  static String normalizeSource(
      String source, JsonNode substitutions, List<LocalizationPlaceholder> placeholders) {
    return normalizeSource(source, substitutions, placeholders, new LinkedHashMap<>());
  }

  private static String normalizeSource(
      String source,
      JsonNode substitutions,
      List<LocalizationPlaceholder> placeholders,
      Map<String, Map<String, List<Map<String, Object>>>> disabledSubstitutions) {
    Matcher markers = SUBSTITUTION.matcher(source);
    if (!markers.find()) {
      return PlaceholderNormalizer.normalizeFoundation(source, placeholders);
    }
    if (!substitutions.isObject() || substitutions.isEmpty()) {
      throw invalid("Xcode plural substitution must reference an active definition");
    }
    markers.reset();
    StringBuilder masked = new StringBuilder();
    Map<String, String> expansions = new LinkedHashMap<>();
    int previous = 0;
    int implicitPosition = 0;
    while (markers.find()) {
      String identifier = markers.group(2);
      if (!SUBSTITUTION_NAME.matcher(identifier).matches()) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER", "Xcode substitution name is not a valid ICU argument");
      }
      JsonNode definition = substitutions.path(identifier);
      if (!definition.isObject()) {
        throw invalid("Xcode plural substitution has no matching definition: " + identifier);
      }
      JsonNode declaredPosition = definition.get("argNum");
      if (declaredPosition != null
          && (!declaredPosition.isIntegralNumber()
              || !declaredPosition.canConvertToInt()
              || declaredPosition.asInt() <= 0)) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER", "Xcode substitution argument position must be positive");
      }
      int position =
          markers.group(1) != null
              ? Integer.parseInt(markers.group(1))
              : declaredPosition != null ? declaredPosition.asInt() : ++implicitPosition;
      if (position <= 0) {
        throw new LocalizationParseException(
            "INVALID_PLACEHOLDER", "Xcode substitution argument position must be positive");
      }
      String expansion = expansions.get(identifier);
      if (expansion == null) {
        expansion =
            expandSubstitution(
                identifier, position, definition, placeholders, disabledSubstitutions);
        expansions.put(identifier, expansion);
      }
      masked.append(source, previous, markers.start());
      masked.append('\u0001').append(identifier).append('\u0001');
      previous = markers.end();
    }
    masked.append(source, previous, source.length());
    String normalized = PlaceholderNormalizer.normalize(masked.toString(), placeholders);
    Map<Integer, String> positions = new LinkedHashMap<>();
    for (LocalizationPlaceholder placeholder : placeholders) {
      if (placeholder.position() != null) {
        String previousKind = positions.putIfAbsent(placeholder.position(), placeholder.kind());
        if (previousKind != null && !previousKind.equals(placeholder.kind())) {
          throw new LocalizationParseException(
              "INVALID_PLACEHOLDER", "Xcode substitution argument has incompatible native types");
        }
      }
    }
    for (Map.Entry<String, String> expansion : expansions.entrySet()) {
      normalized =
          normalized.replace("\u0001" + expansion.getKey() + "\u0001", expansion.getValue());
    }
    return normalized;
  }

  private static String expandSubstitution(
      String identifier,
      int position,
      JsonNode definition,
      List<LocalizationPlaceholder> placeholders,
      Map<String, Map<String, List<Map<String, Object>>>> disabledSubstitutions) {
    JsonNode specifier = definition.get("formatSpecifier");
    if (specifier != null && !specifier.isTextual()) {
      throw invalid("Xcode substitution format specifier must be text");
    }
    if (specifier != null) {
      List<LocalizationPlaceholder> typed = PlaceholderNormalizer.placeholders();
      PlaceholderNormalizer.normalize("%" + specifier.asText(), typed, identifier);
      if (typed.isEmpty() || !List.of("integer", "number").contains(typed.getFirst().kind())) {
        throw invalid("Xcode plural substitution requires a numeric format specifier");
      }
    }
    JsonNode categories = definition.path("variations").path("plural");
    if (!categories.isObject() || categories.isEmpty()) {
      throw invalid("Xcode substitution must contain plural variations");
    }
    Map<String, String> variants = new TreeMap<>(AppleXcstringsParser::compareUnicodeScalars);
    boolean numeric = false;
    Iterator<Map.Entry<String, JsonNode>> entries = categories.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> category = entries.next();
      JsonNode unit = category.getValue().path("stringUnit").path("value");
      if (!unit.isTextual()) {
        throw invalid("Xcode substitution plural branch requires a string value");
      }
      List<LocalizationPlaceholder> branch = PlaceholderNormalizer.placeholders();
      String value =
          PlaceholderNormalizer.normalizeFoundationSubstitution(
              unit.asText(), branch, identifier, position);
      List<Map<String, Object>> conversions =
          PlaceholderNormalizer.foundationSubstitutionPrintfLineSeparators(
              unit.asText(), identifier, position);
      if (!conversions.isEmpty()) {
        List<Map<String, Object>> disabled = new ArrayList<>();
        value = AppleStringsParser.withoutDisabledPrintfConversions(value, conversions, disabled);
        disabledSubstitutions
            .computeIfAbsent(identifier, ignored -> new LinkedHashMap<>())
            .put(category.getKey(), disabled);
      }
      for (LocalizationPlaceholder placeholder : branch) {
        boolean selector = Integer.valueOf(position).equals(placeholder.position());
        if (selector && !List.of("integer", "number").contains(placeholder.kind())) {
          throw new LocalizationParseException(
              "INVALID_PLACEHOLDER", "Xcode plural selector must reference a numeric argument");
        }
        LocalizationPlaceholder corrected =
            selector
                ? new LocalizationPlaceholder(
                    identifier, placeholder.source(), placeholder.kind(), position, null)
                : placeholder;
        if (!placeholders.contains(corrected)) {
          placeholders.add(corrected);
        }
        numeric |= selector;
      }
      variants.put(category.getKey(), value);
    }
    if (!variants.containsKey("other")) {
      throw new LocalizationParseException(
          "MISSING_OTHER_VARIANT", "Xcode substitution plural is missing other");
    }
    if (!numeric) {
      if (specifier == null) {
        throw invalid("Xcode plural substitution requires a numeric format argument");
      }
      List<LocalizationPlaceholder> typed = PlaceholderNormalizer.placeholders();
      PlaceholderNormalizer.normalize("%" + specifier.asText(), typed, identifier);
      LocalizationPlaceholder placeholder = typed.getFirst();
      placeholders.add(
          new LocalizationPlaceholder(
              identifier, placeholder.source(), placeholder.kind(), position, null));
    }
    return PlaceholderNormalizer.plural(identifier, variants);
  }

  private static void validateUnits(JsonNode value) {
    if (value.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
      while (entries.hasNext()) {
        Map.Entry<String, JsonNode> entry = entries.next();
        if ("stringUnit".equals(entry.getKey())
            && (!entry.getValue().isObject()
                || !entry.getValue().path("state").isTextual()
                || !entry.getValue().path("value").isTextual())) {
          throw invalid("Xcode stringUnit requires a state and value");
        }
        if ("variations".equals(entry.getKey())) {
          if (!entry.getValue().isObject()) {
            throw invalid("Xcode variations must be an object");
          }
          if (entry.getValue().isEmpty()) {
            throw invalid("Xcode variations must contain an active axis");
          }
          if (entry.getValue().has("plural") && entry.getValue().size() > 1) {
            throw new LocalizationParseException(
                "AMBIGUOUS_XCSTRINGS_VARIATIONS",
                "Sibling Xcode plural and device axes compile nondeterministically");
          }
          Iterator<Map.Entry<String, JsonNode>> axes = entry.getValue().fields();
          while (axes.hasNext()) {
            Map.Entry<String, JsonNode> axis = axes.next();
            if (!axis.getValue().isObject()) {
              throw invalid("Xcode variation axis must be an object");
            }
            if ("device".equals(axis.getKey())) {
              JsonNode fallback = axis.getValue().path("other");
              if (fallback.isObject() && fallback.path("variations").isObject()) {
                throw invalid("Xcode fallback device value cannot be further varied");
              }
              Iterator<JsonNode> devices = axis.getValue().elements();
              while (devices.hasNext()) {
                JsonNode substitutions = devices.next().path("substitutions");
                if (substitutions.isObject() && !substitutions.isEmpty()) {
                  throw invalid("Xcode substitution definitions belong to the localization root");
                }
              }
            }
            if ("plural".equals(axis.getKey())) {
              Iterator<String> categories = axis.getValue().fieldNames();
              while (categories.hasNext()) {
                String category = categories.next();
                if (!List.of("zero", "one", "two", "few", "many", "other").contains(category)) {
                  throw new LocalizationParseException(
                      "INVALID_PLURAL_CATEGORY", "Unsupported Xcode plural category " + category);
                }
              }
            }
          }
        }
        if ("substitutions".equals(entry.getKey())
            && !entry.getValue().isNull()
            && !entry.getValue().isObject()) {
          throw invalid("Xcode substitutions must be an object or null");
        }
        if ("substitutions".equals(entry.getKey()) && entry.getValue().isObject()) {
          Iterator<Map.Entry<String, JsonNode>> substitutions = entry.getValue().fields();
          while (substitutions.hasNext()) {
            Map.Entry<String, JsonNode> substitution = substitutions.next();
            if (!substitution.getValue().isObject()) {
              throw invalid("Xcode substitution definition must be an object");
            }
            JsonNode position = substitution.getValue().get("argNum");
            if (position != null
                && (!position.isIntegralNumber()
                    || !position.canConvertToInt()
                    || position.asInt() <= 0)) {
              throw new LocalizationParseException(
                  "INVALID_PLACEHOLDER", "Xcode substitution argument position must be positive");
            }
            JsonNode specifier = substitution.getValue().get("formatSpecifier");
            if (specifier != null && !specifier.isTextual()) {
              throw invalid("Xcode substitution format specifier must be text");
            }
          }
        }
        validateUnits(entry.getValue());
      }
    } else if (value.isArray()) {
      for (JsonNode item : value) {
        validateUnits(item);
      }
    }
  }

  private static String defaultDevice(JsonNode devices) {
    for (String device :
        List.of("iphone", "ipad", "mac", "applewatch", "applevision", "appletv", "ipod", "other")) {
      if (devices.has(device)) {
        return device;
      }
    }
    Iterator<String> entries = devices.fieldNames();
    String selected = null;
    while (entries.hasNext()) {
      String candidate = entries.next();
      if (selected == null || compareUnicodeScalars(candidate, selected) < 0) {
        selected = candidate;
      }
    }
    if (selected == null) {
      throw invalid("Xcode device variation must contain at least one device");
    }
    return selected;
  }

  private static int compareUnicodeScalars(String left, String right) {
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
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_XCSTRINGS", message);
  }
}
