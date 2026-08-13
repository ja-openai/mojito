package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.cldr.PluralRuleService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Intentional Mojito workflow rules layered explicitly above native platform extraction. */
final class MojitoLocalizationWorkflow {

  private static final List<String> PLURAL_CATEGORIES =
      List.of("zero", "one", "two", "few", "many", "other");
  private static final String DO_NOT_TRANSLATE = "DO NOT TRANSLATE";
  private static final String UNTRANSLATED = "@#$untranslated$#@";
  private static final Pattern LOCATIONS =
      Pattern.compile("\\s*<locations>\\s*(.*?)\\s*</locations>", Pattern.DOTALL);
  private static final Pattern APPLE_COMMENTS =
      Pattern.compile("(?s)/\\*(.*?)\\*/\\s*(\\\"(?:\\\\.|[^\\\"\\\\])*\\\")\\s*=");
  private static final Pattern HTML_CODE = Pattern.compile("<br id='(p[1-9][0-9]*)'/>");
  private static final Pattern PLURAL_SELECTOR = Pattern.compile("\\{([^,{}]+),\\s*plural,");
  private static final Pattern APPLE_OUTPUT_ENTRY =
      Pattern.compile(
          "(?s)((?:\\s*(?:/\\*.*?\\*/|//[^\\r\\n\\u2028\\u2029]*))+\\s*)?"
              + "((\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z0-9_$/:.-]+)"
              + "\\s*=\\s*(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z0-9_$/:.-]+)\\s*;)");
  private static final String GETTEXT_QUOTED = "\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"";
  private static final Pattern GETTEXT_OUTPUT_ENTRY =
      Pattern.compile(
          "(?m)^(?:#.*\\R)*(?:msgctxt\\h+"
              + GETTEXT_QUOTED
              + "(?:\\h*\\R\\h*"
              + GETTEXT_QUOTED
              + ")*\\h*\\R)?msgid ");
  private static final Pattern GETTEXT_UNTRANSLATED_VALUE =
      Pattern.compile(
          "(?m)^msgstr(?:\\[[0-9]+])?\\h+("
              + GETTEXT_QUOTED
              + ")((?:\\h*\\R\\h*"
              + GETTEXT_QUOTED
              + ")*)\\h*$");
  private static final Pattern GETTEXT_QUOTED_SEGMENT =
      Pattern.compile("\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"");
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS);

  private MojitoLocalizationWorkflow() {}

  static LocalizationCatalog parse(
      LocalizationFileFormat format, byte[] source, List<String> filterOptions) {
    LocalizationFilterOptions options = LocalizationFilterOptions.parse(format, filterOptions);
    if (format == LocalizationFileFormat.HTML) {
      boolean includeImages = options.enabled("processImageUrls");
      boolean suppressEmpty =
          !options.contains("emptyAndNbspNotTranslatable")
              || options.enabled("emptyAndNbspNotTranslatable");
      return applyExtractionPolicy(
          HtmlSourceFormat.parse(
              LocalizationFileConverters.decode(source, null), includeImages, suppressEmpty),
          source,
          options);
    }
    LocalizationCatalog catalog =
        format == LocalizationFileFormat.YAML
            ? YamlSourceFormat.parse(source, options)
            : format == LocalizationFileFormat.FORMATJS_JSON
                    && (filterOptions != null && !filterOptions.isEmpty()
                        || containsJsonComments(source))
                ? parseConfiguredJson(source, options)
                : LocalizationFileConverters.parse(format, source);
    return applyExtractionPolicy(catalog, source, options);
  }

  static LocalizationCatalog parseImport(
      LocalizationFileFormat format,
      byte[] source,
      List<String> filterOptions,
      String targetLocale,
      boolean copyFormsOnImport) {
    List<String> extractionOptions = new ArrayList<>();
    String targetComment = null;
    if (filterOptions != null) {
      for (String option : filterOptions) {
        if (option != null && option.startsWith("targetComment=")) {
          targetComment = option.substring("targetComment=".length());
        } else {
          extractionOptions.add(option);
        }
      }
    }
    LocalizationCatalog original;
    if (format == LocalizationFileFormat.CSV
        || format == LocalizationFileFormat.CSV_ADOBE_MAGENTO) {
      LocalizationFilterOptions.parse(format, extractionOptions);
      original =
          CsvLocalizationFormat.parseImport(
              format, LocalizationFileConverters.decode(source, null));
    } else {
      original =
          parse(
              format,
              format == LocalizationFileFormat.GETTEXT_PO && targetLocale != null
                  ? gettextImportLocale(source, targetLocale)
                  : source,
              extractionOptions);
    }
    if (!copyFormsOnImport && targetComment == null) {
      return original;
    }
    if (copyFormsOnImport
        && format != LocalizationFileFormat.ANDROID
        && format != LocalizationFileFormat.APPLE_STRINGSDICT
        && format != LocalizationFileFormat.GETTEXT_PO) {
      throw new LocalizationParseException(
          "UNSUPPORTED_IMPORT_POLICY", "Plural copying is unsupported for " + format.id());
    }
    Set<String> categories =
        copyFormsOnImport
            ? targetLocale == null ? Set.of() : mojitoPluralCategories(targetLocale)
            : Set.of();
    if (copyFormsOnImport && categories.isEmpty()) {
      throw new LocalizationParseException(
          "INVALID_IMPORT_LOCALE", "Import requires a supported target locale");
    }
    LocalizationCatalog result = new LocalizationCatalog(format);
    result.setLocale(original.locale());
    for (Map.Entry<String, LocalizationMessage> entry : original.messages().entrySet()) {
      LocalizationMessage message = entry.getValue();
      Map<String, Object> metadata =
          new LinkedHashMap<>(message.metadata() == null ? Map.of() : message.metadata());
      if (targetComment != null) {
        metadata.put("mojitoTargetComment", targetComment);
      }
      Map<String, String> variants = message.variants();
      String defaultMessage = message.defaultMessage();
      if (copyFormsOnImport
          && format == LocalizationFileFormat.APPLE_STRINGSDICT
          && metadata.get("applePluralRules") instanceof Map<?, ?> rules
          && metadata.get("pluralVariables") instanceof List<?> variables) {
        for (Object rawVariable : variables) {
          if (!(rawVariable instanceof String variable)
              || !(rules.get(variable) instanceof Map<?, ?> rule)
              || !(rule.get("variants") instanceof Map<?, ?> sourceVariants)) {
            throw new LocalizationParseException(
                "INVALID_IMPORT_PLURAL", "Invalid Apple import plural definition");
          }
          Map<String, String> canonical = new LinkedHashMap<>();
          Integer position =
              message.placeholders().stream()
                  .filter(placeholder -> variable.equals(placeholder.name()))
                  .map(LocalizationPlaceholder::position)
                  .findFirst()
                  .orElse(null);
          for (Map.Entry<?, ?> variant : sourceVariants.entrySet()) {
            if (!(variant.getKey() instanceof String category)
                || !(variant.getValue() instanceof String value)) {
              throw new LocalizationParseException(
                  "INVALID_IMPORT_PLURAL", "Invalid Apple import plural branch");
            }
            String normalized =
                PlaceholderNormalizer.normalizeFoundationPlural(
                    value, PlaceholderNormalizer.placeholders(), variable, position);
            List<Map<String, Object>> conversions =
                PlaceholderNormalizer.foundationPluralPrintfLineSeparators(
                    value, variable, position);
            if (!conversions.isEmpty()) {
              normalized =
                  AppleStringsParser.withoutDisabledPrintfConversions(
                      normalized, conversions, new ArrayList<>());
            }
            canonical.put(category, normalized);
          }
          Map<String, String> completed =
              completeVariants(canonical, categories, targetLocale, format);
          defaultMessage = replacePlural(defaultMessage, variable, completed);
          copyApplePluralRule(metadata, variable, completed);
          rules = (Map<?, ?>) metadata.get("applePluralRules");
        }
      } else if (copyFormsOnImport && variants != null) {
        Set<String> required =
            format == LocalizationFileFormat.GETTEXT_PO
                ? gettextImportCategories(metadata, variants, targetLocale)
                : categories;
        Map<String, String> completed = completeVariants(variants, required, targetLocale, format);
        String selector = pluralSelector(message, metadata);
        defaultMessage = replacePlural(defaultMessage, selector, completed);
        copyPluralMetadata(metadata, variants, completed, format);
        variants = completed;
      }
      result.add(
          entry.getKey(),
          LocalizationMessage.of(
              defaultMessage, message.description(), variants, message.placeholders(), metadata));
    }
    return result;
  }

