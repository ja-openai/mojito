package com.box.l10n.mojito.fileformat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GettextPoParser {

  private static final int LAST_PLURAL_SAMPLE = 1000;
  private static final List<Integer> EXTENDED_PLURAL_SAMPLES =
      List.of(
          1001,
          1002,
          1010,
          1011,
          1100,
          10_000,
          100_000,
          999_999,
          1_000_000,
          1_000_001,
          1_000_002,
          2_000_000,
          1_000_000_000);
  private static final List<Integer> PLURAL_SAMPLES = pluralSamples();
  private static final List<String> CLDR_CATEGORIES =
      List.of("zero", "one", "two", "few", "many", "other");
  private static final Pattern INDEXED_TRANSLATION = Pattern.compile("msgstr\\[(\\d+)]\\s+(.+)");
  private static final Pattern PLURAL_COUNT =
      Pattern.compile("(?:^|;)\\s*nplurals=\\s*(\\d+)\\s*(?:;|$)");
  private static final Pattern PLURAL_EXPRESSION =
      Pattern.compile("(?:^|;)\\s*plural=[ \\t]*([^;]+)");

  private int declaredPluralCount;
  private String declaredPluralExpression;
  private LongUnaryOperator pluralExpression;
  private final Charset charset;
  private String domain;
  private String activeLocale;
  private boolean mixedLocales;
  private final Map<String, DomainHeader> domainHeaders = new LinkedHashMap<>();
  private final List<Pending> messages = new ArrayList<>();

  GettextPoParser(Charset charset) {
    this.charset = charset;
  }

  LocalizationCatalog parse(String source) {
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.GETTEXT_PO);
    Entry entry = new Entry();
    for (String line : logicalDirectives(source)) {
      if (line.isEmpty()) {
        flush(catalog, entry);
        entry = new Entry();
        continue;
      }
      if (line.startsWith("#~")) {
        continue;
      }
      if (line.startsWith("#")) {
        if (entry.hasMessage()) {
          flush(catalog, entry);
          entry = new Entry();
        }
        comment(entry, line);
      } else if (line.startsWith("domain ")) {
        if (entry.id != null) {
          flush(catalog, entry);
          entry = new Entry();
        }
        domain = quoted(line.substring("domain ".length()));
        if (domain.indexOf('/') >= 0
            || domain.indexOf('\\') >= 0
            || domain.codePoints().anyMatch(Character::isWhitespace)) {
          throw new LocalizationParseException(
              "INVALID_GETTEXT_DOMAIN", "GNU gettext domain is unsafe as an MO output filename");
        }
        activateDomain(domain);
      } else if (line.startsWith("msgctxt ")) {
        entry.domain = domain;
        entry.context = quoted(line.substring("msgctxt ".length()));
        entry.active = Field.CONTEXT;
      } else if (line.startsWith("msgid_plural ")) {
        requireMessage(entry);
        entry.plural = quoted(line.substring("msgid_plural ".length()));
        entry.active = Field.PLURAL;
      } else if (line.startsWith("msgid ")) {
        if (entry.id != null) {
          flush(catalog, entry);
          entry = new Entry();
        }
        entry.domain = domain;
        entry.id = quoted(line.substring("msgid ".length()));
        entry.active = Field.ID;
      } else if (line.startsWith("msgstr[")) {
        requireMessage(entry);
        Matcher matcher = INDEXED_TRANSLATION.matcher(line);
        if (!matcher.matches()) {
          throw invalid("Malformed indexed gettext translation");
        }
        try {
          entry.activeIndex = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
          throw invalid("Gettext plural translation index is out of range");
        }
        if (entry.translations.putIfAbsent(entry.activeIndex, quoted(matcher.group(2))) != null) {
          throw invalid("Duplicate gettext plural translation index");
        }
        entry.active = Field.INDEXED_TRANSLATION;
      } else if (line.startsWith("msgstr ")) {
        requireMessage(entry);
        if (entry.translation != null) {
          throw invalid("Duplicate gettext translation");
        }
        entry.translation = quoted(line.substring("msgstr ".length()));
        entry.active = Field.TRANSLATION;
      } else if (line.startsWith("\"")) {
        append(entry, quoted(line));
      } else {
        throw invalid("Unsupported gettext directive: " + line);
      }
    }
    flush(catalog, entry);
    addMessages(catalog);
    return catalog;
  }

  private void flush(LocalizationCatalog catalog, Entry entry) {
    if (entry.previousContext != null && entry.previousId == null
        || entry.previousPlural != null && entry.previousId == null) {
      throw invalid("Previous gettext history requires a previous msgid");
    }
    if (entry.id == null) {
      return;
    }
    if (entry.id.isEmpty() && entry.context == null) {
      parseHeaders(catalog, entry.translation, entry.domain);
      return;
    }
    List<LocalizationPlaceholder> placeholders = PlaceholderNormalizer.placeholders();
    Map<String, Object> metadata = new LinkedHashMap<>();
    String id = entry.context == null || entry.context.isEmpty() ? entry.id : entry.context;
    String description = String.join("\n", entry.extractedComments);
    String message;
    Map<String, String> variants = null;
    if (entry.plural != null) {
      variants = new LinkedHashMap<>();
      Map<String, String> indexes = new LinkedHashMap<>();
      Map<String, List<String>> selectorMetadata = new LinkedHashMap<>();
      Map<String, List<Integer>> escapedPercents = new LinkedHashMap<>();
      Map<String, List<Map<String, Object>>> lineSeparators = new LinkedHashMap<>();
      List<Integer> untranslatedIndexes = new ArrayList<>();
      Map<Integer, String> translations = entry.translations;
      if (translations.isEmpty()) {
        translations = new LinkedHashMap<>();
        translations.put(0, entry.id);
        translations.put(1, entry.plural);
      }
      validatePluralIndexes(translations);
      Map<Integer, List<String>> selectors = pluralSelectors(activeLocale, translations);
      boolean expandedSelectors = false;
      for (Map.Entry<Integer, String> translation : translations.entrySet()) {
        List<String> categories = selectors.get(translation.getKey());
        String text =
            translation.getValue().isEmpty()
                ? sourcePlural(entry, translation.getKey())
                : translation.getValue();
        String normalized = normalize(entry, text, placeholders);
        if (translation.getValue().isEmpty()) {
          untranslatedIndexes.add(translation.getKey());
        }
        if (!preservesLiteralFormats(entry)) {
          List<Integer> positions = PlaceholderNormalizer.escapedPercentPositions(text);
          if (!positions.isEmpty()) {
            escapedPercents.put(Integer.toString(translation.getKey()), positions);
          }
          List<Map<String, Object>> separators = PlaceholderNormalizer.printfLineSeparators(text);
          if (!separators.isEmpty()) {
            lineSeparators.put(Integer.toString(translation.getKey()), separators);
          }
        }
        for (String category : categories) {
          variants.put(category, normalized);
        }
        String index = Integer.toString(translation.getKey());
        indexes.put(index, categories.getFirst());
        selectorMetadata.put(index, categories);
        expandedSelectors |= categories.size() > 1;
      }
      if (!variants.containsKey("other")) {
        if (variants.isEmpty()) {
          throw new LocalizationParseException(
              "MISSING_OTHER_VARIANT", "Gettext plural is missing other");
        }
        String fallback = null;
        for (String value : variants.values()) {
          fallback = value;
        }
        variants.put("other", fallback);
      }
      String selector =
          placeholders.stream()
              .filter(placeholder -> "integer".equals(placeholder.kind()))
              .map(LocalizationPlaceholder::name)
              .findFirst()
              .orElse("count");
      message = PlaceholderNormalizer.plural(selector, variants);
      metadata.put("sourceMessage", entry.id);
      metadata.put("sourcePlural", entry.plural);
      addCommonMetadata(entry, metadata);
      metadata.put("gettextPluralIndexes", indexes);
      if (!escapedPercents.isEmpty()) {
        metadata.put("gettextPluralEscapedPercents", escapedPercents);
      }
      if (!lineSeparators.isEmpty()) {
        metadata.put("gettextPluralPrintfLineSeparators", lineSeparators);
      }
      if (!untranslatedIndexes.isEmpty()) {
        metadata.put("gettextUntranslatedIndexes", untranslatedIndexes);
      }
      if (expandedSelectors) {
        metadata.put("gettextPluralSelectors", selectorMetadata);
      }
      if (pluralExpression != null) {
        metadata.put(
            "gettextPluralForms",
            Map.of("nplurals", declaredPluralCount, "expression", declaredPluralExpression));
      }
    } else {
      String text =
          entry.translation == null || entry.translation.isEmpty() ? entry.id : entry.translation;
      message = normalize(entry, text, placeholders);
      if (entry.translation == null || entry.translation.isEmpty()) {
        metadata.put("gettextUntranslated", true);
      }
      if (entry.translation != null
          && !entry.translation.isEmpty()
          && !entry.translation.equals(entry.id)) {
        metadata.put("sourceMessage", entry.id);
      }
      if (!preservesLiteralFormats(entry)) {
        List<Integer> positions = PlaceholderNormalizer.escapedPercentPositions(text);
        if (!positions.isEmpty()) {
          metadata.put("gettextEscapedPercents", positions);
        }
        List<Map<String, Object>> separators = PlaceholderNormalizer.printfLineSeparators(text);
        if (!separators.isEmpty()) {
          metadata.put("gettextPrintfLineSeparators", separators);
        }
      }
      addCommonMetadata(entry, metadata);
    }
    DomainHeader header = domainHeaders.get(effectiveDomain(entry.domain));
    if (header != null && (entry.domain != null || !header.fields().isEmpty())) {
      metadata.put("gettextDomainHeader", header.metadata());
    }
    messages.add(
        new Pending(
            id,
            effectiveDomain(entry.domain),
            LocalizationMessage.of(message, description, variants, placeholders, metadata)));
  }

  private void addMessages(LocalizationCatalog catalog) {
    Map<String, Set<String>> identities = new LinkedHashMap<>();
    for (Pending message : messages) {
      identities.computeIfAbsent(message.id(), ignored -> new HashSet<>()).add(message.domain());
    }
    for (Pending pending : messages) {
      String id = pending.id();
      LocalizationMessage message = pending.message();
      if (mixedLocales
          && (message.metadata() == null
              || !message.metadata().containsKey("gettextDomainHeader"))) {
        DomainHeader header = domainHeaders.get(pending.domain());
        if (header != null) {
          Map<String, Object> metadata =
              new LinkedHashMap<>(message.metadata() == null ? Map.of() : message.metadata());
          metadata.put("gettextDomainHeader", header.metadata());
          message =
              LocalizationMessage.of(
                  message.defaultMessage(),
                  message.description(),
                  message.variants(),
                  message.placeholders(),
                  metadata);
        }
      }
      if (identities.get(id).size() > 1) {
        id += "@domain=" + escapeDomain(pending.domain());
        Map<String, Object> metadata =
            new LinkedHashMap<>(message.metadata() == null ? Map.of() : message.metadata());
        metadata.put("gettextOriginalId", pending.id());
        message =
            LocalizationMessage.of(
                message.defaultMessage(),
                message.description(),
                message.variants(),
                message.placeholders(),
                metadata);
      }
      catalog.add(id, message);
    }
  }

  static String escapeDomain(String domain) {
    StringBuilder result = new StringBuilder();
    for (byte octet : domain.getBytes(StandardCharsets.UTF_8)) {
      int value = Byte.toUnsignedInt(octet);
      if (value >= 'a' && value <= 'z'
          || value >= 'A' && value <= 'Z'
          || value >= '0' && value <= '9'
          || value == '-'
          || value == '_'
          || value == '.'
          || value == '~') {
        result.append((char) value);
      } else {
        result.append('%');
        result.append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)));
        result.append(Character.toUpperCase(Character.forDigit(value & 15, 16)));
      }
    }
    return result.toString();
  }

  private static String effectiveDomain(String domain) {
    return domain == null ? "messages" : domain;
  }

  private static void addCommonMetadata(Entry entry, Map<String, Object> metadata) {
    if (entry.domain != null) {
      metadata.put("gettextDomain", entry.domain);
    }
    if (entry.previousId != null) {
      Map<String, Object> previous = new LinkedHashMap<>();
      if (entry.previousContext != null) {
        previous.put("context", entry.previousContext);
      }
      previous.put("id", entry.previousId);
      if (entry.previousPlural != null) {
        previous.put("plural", entry.previousPlural);
      }
      metadata.put("gettextPrevious", previous);
    }
    if (!entry.translatorComments.isEmpty()) {
      metadata.put("translatorComments", entry.translatorComments);
    }
    if (!entry.references.isEmpty()) {
      metadata.put("references", entry.references);
    }
    if (!entry.flags.isEmpty()) {
      metadata.put("flags", entry.flags);
    }
    if (entry.context != null) {
      metadata.put("context", entry.context);
    }
  }

  private static String normalize(
      Entry entry, String text, List<LocalizationPlaceholder> placeholders) {
    return preservesLiteralFormats(entry)
        ? text
        : PlaceholderNormalizer.normalize(text, placeholders);
  }

  private static boolean preservesLiteralFormats(Entry entry) {
    return entry.flags.contains("no-c-format") || entry.flags.contains("no-python-format");
  }

  private static String sourcePlural(Entry entry, int index) {
    return index == 0 ? entry.id : entry.plural;
  }

  private Map<Integer, List<String>> pluralSelectors(
      String locale, Map<Integer, String> translations) {
    Map<Integer, List<String>> result = new LinkedHashMap<>();
    if (pluralExpression == null) {
      for (Integer index : translations.keySet()) {
        result.put(index, List.of(pluralCategory(locale, index, translations.size())));
      }
      return result;
    }

    Map<Integer, List<Integer>> samples = new LinkedHashMap<>();
    Map<String, Map<Integer, Integer>> categoryCounts = new LinkedHashMap<>();
    for (int sample : PLURAL_SAMPLES) {
      int index = evaluate(sample);
      String category = cldrCategory(locale, sample);
      samples.computeIfAbsent(index, ignored -> new ArrayList<>()).add(sample);
      categoryCounts
          .computeIfAbsent(category, ignored -> new LinkedHashMap<>())
          .merge(index, 1, Integer::sum);
    }

    Map<String, Integer> categoryOwners = new LinkedHashMap<>();
    for (Map.Entry<String, Map<Integer, Integer>> category : categoryCounts.entrySet()) {
      int winner = -1;
      int count = -1;
      for (Map.Entry<Integer, Integer> candidate : category.getValue().entrySet()) {
        if (candidate.getValue() > count
            || candidate.getValue() == count && candidate.getKey() < winner) {
          winner = candidate.getKey();
          count = candidate.getValue();
        }
      }
      categoryOwners.put(category.getKey(), winner);
    }

    for (Integer index : translations.keySet()) {
      List<String> selectors = new ArrayList<>();
      for (Integer sample : samples.getOrDefault(index, List.of())) {
        if (!index.equals(categoryOwners.get(cldrCategory(locale, sample)))) {
          selectors.add("=" + sample);
        }
      }
      for (String category : CLDR_CATEGORIES) {
        if (index.equals(categoryOwners.get(category))) {
          selectors.add(category);
        }
      }
      if (selectors.isEmpty()) {
        selectors.add(pluralCategory(locale, index, translations.size()));
      }
      result.put(index, List.copyOf(selectors));
    }
    return result;
  }

  private String pluralCategory(String locale, int index, int size) {
    if (pluralExpression != null) {
      for (int sample : PLURAL_SAMPLES) {
        if (evaluate(sample) == index) {
          return cldrCategory(locale, sample);
        }
      }
    }
    String language = locale == null ? "" : locale.toLowerCase(Locale.ROOT).split("-")[0];
    List<String> categories =
        switch (language) {
          case "ar", "cy" -> List.of("zero", "one", "two", "few", "many", "other");
          case "ru", "uk", "be", "pl" -> List.of("one", "few", "many", "other");
          case "sr", "hr", "cs", "sk", "ro", "lt" -> List.of("one", "few", "other");
          case "sl" -> List.of("one", "two", "few", "other");
          case "he", "iw" -> List.of("one", "two", "other");
          case "ja", "ko", "zh", "th", "vi" -> List.of("other");
          default -> size <= 1 ? List.of("other") : List.of("one", "other");
        };
    return index < categories.size() ? categories.get(index) : "=" + index;
  }

  private void parseHeaders(LocalizationCatalog catalog, String headers, String declaredDomain) {
    if (headers == null) {
      return;
    }
    String key = effectiveDomain(declaredDomain);
    if (domainHeaders.containsKey(key)) {
      throw new LocalizationParseException(
          "INVALID_GETTEXT_DOMAIN_HEADER", "Duplicate gettext header in one translation domain");
    }
    String locale = null;
    int countValue = 0;
    String expressionValue = null;
    LongUnaryOperator parsedExpression = null;
    List<Map<String, String>> fields = new ArrayList<>();
    Map<String, String> previousField = null;
    for (String header : headers.split("\n")) {
      if (header.isBlank()) {
        continue;
      }
      int separator = header.indexOf(':');
      if (separator < 0) {
        if (previousField == null) {
          throw new LocalizationParseException(
              "INVALID_GETTEXT_DOMAIN_HEADER",
              "Gettext header continuation cannot alter a reserved native field");
        }
        previousField.put("value", previousField.get("value") + "\n" + header.strip());
        continue;
      }
      String name = header.substring(0, separator);
      String value = header.substring(separator + 1).strip();
      previousField = null;
      if ("Language".equalsIgnoreCase(name)) {
        locale = value.replace('_', '-');
      } else if ("Plural-Forms".equalsIgnoreCase(name)) {
        String forms = value;
        if (repeatedPluralField(forms, "nplurals") || repeatedPluralField(forms, "plural")) {
          throw GettextPluralExpression.invalid("Plural-Forms contains a duplicate declaration");
        }
        Matcher count = PLURAL_COUNT.matcher(forms);
        Matcher expression = PLURAL_EXPRESSION.matcher(forms);
        if (!count.find() || !expression.find()) {
          throw GettextPluralExpression.invalid("Plural-Forms must declare nplurals and plural");
        }
        try {
          countValue = Integer.parseInt(GettextPluralExpression.trimLeadingZeroes(count.group(1)));
        } catch (NumberFormatException exception) {
          throw GettextPluralExpression.invalid("Gettext nplurals is out of range");
        }
        if (countValue < 1 || countValue > 100) {
          throw GettextPluralExpression.invalid("Gettext nplurals must be between 1 and 100");
        }
        expressionValue = GettextPluralExpression.trimHorizontalWhitespace(expression.group(1));
        parsedExpression = GettextPluralExpression.parse(expressionValue);
      } else if (!"Content-Type".equalsIgnoreCase(name)) {
        previousField = new LinkedHashMap<>();
        previousField.put("name", name);
        previousField.put("value", value);
        fields.add(previousField);
      }
    }
    if (locale != null && !locale.isEmpty()) {
      if (catalog.locale() != null && !catalog.locale().equals(locale)) {
        mixedLocales = true;
        catalog.setLocale(null);
      } else if (!mixedLocales) {
        catalog.setLocale(locale);
      }
    }
    domainHeaders.put(
        key, new DomainHeader(locale, countValue, expressionValue, parsedExpression, fields));
    activateDomain(declaredDomain);
    if (pluralExpression != null) {
      for (int sample : PLURAL_SAMPLES) {
        evaluate(sample);
      }
    }
  }

  private static boolean repeatedPluralField(String forms, String name) {
    boolean found = false;
    for (String field : forms.split(";")) {
      int separator = field.indexOf('=');
      if (separator < 0 || !field.substring(0, separator).stripLeading().equals(name)) {
        continue;
      }
      if (found) {
        return true;
      }
      found = true;
    }
    return false;
  }

  private void activateDomain(String declaredDomain) {
    DomainHeader header = domainHeaders.get(effectiveDomain(declaredDomain));
    if (header == null) {
      header = domainHeaders.get("messages");
    }
    if (header == null) {
      activeLocale = null;
      declaredPluralCount = 0;
      declaredPluralExpression = null;
      pluralExpression = null;
      return;
    }
    activeLocale = header.locale();
    declaredPluralCount = header.pluralCount();
    declaredPluralExpression = header.pluralExpression();
    pluralExpression = header.expression();
  }

  private void validatePluralIndexes(Map<Integer, String> translations) {
    if (pluralExpression == null) {
      return;
    }
    for (Integer index : translations.keySet()) {
      if (index < 0 || index >= declaredPluralCount) {
        throw GettextPluralExpression.invalid("Gettext plural index exceeds nplurals");
      }
    }
    if (translations.size() != declaredPluralCount) {
      throw GettextPluralExpression.invalid("Gettext translations do not cover all plural forms");
    }
  }

  private int evaluate(int sample) {
    try {
      long index = pluralExpression.applyAsLong(sample);
      if (index < 0 || index >= declaredPluralCount) {
        throw GettextPluralExpression.invalid(
            "Gettext plural expression produced an invalid index");
      }
      return (int) index;
    } catch (ArithmeticException exception) {
      throw GettextPluralExpression.invalid("Gettext plural expression arithmetic is invalid");
    }
  }

  private static String cldrCategory(String locale, int n) {
    String tag = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    String language = tag.split("-")[0];
    int mod10 = n % 10;
    int mod100 = n % 100;
    return switch (language) {
      case "ja", "ko", "zh", "th", "vi" -> "other";
      case "fr" -> n <= 1 ? "one" : millionCategory(n);
      case "pt" -> (tag.equals("pt-pt") ? n == 1 : n <= 1) ? "one" : millionCategory(n);
      case "ca", "es", "it" -> n == 1 ? "one" : millionCategory(n);
      case "ar" ->
          n == 0
              ? "zero"
              : n == 1
                  ? "one"
                  : n == 2
                      ? "two"
                      : mod100 >= 3 && mod100 <= 10 ? "few" : mod100 >= 11 ? "many" : "other";
      case "ru", "uk", "be" ->
          mod10 == 1 && mod100 != 11
              ? "one"
              : mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14) ? "few" : "many";
      case "pl" ->
          n == 1
              ? "one"
              : mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14) ? "few" : "many";
      case "sr", "hr" ->
          mod10 == 1 && mod100 != 11
              ? "one"
              : mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14) ? "few" : "other";
      case "cs", "sk" -> n == 1 ? "one" : n >= 2 && n <= 4 ? "few" : "other";
      case "sl" ->
          mod100 == 1 ? "one" : mod100 == 2 ? "two" : mod100 == 3 || mod100 == 4 ? "few" : "other";
      case "cy" ->
          n == 0
              ? "zero"
              : n == 1 ? "one" : n == 2 ? "two" : n == 3 ? "few" : n == 6 ? "many" : "other";
      case "he", "iw" -> n == 1 ? "one" : n == 2 ? "two" : "other";
      default -> n == 1 ? "one" : "other";
    };
  }

  private static String millionCategory(int value) {
    return value != 0 && value % 1_000_000 == 0 ? "many" : "other";
  }

  private static List<Integer> pluralSamples() {
    List<Integer> samples =
        new ArrayList<>(LAST_PLURAL_SAMPLE + 1 + EXTENDED_PLURAL_SAMPLES.size());
    for (int sample = 0; sample <= LAST_PLURAL_SAMPLE; sample++) {
      samples.add(sample);
    }
    samples.addAll(EXTENDED_PLURAL_SAMPLES);
    return List.copyOf(samples);
  }

  private void comment(Entry entry, String line) {
    if (line.startsWith("#.")) {
      entry.extractedComments.add(line.substring(2).strip());
    } else if (line.startsWith("#:")) {
      for (String reference : line.substring(2).strip().split("\\s+")) {
        if (!reference.isEmpty()) {
          entry.references.add(reference);
        }
      }
    } else if (line.startsWith("#,") || line.startsWith("#=")) {
      for (String flag : line.substring(2).split(",")) {
        if (!flag.isBlank()) {
          String value = flag.strip();
          String opposite =
              value.endsWith("-format")
                  ? value.startsWith("no-") ? value.substring(3) : "no-" + value
                  : null;
          entry.flags.remove(value);
          if (opposite != null) {
            entry.flags.remove(opposite);
          }
          entry.flags.add(value);
        }
      }
    } else if (line.startsWith("#|")) {
      previous(entry, line.substring(2));
    } else {
      String value = line.substring(1).strip();
      if (!value.isEmpty()) {
        entry.translatorComments.add(value);
      }
    }
  }

  private void previous(Entry entry, String source) {
    String value = trimAsciiLeading(source);
    if (value.startsWith("\"")) {
      String continuation = quoted(value);
      if (entry.previousActive == null) {
        throw invalid("Previous gettext continuation has no active field");
      }
      switch (entry.previousActive) {
        case CONTEXT -> entry.previousContext += continuation;
        case ID -> entry.previousId += continuation;
        case PLURAL -> entry.previousPlural += continuation;
        default -> throw invalid("Invalid previous gettext continuation");
      }
      return;
    }
    for (String directive : List.of("msgid_plural", "msgctxt", "msgid")) {
      if (value.startsWith(directive)) {
        String argument = value.substring(directive.length());
        if (!argument.isEmpty()
            && !asciiWhitespace(argument.charAt(0))
            && argument.charAt(0) != '"') {
          continue;
        }
        String text = quoted(trimAsciiLeading(argument));
        switch (directive) {
          case "msgctxt" -> {
            if (entry.previousContext != null || entry.previousId != null) {
              throw invalid("Invalid previous gettext context ordering");
            }
            entry.previousContext = text;
            entry.previousActive = Field.CONTEXT;
          }
          case "msgid" -> {
            if (entry.previousId != null) {
              throw invalid("Duplicate previous gettext msgid");
            }
            entry.previousId = text;
            entry.previousActive = Field.ID;
          }
          case "msgid_plural" -> {
            if (entry.previousId == null || entry.previousPlural != null) {
              throw invalid("Invalid previous gettext plural ordering");
            }
            entry.previousPlural = text;
            entry.previousActive = Field.PLURAL;
          }
          default -> throw invalid("Unsupported previous gettext directive");
        }
        return;
      }
    }
    throw invalid("Unsupported previous gettext directive");
  }

  private static List<String> logicalDirectives(String source) {
    String spliced = source.replaceAll("\\\\(?:\\r\\n|\\n)", "");
    List<String> result = new ArrayList<>();
    for (String physical : spliced.split("\\r\\n|\\r|\\n", -1)) {
      String line = trimAsciiLeading(physical);
      if (line.isEmpty() || line.charAt(0) == '#') {
        result.add(line);
        continue;
      }
      for (int index = 0; index < line.length(); ) {
        while (index < line.length() && asciiWhitespace(line.charAt(index))) {
          index++;
        }
        if (index == line.length()) {
          break;
        }
        if (line.charAt(index) == '"') {
          int end = quotedEnd(line, index);
          result.add(line.substring(index, end));
          index = end;
          continue;
        }
        int start = index;
        while (index < line.length()
            && (line.charAt(index) >= 'a' && line.charAt(index) <= 'z'
                || line.charAt(index) == '_')) {
          index++;
        }
        if (start == index) {
          throw invalid("Unsupported gettext directive");
        }
        String keyword = line.substring(start, index);
        while (index < line.length() && asciiWhitespace(line.charAt(index))) {
          index++;
        }
        if ("msgstr".equals(keyword) && index < line.length() && line.charAt(index) == '[') {
          index++;
          while (index < line.length() && asciiWhitespace(line.charAt(index))) {
            index++;
          }
          int digits = index;
          while (index < line.length() && line.charAt(index) >= '0' && line.charAt(index) <= '9') {
            index++;
          }
          if (digits == index) {
            throw invalid("Malformed indexed gettext translation");
          }
          String number = line.substring(digits, index);
          while (index < line.length() && asciiWhitespace(line.charAt(index))) {
            index++;
          }
          if (index >= line.length() || line.charAt(index++) != ']') {
            throw invalid("Malformed indexed gettext translation");
          }
          keyword += "[" + number + "]";
          while (index < line.length() && asciiWhitespace(line.charAt(index))) {
            index++;
          }
        }
        if (index >= line.length() || line.charAt(index) != '"') {
          throw invalid("Gettext directive must contain a quoted C string");
        }
        int end = quotedEnd(line, index);
        result.add(keyword + " " + line.substring(index, end));
        index = end;
      }
    }
    return result;
  }

  private static int quotedEnd(String value, int start) {
    for (int index = start + 1; index < value.length(); index++) {
      if (value.charAt(index) == '\\') {
        index++;
      } else if (value.charAt(index) == '"') {
        return index + 1;
      }
    }
    throw invalid("Unterminated gettext C string");
  }

  private static String trimAsciiLeading(String value) {
    int index = 0;
    while (index < value.length() && asciiWhitespace(value.charAt(index))) {
      index++;
    }
    return value.substring(index);
  }

  private static boolean asciiWhitespace(char value) {
    return value == ' ' || value == '\t' || value == '\u000b' || value == '\f';
  }

  private static void append(Entry entry, String value) {
    if (entry.active == null) {
      throw invalid("Gettext continuation without an active directive");
    }
    switch (entry.active) {
      case CONTEXT -> entry.context += value;
      case ID -> entry.id += value;
      case PLURAL -> entry.plural += value;
      case TRANSLATION -> entry.translation += value;
      case INDEXED_TRANSLATION ->
          entry.translations.merge(entry.activeIndex, value, String::concat);
    }
  }

  String quoted(String source) {
    String text = source.strip();
    if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
      throw invalid("Gettext directive must contain a quoted C string");
    }
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    for (int index = 1; index < text.length() - 1; ) {
      int character = text.codePointAt(index);
      index += Character.charCount(character);
      if (character != '\\') {
        result.writeBytes(new String(Character.toChars(character)).getBytes(charset));
      } else if (index >= text.length() - 1) {
        throw invalid("Trailing gettext escape");
      } else {
        char escaped = text.charAt(index++);
        if (escaped == 'x') {
          int start = index;
          while (index < text.length() - 1 && Character.digit(text.charAt(index), 16) >= 0) {
            index++;
          }
          if (start == index) {
            throw invalid("Gettext hexadecimal escape has no digits");
          }
          result.write(Integer.parseInt(text.substring(Math.max(start, index - 2), index), 16));
        } else if (escaped >= '0' && escaped <= '7') {
          int value = escaped - '0';
          for (int count = 1; count < 3 && index < text.length() - 1; count++) {
            char digit = text.charAt(index);
            if (digit < '0' || digit > '7') {
              break;
            }
            value = value * 8 + digit - '0';
            index++;
          }
          result.write(value);
        } else {
          result.write(
              switch (escaped) {
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'a' -> '\u0007';
                case 'v' -> '\u000b';
                case '\\', '"' -> escaped;
                default -> throw invalid("Unsupported gettext C escape");
              });
        }
      }
    }
    try {
      String decoded =
          charset
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(result.toByteArray()))
              .toString();
      if (decoded.indexOf('\0') >= 0) {
        throw new LocalizationParseException(
            "INVALID_GETTEXT_NUL", "GNU gettext silently truncates embedded NUL bytes");
      }
      return decoded;
    } catch (CharacterCodingException exception) {
      throw new LocalizationParseException(
          "INVALID_GETTEXT_ENCODING",
          "Gettext C escapes must form valid " + charset.displayName(),
          exception);
    }
  }

  private static void requireMessage(Entry entry) {
    if (entry.id == null) {
      throw invalid("Gettext translation or plural appears before msgid");
    }
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_GETTEXT", message);
  }

  private enum Field {
    CONTEXT,
    ID,
    PLURAL,
    TRANSLATION,
    INDEXED_TRANSLATION
  }

  private record Pending(String id, String domain, LocalizationMessage message) {}

  private record DomainHeader(
      String locale,
      int pluralCount,
      String pluralExpression,
      LongUnaryOperator expression,
      List<Map<String, String>> fields) {

    private Map<String, Object> metadata() {
      Map<String, Object> result = new LinkedHashMap<>();
      if (locale != null && !locale.isEmpty()) {
        result.put("locale", locale);
      }
      if (expression != null) {
        result.put("pluralForms", Map.of("nplurals", pluralCount, "expression", pluralExpression));
      }
      if (!fields.isEmpty()) {
        result.put("fields", fields);
      }
      return result;
    }
  }

  private static final class Entry {
    private final List<String> extractedComments = new ArrayList<>();
    private final List<String> translatorComments = new ArrayList<>();
    private final List<String> references = new ArrayList<>();
    private final List<String> flags = new ArrayList<>();
    private final Map<Integer, String> translations = new LinkedHashMap<>();
    private String context;
    private String id;
    private String plural;
    private String translation;
    private String domain;
    private String previousContext;
    private String previousId;
    private String previousPlural;
    private Field active;
    private Field previousActive;
    private int activeIndex;

    private boolean hasMessage() {
      return id != null && (translation != null || !translations.isEmpty());
    }
  }
}