  private static Set<String> gettextImportCategories(
      Map<String, Object> metadata, Map<String, String> variants, String targetLocale) {
    Set<String> categories = new LinkedHashSet<>();
    if (metadata.get("gettextPluralIndexes") instanceof Map<?, ?> indexes) {
      indexes.values().forEach(value -> categories.add(value.toString()));
    }
    categories.addAll(variants.keySet());
    switch (language(targetLocale)) {
      case "cs", "sk", "lt" -> categories.add("many");
      case "ru", "uk", "be", "pl", "sl" -> categories.add("other");
      case "ga" -> {
        categories.add("many");
        categories.add("other");
      }
      default -> {}
    }
    if (categories.isEmpty()) {
      categories.add("other");
    }
    return categories;
  }

  private static byte[] gettextImportLocale(byte[] source, String targetLocale) {
    String original = new String(source, StandardCharsets.ISO_8859_1);
    String localized =
        original.replace("\"Language: \\n\"", "\"Language: " + targetLocale + "\\n\"");
    return localized.equals(original) ? source : localized.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static Map<String, String> completeVariants(
      Map<String, String> source,
      Set<String> required,
      String locale,
      LocalizationFileFormat format) {
    Map<String, String> completed = new LinkedHashMap<>();
    for (String category : PLURAL_CATEGORIES) {
      if (!required.contains(category)) {
        continue;
      }
      String value = source.get(category);
      if (value == null && format == LocalizationFileFormat.GETTEXT_PO) {
        String language = language(locale);
        if ("many".equals(category) && "ga".equals(language)) {
          value = source.get("few");
        } else if ("other".equals(category)
            && Set.of("ru", "uk", "be", "pl", "sl").contains(language)) {
          value = source.get("many");
        }
      }
      if (value == null) {
        value = source.get("other");
      }
      if (value == null) {
        throw new LocalizationParseException(
            "INVALID_IMPORT_PLURAL", "Cannot synthesize import plural category: " + category);
      }
      completed.put(category, value);
    }
    return completed;
  }

  private static String language(String locale) {
    int separator = locale.indexOf('-');
    int underscore = locale.indexOf('_');
    int end =
        separator < 0 ? underscore : underscore < 0 ? separator : Math.min(separator, underscore);
    return end < 0
        ? locale.toLowerCase(Locale.ROOT)
        : locale.substring(0, end).toLowerCase(Locale.ROOT);
  }

  private static String pluralSelector(LocalizationMessage message, Map<String, Object> metadata) {
    if (metadata.get("pluralVariable") instanceof String variable) {
      return variable;
    }
    Matcher selector = PLURAL_SELECTOR.matcher(message.defaultMessage());
    if (selector.find()) {
      return selector.group(1);
    }
    throw new LocalizationParseException("INVALID_IMPORT_PLURAL", "Missing import plural selector");
  }

  private static String replacePlural(
      String message, String selector, Map<String, String> variants) {
    String marker = "{" + selector + ", plural,";
    int start = message.indexOf(marker);
    if (start < 0) {
      throw new LocalizationParseException("INVALID_IMPORT_PLURAL", "Missing import plural branch");
    }
    String replacement = PlaceholderNormalizer.plural(selector, variants);
    StringBuilder result = new StringBuilder(message.length() + replacement.length());
    int copied = 0;
    while (start >= 0) {
      int depth = 0;
      int end = start;
      for (; end < message.length(); end++) {
        if (message.charAt(end) == '{') {
          depth++;
        } else if (message.charAt(end) == '}' && --depth == 0) {
          break;
        }
      }
      if (end == message.length()) {
        throw new LocalizationParseException(
            "INVALID_IMPORT_PLURAL", "Unclosed import plural branch");
      }
      result.append(message, copied, start).append(replacement);
      copied = end + 1;
      start = message.indexOf(marker, copied);
    }
    return result.append(message, copied, message.length()).toString();
  }

  @SuppressWarnings("unchecked")
  private static void copyPluralMetadata(
      Map<String, Object> metadata,
      Map<String, String> original,
      Map<String, String> completed,
      LocalizationFileFormat format) {
    for (Map.Entry<String, Object> field : new ArrayList<>(metadata.entrySet())) {
      if (field.getKey().startsWith("androidPlural") && field.getValue() instanceof Map<?, ?> raw) {
        Map<String, Object> categories = new LinkedHashMap<>();
        for (String category : completed.keySet()) {
          Object value = raw.get(category);
          if (value == null && !original.containsKey(category)) {
            value = raw.get("other");
          }
          if (value != null) {
            categories.put(category, value);
          }
        }
        if (categories.isEmpty()) {
          metadata.remove(field.getKey());
        } else {
          metadata.put(field.getKey(), categories);
        }
      }
    }
    if (format == LocalizationFileFormat.APPLE_STRINGSDICT
        && metadata.get("pluralVariable") instanceof String variable) {
      copyApplePluralRule(metadata, variable, completed);
    }
  }

  @SuppressWarnings("unchecked")
  private static void copyApplePluralRule(
      Map<String, Object> metadata, String variable, Map<String, String> completed) {
    if (metadata.get("applePluralRules") instanceof Map<?, ?> rules
        && rules.get(variable) instanceof Map<?, ?> rawRule
        && rawRule.get("variants") instanceof Map<?, ?> rawVariants) {
      Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>) rawRule);
      Map<String, Object> sourceVariants = new LinkedHashMap<>();
      for (String category : completed.keySet()) {
        Object value = rawVariants.get(category);
        if (value == null) {
          value = rawVariants.get("other");
        }
        sourceVariants.put(category, value);
      }
      rule.put("variants", sourceVariants);
      Map<String, Object> updated = new LinkedHashMap<>((Map<String, Object>) rules);
      updated.put(variable, rule);
      metadata.put("applePluralRules", updated);
    }
    for (String key : List.of("devicePluralVariants", "deviceMixedVariants")) {
      if (!(metadata.get(key) instanceof Map<?, ?> devices)) {
        continue;
      }
      Map<String, Object> updatedDevices = new LinkedHashMap<>();
      for (Map.Entry<?, ?> device : devices.entrySet()) {
        Object value = device.getValue();
        if (value instanceof Map<?, ?> branch
            && branch.get(variable) instanceof Map<?, ?> sourceRule
            && "NSStringPluralRuleType".equals(sourceRule.get("NSStringFormatSpecTypeKey"))) {
          Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>) sourceRule);
          Object other = sourceRule.get("other");
          if (other != null) {
            for (String category : completed.keySet()) {
              rule.putIfAbsent(category, other);
            }
          }
          Map<String, Object> updated = new LinkedHashMap<>((Map<String, Object>) branch);
          updated.put(variable, rule);
          value = updated;
        }
        updatedDevices.put(device.getKey().toString(), value);
      }
      metadata.put(key, updatedDevices);
    }
    if (metadata.get("applePluralDisabledPrintfConversions") instanceof Map<?, ?> conversions
        && conversions.get(variable) instanceof Map<?, ?> original) {
      Map<String, Object> categories = new LinkedHashMap<>();
      for (String category : completed.keySet()) {
        Object conversion = original.get(category);
        if (conversion == null) {
          conversion = original.get("other");
        }
        if (conversion != null) {
          categories.put(category, conversion);
        }
      }
      Map<String, Object> updated = new LinkedHashMap<>((Map<String, Object>) conversions);
      updated.put(variable, categories);
      metadata.put("applePluralDisabledPrintfConversions", updated);
    }
  }

  static byte[] localize(
      LocalizationFileFormat format,
      byte[] source,
      Map<String, String> translations,
      List<String> filterOptions,
      boolean removeUntranslated,
      String targetLocale) {
    LocalizationFilterOptions options = LocalizationFilterOptions.parse(format, filterOptions);
    if (format == LocalizationFileFormat.FORMATJS_JSON) {
      return localizeJson(source, translations, options, removeUntranslated);
    }
    if (format == LocalizationFileFormat.CSV
        || format == LocalizationFileFormat.CSV_ADOBE_MAGENTO) {
      return CsvLocalizationFormat.localize(format, source, translations, removeUntranslated);
    }
    if (format == LocalizationFileFormat.HTML) {
      boolean includeImages = options.enabled("processImageUrls");
      boolean suppressEmpty =
          !options.contains("emptyAndNbspNotTranslatable")
              || options.enabled("emptyAndNbspNotTranslatable");
      return HtmlSourceFormat.render(
          HtmlSourceFormat.extract(source, includeImages, suppressEmpty), translations);
    }
    LocalizationCatalog catalog =
        applyExtractionPolicy(
            format == LocalizationFileFormat.YAML
                ? YamlSourceFormat.parse(source, options)
                : LocalizationFileConverters.parse(format, source),
            source,
            options);
    LocalizationSourceSkeleton skeleton =
        LocalizationFileConverters.extractSkeleton(format, source);
    Map<String, String> selected = new LinkedHashMap<>();
    Set<String> untranslatedKeys = new LinkedHashSet<>();
    String untranslatedMarker = UNTRANSLATED;
    if (removeUntranslated
        && (format == LocalizationFileFormat.GETTEXT_PO
            || format == LocalizationFileFormat.ANDROID)) {
      while (translations.containsValue(untranslatedMarker)) {
        untranslatedMarker += "#";
      }
      if (format == LocalizationFileFormat.ANDROID) {
        while (skeleton.source().contains(untranslatedMarker)) {
          untranslatedMarker += "#";
        }
      }
    }
    for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : skeleton.slots()) {
      if (!catalog.messages().containsKey(slot.id())) {
        continue;
      }
      String key = slot.translationKey();
      if (translations.containsKey(key)) {
        selected.put(key, translations.get(key));
      } else if (removeUntranslated) {
        selected.put(key, untranslatedMarker);
        untranslatedKeys.add(key);
      }
    }
    for (String key : translations.keySet()) {
      if (!selected.containsKey(key)) {
        throw new LocalizationParseException(
            "UNKNOWN_SKELETON_SLOT", "Translation has no translatable source slot: " + key);
      }
    }
    if (format == LocalizationFileFormat.APPLE_STRINGSDICT && removeUntranslated) {
      Set<String> missingMessages = new LinkedHashSet<>(catalog.messages().keySet());
      for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : skeleton.slots()) {
        if (translations.containsKey(slot.translationKey())) {
          missingMessages.remove(slot.id());
        }
      }
      if (!missingMessages.isEmpty()) {
        Set<String> removedSlots = new LinkedHashSet<>();
        for (LocalizationSourceSkeleton.LocalizationSourceSlot slot : skeleton.slots()) {
          if (missingMessages.contains(slot.id())) {
            removedSlots.add(slot.translationKey());
          }
        }
        byte[] retained = AppleStringsdictSourceSkeleton.removeMessages(skeleton, missingMessages);
        skeleton = LocalizationFileConverters.extractSkeleton(format, retained);
        selected.keySet().removeAll(removedSlots);
      }
    }
    if (removeUntranslated
        && (format == LocalizationFileFormat.RESX || format == LocalizationFileFormat.XTB)) {
      byte[] retained = XmlResourceSourceSkeleton.removeEntries(skeleton, untranslatedKeys);
      skeleton = LocalizationFileConverters.extractSkeleton(format, retained);
      selected.keySet().removeAll(untranslatedKeys);
    }
    byte[] localized = LocalizationFileConverters.renderSkeleton(skeleton, selected);
    if (format == LocalizationFileFormat.APPLE_STRINGSDICT && targetLocale != null) {
      localized =
          AppleStringsdictSourceSkeleton.retainPluralCategories(
              localized, mojitoPluralCategories(targetLocale));
    }
    if (format == LocalizationFileFormat.ANDROID
        && (removeUntranslated || options.changesAndroidOutput())) {
      return AndroidLocalizedOutput.process(
          localized, options, removeUntranslated, untranslatedMarker);
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    String content = encoding.decode(localized, encoding.bom().length, localized.length);
    if (format == LocalizationFileFormat.APPLE_STRINGS) {
      content =
          processAppleStrings(
              content, options.enabled("removeComment"), removeUntranslated, untranslatedKeys);
    } else if (format == LocalizationFileFormat.JAVA_PROPERTIES && removeUntranslated) {
      content = JavaPropertiesSourceSkeleton.removeEntries(content, untranslatedKeys);
    } else if (format == LocalizationFileFormat.GETTEXT_PO && removeUntranslated) {
      content = removeGettextEntries(content, untranslatedMarker);
    }
    return encoding.encode(content);
  }

  private static Set<String> mojitoPluralCategories(String targetLocale) {
    return CldrCardinalCategories.forLocale(targetLocale).isEmpty()
        ? Set.of()
        : PluralRuleService.getKeywordsForLanguageTag(targetLocale.replace('_', '-'));
  }

  static String normalizeTranslation(
      LocalizationFileFormat format,
      LocalizationMessage message,
      String variant,
      String translation) {
    if (format == LocalizationFileFormat.ANDROID || format == LocalizationFileFormat.GETTEXT_PO) {
      return PlaceholderNormalizer.normalize(translation, PlaceholderNormalizer.placeholders());
    }
    if (format == LocalizationFileFormat.APPLE_STRINGS) {
      return PlaceholderNormalizer.normalizeFoundation(
          translation, PlaceholderNormalizer.placeholders());
    }
    if (format != LocalizationFileFormat.APPLE_STRINGSDICT) {
      return translation;
    }
    String selector =
        message.metadata() != null
                && message.metadata().get("pluralVariable") instanceof String name
            ? name
            : null;
    if (selector == null || variant == null) {
      return PlaceholderNormalizer.normalizeFoundation(
          translation, PlaceholderNormalizer.placeholders());
    }
    Integer position =
        message.placeholders() == null
            ? null
            : message.placeholders().stream()
                .filter(placeholder -> selector.equals(placeholder.name()))
                .map(LocalizationPlaceholder::position)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    return PlaceholderNormalizer.normalizeFoundationPlural(
        translation, PlaceholderNormalizer.placeholders(), selector, position);
  }

  private static LocalizationCatalog applyExtractionPolicy(
      LocalizationCatalog original, byte[] source, LocalizationFilterOptions options) {
    Map<String, String> androidNotes =
        options.format() == LocalizationFileFormat.ANDROID ? androidNotes(source) : Map.of();
    Map<String, String> appleNotes =
        options.format() == LocalizationFileFormat.APPLE_STRINGS
            ? appleNotes(
                LocalizationFileConverters.decode(
                    source, LocalizationFileConverters.xmlCharset(options.format(), source)))
            : Map.of();
    LocalizationCatalog result = new LocalizationCatalog(options.format());
    result.setLocale(original.locale());
    for (Map.Entry<String, LocalizationMessage> entry : original.messages().entrySet()) {
      LocalizationMessage message = entry.getValue();
      String description = message.description();
      if (androidNotes.containsKey(resourceIdentity(entry.getKey()))) {
        description = androidNotes.get(resourceIdentity(entry.getKey()));
      }
      if (appleNotes.containsKey(entry.getKey())) {
        description = appleNotes.get(entry.getKey());
      }
      Map<String, Object> metadata =
          message.metadata() == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(message.metadata());
      if (options.format() == LocalizationFileFormat.APPLE_STRINGS && description != null) {
        Matcher locations = LOCATIONS.matcher(description);
        if (locations.find()) {
          Set<String> usages = new LinkedHashSet<>();
          for (String line : locations.group(1).split("\\R")) {
            if (!line.isBlank()) {
              usages.add(line.trim());
            }
          }
          if (!usages.isEmpty()) {
            metadata.put("references", new ArrayList<>(usages));
          }
          description =
              description.substring(0, locations.start()) + description.substring(locations.end());
        }
      }
      if (description != null && description.contains(DO_NOT_TRANSLATE)) {
        continue;
      }
      result.add(
          entry.getKey(),
          LocalizationMessage.of(
              message.defaultMessage(),
              description,
              message.variants(),
              message.placeholders(),
              metadata));
    }
    return result;
  }

  private static String resourceIdentity(String id) {
    int index = id.indexOf('[');
    int product = id.indexOf('@');
    int end = index < 0 ? product : product < 0 ? index : Math.min(index, product);
    return end < 0 ? id : id.substring(0, end);
  }

  private static Map<String, String> appleNotes(String source) {
    Map<String, String> notes = new LinkedHashMap<>();
    Matcher matcher = APPLE_COMMENTS.matcher(source);
    while (matcher.find()) {
      notes.put(AppleStringsParser.decodeSourceToken(matcher.group(2)), matcher.group(1));
    }
    return notes;
  }

  private static Map<String, String> androidNotes(String source) {
    Map<String, String> notes = new LinkedHashMap<>();
    Element root = SecureXmlParser.parse(source).getDocumentElement();
    List<String> comments = new ArrayList<>();
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child.getNodeType() == Node.COMMENT_NODE) {
        comments.add(child.getNodeValue().trim());
      } else if (child instanceof Element element) {
        if (element.getNamespaceURI() != null && !element.getNamespaceURI().isEmpty()) {
          continue;
        }
        if ("skip".equals(element.getLocalName()) || "eat-comment".equals(element.getLocalName())) {
          comments.clear();
          continue;
        }
        String note =
            element.hasAttribute("description")
                ? element.getAttribute("description")
                : String.join(" ", comments);
        if (!note.isBlank() && element.hasAttribute("name")) {
          notes.put(element.getAttribute("name"), note);
        }
        comments.clear();
      }
    }
    return notes;
  }

  private static Map<String, String> androidNotes(byte[] source) {
    String decoded =
        LocalizationFileConverters.decode(
            source, LocalizationFileConverters.xmlCharset(LocalizationFileFormat.ANDROID, source));
    return decoded.contains("<!--") ? androidNotes(decoded) : Map.of();
  }

  private static LocalizationCatalog parseConfiguredJson(
      byte[] source, LocalizationFilterOptions options) {
    try {
      JsonNode root = JSON.readTree(source);
      if (root == null || !root.isObject()) {
        throw new LocalizationParseException("INVALID_FORMATJS", "Expected a JSON object");
      }
      LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.FORMATJS_JSON);
      collectJson(
          root, "", new JsonContext(null, List.of()), options, catalog, jsonComments(source));
      return catalog;
    } catch (LocalizationParseException invalid) {
      throw invalid;
    } catch (Exception invalid) {
      throw new LocalizationParseException("INVALID_FORMATJS", "Invalid configured JSON", invalid);
    }
  }

  private static boolean containsJsonComments(byte[] source) {
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < source.length; index++) {
      byte current = source[index];
      if (quoted) {
        if (current == '"' && !escaped) {
          quoted = false;
        }
        escaped = current == '\\' && !escaped;
      } else if (current == '"') {
        quoted = true;
      } else if (current == '/'
          && index + 1 < source.length
          && (source[index + 1] == '/' || source[index + 1] == '*')) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, String> jsonComments(byte[] source) throws IOException {
    Map<String, String> notes = new LinkedHashMap<>();
    int previous = 0;
    try (JsonParser parser = JSON.getFactory().createParser(source)) {
      JsonToken token;
      while ((token = parser.nextToken()) != null) {
        int start = Math.toIntExact(parser.currentTokenLocation().getByteOffset());
        if (token == JsonToken.FIELD_NAME && start >= previous) {
          String gap = new String(source, previous, start - previous, StandardCharsets.UTF_8);
          int line = gap.lastIndexOf("//");
          int block = gap.lastIndexOf("/*");
          String note = null;
          if (line > block) {
            int end = gap.indexOf('\n', line);
            note = gap.substring(line + 2, end < 0 ? gap.length() : end).strip();
          } else if (block >= 0) {
            int end = gap.indexOf("*/", block + 2);
            if (end >= 0) {
              note = gap.substring(block + 2, end).strip();
            }
          }
          if (note != null && !note.isEmpty()) {
            String path = parser.getParsingContext().pathAsPointer().toString();
            notes.put(path.startsWith("/") ? path.substring(1) : path, note);
          }
        }
        previous = Math.toIntExact(parser.currentLocation().getByteOffset());
      }
    }
    return notes;
  }

  private static void collectJson(
      JsonNode object,
      String parent,
      JsonContext inherited,
      LocalizationFilterOptions options,
      LocalizationCatalog catalog,
      Map<String, String> comments) {
    if (!object.isObject()) {
      return;
    }
    JsonContext context = jsonContext(object, parent, inherited, options);
    object
        .fields()
        .forEachRemaining(
            entry -> {
              String path = parent.isEmpty() ? entry.getKey() : parent + "/" + entry.getKey();
              JsonNode value = entry.getValue();
              if (value.isObject()) {
                collectJson(value, path, context, options, catalog, comments);
              } else if (value.isArray()) {
                for (int index = 0; index < value.size(); index++) {
                  if (value.get(index).isObject()) {
                    collectJson(
                        value.get(index), path + "/" + index, context, options, catalog, comments);
                  }
                }
              } else if (value.isTextual() && selected(path, entry.getKey(), options)) {
                String id =
                    options.contains("useFullKeyPath") && !options.enabled("useFullKeyPath")
                        ? entry.getKey()
                        : path;
                String suffix = options.value("removeKeySuffix");
                if (suffix != null && id.endsWith(suffix)) {
                  id = id.substring(0, id.length() - suffix.length());
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                if (!context.usages().isEmpty()) {
                  metadata.put("references", context.usages());
                }
                String message = value.asText();
                if (options.enabled("convertToHtmlCodes")) {
                  InlineCodes coded = protectInlineCodes(message, options.inlinePatterns());
                  message = coded.text();
                  if (!coded.codes().isEmpty()) {
                    metadata.put("mojitoInlineCodes", coded.codes());
                  }
                }
                catalog.add(
                    id,
                    LocalizationMessage.of(
                        message,
                        context.note() == null ? comments.get(path) : context.note(),
                        null,
                        null,
                        metadata));
              }
            });
  }

  private static JsonContext jsonContext(
      JsonNode object, String parent, JsonContext inherited, LocalizationFilterOptions options) {
    String note = options.enabled("noteKeepOrReplace") ? inherited.note() : null;
    List<String> usages =
        options.enabled("usagesKeepOrReplace")
            ? new ArrayList<>(inherited.usages())
            : new ArrayList<>();
    boolean foundUsage = false;
    String positionPath = null;
    Integer positionLine = null;
    Integer positionColumn = null;
    var fields = object.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      String key = entry.getKey();
      String path = parent.isEmpty() ? key : parent + "/" + key;
      JsonNode value = entry.getValue();
      if (value.isTextual()) {
        if (matches(options.pattern("noteKeyPattern"), path, key)) {
          note = value.asText();
        }
        if (matches(options.pattern("usagesKeyPattern"), path, key)) {
          if (options.enabled("usagesKeepOrReplace") || !foundUsage) {
            usages.clear();
          }
          usages.add(value.asText());
          foundUsage = true;
        }
        if (matches(options.pattern("filePositionPathKeyPattern"), path, key)) {
          positionPath = value.asText();
        }
      }
      if (value.isIntegralNumber() || value.isTextual()) {
        Integer number = number(value);
        if (matches(options.pattern("filePositionLineKeyPattern"), path, key)) {
          positionLine = number;
        }
        if (matches(options.pattern("filePositionColKeyPattern"), path, key)) {
          positionColumn = number;
        }
      }
    }
    if (positionPath != null) {
      String location = positionPath;
      if (positionLine != null) {
        location += ":" + positionLine;
        if (positionColumn != null) {
          location += ":" + positionColumn;
        }
      }
      usages = new ArrayList<>(List.of(location));
    }
    return new JsonContext(note, List.copyOf(new LinkedHashSet<>(usages)));
  }

  private static Integer number(JsonNode value) {
    if (value.isIntegralNumber()) {
      return value.canConvertToInt() ? value.intValue() : null;
    }
    if (value.asText().startsWith("+")) {
      return null;
    }
    try {
      return Integer.valueOf(value.asText());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static boolean selected(String path, String key, LocalizationFilterOptions options) {
    Pattern exceptions = options.pattern("exceptions");
    boolean matches =
        matches(exceptions, path, key) || exceptions != null && exceptions.matcher(path).find();
    boolean all = !options.contains("extractAllPairs") || options.enabled("extractAllPairs");
    return all ? exceptions == null || !matches : matches;
  }

  private static boolean matches(Pattern pattern, String path, String key) {
    return pattern != null && (pattern.matcher(path).matches() || pattern.matcher(key).matches());
  }

  private static byte[] localizeJson(
      byte[] source,
      Map<String, String> translations,
      LocalizationFilterOptions options,
      boolean removeUntranslated) {
    try {
      JsonNode root = JSON.readTree(source);
      LocalizationCatalog catalog =
          applyExtractionPolicy(parseConfiguredJson(source, options), source, options);
      boolean modified = updateJson(root, "", catalog, translations, options, removeUntranslated);
      for (String key : translations.keySet()) {
        if (!catalog.messages().containsKey(key)) {
          throw new LocalizationParseException(
              "UNKNOWN_SKELETON_SLOT", "Unknown JSON message: " + key);
        }
      }
      if (removeUntranslated) {
        removeJsonUntranslated(root, "", catalog, translations, options);
      }
      if (!modified) {
        return source.clone();
      }
      if (!removeUntranslated) {
        return renderJsonTemplate(source, translations, catalog, options);
      }
      String localized = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
      if (source.length > 0 && source[source.length - 1] == '\n') {
        localized += "\n";
      }
      return localized.getBytes(StandardCharsets.UTF_8);
    } catch (LocalizationParseException invalid) {
      throw invalid;
    } catch (Exception invalid) {
      throw new LocalizationParseException("INVALID_FORMATJS", "Invalid localized JSON", invalid);
    }
  }

  private static byte[] renderJsonTemplate(
      byte[] source,
      Map<String, String> translations,
      LocalizationCatalog catalog,
      LocalizationFilterOptions options)
      throws IOException {
    List<JsonPatch> patches = new ArrayList<>();
    try (JsonParser parser = JSON.getFactory().createParser(source)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new LocalizationParseException("INVALID_FORMATJS", "Expected a JSON object");
      }
      collectJsonPatches(parser, "", translations, catalog, options, patches);
    }
    ByteArrayOutputStream result = new ByteArrayOutputStream(source.length);
    int previous = 0;
    for (JsonPatch patch : patches) {
      result.write(source, previous, patch.start() - previous);
      result.write(JSON.writeValueAsBytes(patch.value()));
      previous = patch.end();
    }
    result.write(source, previous, source.length - previous);
    return result.toByteArray();
  }

  private static void collectJsonPatches(
      JsonParser parser,
      String parent,
      Map<String, String> translations,
      LocalizationCatalog catalog,
      LocalizationFilterOptions options,
      List<JsonPatch> patches)
      throws IOException {
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      String key = parser.currentName();
      JsonToken token = parser.nextToken();
      String path = parent.isEmpty() ? key : parent + "/" + key;
      if (token == JsonToken.START_OBJECT) {
        collectJsonPatches(parser, path, translations, catalog, options, patches);
      } else if (token == JsonToken.START_ARRAY) {
        int index = 0;
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          if (parser.currentToken() == JsonToken.START_OBJECT) {
            collectJsonPatches(parser, path + "/" + index, translations, catalog, options, patches);
          } else if (parser.currentToken() == JsonToken.START_ARRAY) {
            parser.skipChildren();
          }
          index++;
        }
      } else if (token == JsonToken.VALUE_STRING && selected(path, key, options)) {
        String id = jsonIdentity(path, key, options);
        String translation = translations.get(id);
        if (translation != null && catalog.messages().containsKey(id)) {
          if (options.enabled("convertToHtmlCodes")) {
            translation = restoreInlineCodes(translation, catalog.messages().get(id));
          }
          if (!parser.getText().equals(translation)) {
            patches.add(
                new JsonPatch(
                    Math.toIntExact(parser.currentTokenLocation().getByteOffset()),
                    Math.toIntExact(parser.currentLocation().getByteOffset()),
                    translation));
          }
        }
      }
    }
  }

  private static String jsonIdentity(String path, String key, LocalizationFilterOptions options) {
    String id =
        options.contains("useFullKeyPath") && !options.enabled("useFullKeyPath") ? key : path;
    String suffix = options.value("removeKeySuffix");
    return suffix != null && id.endsWith(suffix)
        ? id.substring(0, id.length() - suffix.length())
        : id;
  }

  private static boolean updateJson(
      JsonNode node,
      String parent,
      LocalizationCatalog catalog,
      Map<String, String> translations,
      LocalizationFilterOptions options,
      boolean removeUntranslated) {
    if (!node.isObject()) {
      return false;
    }
    var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
    List<String> keys = new ArrayList<>();
    object.fieldNames().forEachRemaining(keys::add);
    boolean modified = false;
    for (String key : keys) {
      JsonNode value = object.get(key);
      String path = parent.isEmpty() ? key : parent + "/" + key;
      if (value.isObject()) {
        boolean nested =
            updateJson(value, path, catalog, translations, options, removeUntranslated);
        modified |= nested;
      } else if (value.isArray()) {
        for (int index = 0; index < value.size(); index++) {
          if (value.get(index).isObject()) {
            modified |=
                updateJson(
                    value.get(index),
                    path + "/" + index,
                    catalog,
                    translations,
                    options,
                    removeUntranslated);
          }
        }
      } else if (value.isTextual() && selected(path, key, options)) {
        String id =
            options.contains("useFullKeyPath") && !options.enabled("useFullKeyPath") ? key : path;
        String suffix = options.value("removeKeySuffix");
        if (suffix != null && id.endsWith(suffix)) {
          id = id.substring(0, id.length() - suffix.length());
        }
        if (!catalog.messages().containsKey(id)) {
          continue;
        }
        String translation = translations.get(id);
        if (translation != null) {
          if (options.enabled("convertToHtmlCodes")) {
            translation = restoreInlineCodes(translation, catalog.messages().get(id));
          }
          if (!value.asText().equals(translation)) {
            object.put(key, translation);
            modified = true;
          }
        } else if (removeUntranslated) {
          object.put(key, UNTRANSLATED);
          modified = true;
        }
      }
    }
    return modified;
  }

  private static boolean containsUntranslatedJsonValue(
      JsonNode object,
      String parent,
      LocalizationCatalog catalog,
      Map<String, String> translations,
      LocalizationFilterOptions options) {
    var fields = object.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      if (!field.getValue().isTextual() || !UNTRANSLATED.equals(field.getValue().asText())) {
        continue;
      }
      String path = parent.isEmpty() ? field.getKey() : parent + "/" + field.getKey();
      if (!selected(path, field.getKey(), options)) {
        continue;
      }
      String id = jsonIdentity(path, field.getKey(), options);
      if (catalog.messages().containsKey(id) && !translations.containsKey(id)) {
        return true;
      }
    }
    return false;
  }

  private static void removeJsonUntranslated(
      JsonNode node,
      String parent,
      LocalizationCatalog catalog,
      Map<String, String> translations,
      LocalizationFilterOptions options) {
    if (node.isObject()) {
      var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
      List<String> keys = new ArrayList<>();
      object.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        JsonNode value = object.get(key);
        String path = parent.isEmpty() ? key : parent + "/" + key;
        if (value.isObject()
            && containsUntranslatedJsonValue(value, path, catalog, translations, options)) {
          object.remove(key);
        } else if (value.isTextual()
            && UNTRANSLATED.equals(value.asText())
            && selected(path, key, options)
            && !translations.containsKey(jsonIdentity(path, key, options))) {
          object.remove(key);
        } else {
          removeJsonUntranslated(value, path, catalog, translations, options);
        }
      }
    } else if (node.isArray()) {
      var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
      for (int index = array.size() - 1; index >= 0; index--) {
        JsonNode value = array.get(index);
        String path = parent + "/" + index;
        if (value.isObject()
            && containsUntranslatedJsonValue(value, path, catalog, translations, options)) {
          array.remove(index);
        } else {
          removeJsonUntranslated(value, path, catalog, translations, options);
        }
      }
    }
  }

  private static String processAppleStrings(
      String source,
      boolean removeComments,
      boolean removeUntranslated,
      Set<String> untranslatedKeys) {
    if (source.stripLeading().startsWith("<")) {
      if (removeUntranslated) {
        source = removeUntranslatedAppleXmlEntries(source, untranslatedKeys);
      }
      return removeComments ? removeAppleXmlComments(source) : source;
    }
    if (removeUntranslated) {
      Matcher matcher = APPLE_OUTPUT_ENTRY.matcher(source);
      StringBuilder result = new StringBuilder();
      int scanned = 0;
      char quote = 0;
      while (matcher.find()) {
        quote = appleQuoteState(source, scanned, matcher.start(2), quote);
        String declaration = matcher.group(2);
        String value = matcher.group(4);
        boolean untranslated =
            (value.equals("\"" + UNTRANSLATED + "\"") || value.equals("'" + UNTRANSLATED + "'"))
                && untranslatedKeys.contains(
                    AppleStringsParser.decodeSourceToken(matcher.group(3)));
        String replacement =
            quote != 0
                ? matcher.group()
                : untranslated
                    ? ""
                    : (matcher.group(1) == null ? "" : matcher.group(1)) + declaration;
        matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        quote = appleQuoteState(source, matcher.start(2), matcher.end(), quote);
        scanned = matcher.end();
      }
      matcher.appendTail(result);
      source = result.toString();
    }
    return removeComments ? removeAppleComments(source) : source;
  }

  private static char appleQuoteState(String source, int start, int end, char quote) {
    for (int index = start; index < end; index++) {
      char current = source.charAt(index);
      if (quote != 0) {
        if (current == '\\' && index + 1 < end) {
          index++;
        } else if (current == quote) {
          quote = 0;
        }
      } else if (current == '"' || current == '\'') {
        quote = current;
      } else if (current == '/' && index + 1 < end) {
        char next = source.charAt(index + 1);
        if (next == '*') {
          int closing = source.indexOf("*/", index + 2);
          index = closing < 0 ? end : closing + 1;
        } else if (next == '/') {
          index += 2;
          while (index < end
              && source.charAt(index) != '\n'
              && source.charAt(index) != '\r'
              && source.charAt(index) != '\u2028'
              && source.charAt(index) != '\u2029') {
            index++;
          }
          index--;
        }
      }
    }
    return quote;
  }

  private static String removeAppleComments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    char quote = 0;
    for (int index = 0; index < source.length(); index++) {
      char current = source.charAt(index);
      if (quote != 0) {
        result.append(current);
        if (current == '\\' && index + 1 < source.length()) {
          result.append(source.charAt(++index));
        } else if (current == quote) {
          quote = 0;
        }
      } else if (current == '"' || current == '\'') {
        quote = current;
        result.append(current);
      } else if (current == '/' && index + 1 < source.length()) {
        char next = source.charAt(index + 1);
        if (next == '*') {
          int end = source.indexOf("*/", index + 2);
          index = end < 0 ? source.length() : end + 1;
        } else if (next == '/') {
          index += 2;
          while (index < source.length()
              && source.charAt(index) != '\n'
              && source.charAt(index) != '\r'
              && source.charAt(index) != '\u2028'
              && source.charAt(index) != '\u2029') {
            index++;
          }
          index--;
        } else {
          result.append(current);
        }
      } else {
        result.append(current);
      }
    }
    return result.toString();
  }

  private static String removeAppleXmlComments(String source) {
    StringBuilder result = new StringBuilder(source.length());
    for (int index = 0; index < source.length(); ) {
      String delimiter;
      boolean comment;
      if (source.startsWith("<!--", index)) {
        delimiter = "-->";
        comment = true;
      } else if (source.startsWith("<![CDATA[", index)) {
        delimiter = "]]>";
        comment = false;
      } else if (source.startsWith("<?", index)) {
        delimiter = "?>";
        comment = false;
      } else {
        result.append(source.charAt(index++));
        continue;
      }
      int end = source.indexOf(delimiter, index);
      if (end < 0) {
        throw new LocalizationParseException("INVALID_XML", "Unterminated Apple XML section");
      }
      end += delimiter.length();
      if (!comment) {
        result.append(source, index, end);
      }
      index = end;
    }
    return result.toString();
  }

  private static String removeUntranslatedAppleXmlEntries(
      String source, Set<String> untranslatedKeys) {
    Element dictionary = SecureXmlParser.parseApplePlist(source).getDocumentElement();
    if ("plist".equals(dictionary.getTagName())) {
      NodeList children = dictionary.getChildNodes();
      for (int index = 0; index < children.getLength(); index++) {
        if (children.item(index) instanceof Element element) {
          dictionary = element;
          break;
        }
      }
    }
    List<Boolean> untranslated = new ArrayList<>();
    NodeList entries = dictionary.getChildNodes();
    String key = null;
    for (int index = 0; index < entries.getLength(); index++) {
      if (entries.item(index) instanceof Element element) {
        if ("key".equals(element.getTagName())) {
          key = element.getTextContent();
        } else if ("string".equals(element.getTagName())) {
          untranslated.add(
              UNTRANSLATED.equals(element.getTextContent()) && untranslatedKeys.contains(key));
          key = null;
        }
      }
    }

    StringBuilder result = new StringBuilder(source.length());
    Deque<String> elements = new ArrayDeque<>();
    int previous = 0;
    int keyStart = -1;
    int valueIndex = 0;
    for (int index = 0; index < source.length(); ) {
      if (source.charAt(index) != '<') {
        index++;
        continue;
      }
      String delimiter =
          source.startsWith("<!--", index)
              ? "-->"
              : source.startsWith("<![CDATA[", index)
                  ? "]]>"
                  : source.startsWith("<?", index) ? "?>" : null;
      if (delimiter != null) {
        int end = source.indexOf(delimiter, index);
        if (end < 0) {
          throw new LocalizationParseException("INVALID_XML", "Unterminated Apple XML section");
        }
        index = end + delimiter.length();
        continue;
      }
      int end = SecureXmlParser.appleTagEnd(source, index + 1);
      String tag = source.substring(index + 1, end).strip();
      if (tag.startsWith("!")) {
        index = end + 1;
        continue;
      }
      if (tag.startsWith("/")) {
        String current = elements.pop();
        if ("dict".equals(elements.peek()) && "string".equals(current)) {
          if (untranslated.get(valueIndex++) && keyStart >= 0) {
            result.append(source, previous, keyStart);
            previous = end + 1;
          }
          keyStart = -1;
        }
      } else {
        boolean empty = tag.endsWith("/");
        if (empty) {
          tag = tag.substring(0, tag.length() - 1).strip();
        }
        String name = tag.split("\\s+", 2)[0];
        if ("dict".equals(elements.peek()) && "key".equals(name)) {
          keyStart = index;
        }
        if (empty) {
          if ("dict".equals(elements.peek()) && "string".equals(name)) {
            valueIndex++;
            keyStart = -1;
          }
        } else {
          elements.push(name);
        }
      }
      index = end + 1;
    }
    result.append(source, previous, source.length());
    return result.toString();
  }

  private static String removeGettextEntries(String source, String untranslatedMarker) {
    Matcher matcher = GETTEXT_OUTPUT_ENTRY.matcher(source);
    List<Integer> starts = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    if (starts.isEmpty() || starts.getFirst() != 0) {
      starts.addFirst(0);
    }
    starts.add(source.length());
    StringBuilder result = new StringBuilder();
    for (int index = 0; index + 1 < starts.size(); index++) {
      String block = source.substring(starts.get(index), starts.get(index + 1));
      if (!containsUntranslatedGettextValue(block, untranslatedMarker)) {
        result.append(block);
      }
    }
    return result.toString();
  }

  private static boolean containsUntranslatedGettextValue(String block, String untranslatedMarker) {
    Matcher directives = GETTEXT_UNTRANSLATED_VALUE.matcher(block);
    while (directives.find()) {
      String first = directives.group(1);
      StringBuilder value = new StringBuilder(first.substring(1, first.length() - 1));
      Matcher segments = GETTEXT_QUOTED_SEGMENT.matcher(directives.group(2));
      while (segments.find()) {
        value.append(segments.group(1));
      }
      if (untranslatedMarker.equals(value.toString())) {
        return true;
      }
    }
    return false;
  }

  private static InlineCodes protectInlineCodes(String source, List<Pattern> patterns) {
    List<Map<String, String>> codes = new ArrayList<>();
    StringBuilder output = new StringBuilder();
    int index = 0;
    while (index < source.length()) {
      Matcher selected = null;
      for (Pattern pattern : patterns) {
        Matcher matcher = pattern.matcher(source);
        if (matcher.find(index)
            && matcher.end() > matcher.start()
            && (selected == null || matcher.start() < selected.start())) {
          selected = matcher;
        }
      }
      if (selected == null) {
        output.append(source, index, source.length());
        break;
      }
      output.append(source, index, selected.start());
      String id = "p" + (codes.size() + 1);
      output.append("<br id='").append(id).append("'/>");
      codes.add(Map.of("id", id, "source", selected.group()));
      index = selected.end();
    }
    return new InlineCodes(output.toString(), List.copyOf(codes));
  }

  private static String restoreInlineCodes(String translation, LocalizationMessage message) {
    Object source = message.metadata() == null ? null : message.metadata().get("mojitoInlineCodes");
    if (!(source instanceof List<?> entries)) {
      return translation;
    }
    Map<String, String> codes = new LinkedHashMap<>();
    for (Object entry : entries) {
      Map<?, ?> code = (Map<?, ?>) entry;
      codes.put((String) code.get("id"), (String) code.get("source"));
    }
    Matcher matcher = HTML_CODE.matcher(translation);
    StringBuilder result = new StringBuilder();
    Set<String> used = new LinkedHashSet<>();
    while (matcher.find()) {
      String id = matcher.group(1);
      String original = codes.get(id);
      if (original == null || !used.add(id)) {
        throw new LocalizationParseException(
            "INVALID_INLINE_CODE", "Unknown or repeated protected code");
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(original));
    }
    matcher.appendTail(result);
    if (used.size() != codes.size()) {
      throw new LocalizationParseException("INVALID_INLINE_CODE", "Missing protected inline code");
    }
    return result.toString();
  }

  private record JsonContext(String note, List<String> usages) {}

  private record InlineCodes(String text, List<Map<String, String>> codes) {}

  private record JsonPatch(int start, int end, String value) {}
}
