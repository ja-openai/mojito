package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic UTF-8 GNU gettext regeneration checked against actual msgfmt. */
final class GettextPoWriter {

  private static final Pattern ARGUMENT = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.-]*)\\}");
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
    if (!LocalizationFileFormat.GETTEXT_PO.id().equals(catalog.sourceFormat())) {
      throw new LocalizationParseException(
          "INVALID_SOURCE_FORMAT", "Gettext writer requires a gettext PO catalog");
    }
    TreeMap<String, LocalizationMessage> messages = new TreeMap<>(UNICODE_SCALAR_ORDER);
    messages.putAll(catalog.messages());
    StringBuilder output = new StringBuilder();
    List<Map.Entry<String, LocalizationMessage>> ordered = new ArrayList<>(messages.entrySet());
    Map<String, Map<?, ?>> headers = new HashMap<>();
    for (Map.Entry<String, LocalizationMessage> entry : ordered) {
      String declaredDomain = domain(entry.getValue());
      Map<?, ?> header = domainHeader(entry.getValue());
      if (header != null) {
        String effective = declaredDomain == null ? "messages" : declaredDomain;
        Map<?, ?> existing = headers.putIfAbsent(effective, header);
        if (existing != null && !existing.equals(header)) {
          throw invalid(
              "INCONSISTENT_GETTEXT_DOMAIN_HEADER",
              "One gettext domain cannot contain conflicting header metadata");
        }
      }
      validateIdentity(entry.getKey(), entry.getValue(), declaredDomain);
    }
    ordered.sort(
        Comparator.<Map.Entry<String, LocalizationMessage>, String>comparing(
                entry -> domain(entry.getValue()), Comparator.nullsFirst(UNICODE_SCALAR_ORDER))
            .thenComparing(Map.Entry::getKey, UNICODE_SCALAR_ORDER));
    boolean defaultHeader =
        ordered.isEmpty()
            || ordered.stream()
                .anyMatch(
                    entry ->
                        domain(entry.getValue()) == null || domainHeader(entry.getValue()) == null);
    if (defaultHeader) {
      Map<?, ?> header = headers.get("messages");
      appendHeader(
          output,
          header != null && header.get("locale") instanceof String locale
              ? locale
              : catalog.locale(),
          header != null && header.get("pluralForms") instanceof Map<?, ?> forms
              ? forms
              : pluralForms(messages),
          header != null && header.get("fields") instanceof List<?> fields ? fields : List.of());
    }
    String currentDomain = null;
    for (Map.Entry<String, LocalizationMessage> entry : ordered) {
      LocalizationMessage message = entry.getValue();
      Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
      String domain = domain(message);
      if (!Objects.equals(currentDomain, domain)) {
        if (!output.isEmpty()) {
          output.append('\n');
        }
        output.append("domain ").append(quote(domain)).append('\n');
        currentDomain = domain;
        Map<?, ?> header = headers.get(domain);
        if (header != null) {
          appendHeader(
              output,
              header.get("locale") instanceof String locale ? locale : null,
              header.get("pluralForms") instanceof Map<?, ?> forms ? forms : null,
              header.get("fields") instanceof List<?> fields ? fields : List.of());
        }
      }
      output.append('\n');
      for (String comment : strings(metadata.get("translatorComments"))) {
        appendComment(output, "# ", comment);
      }
      if (message.description() != null) {
        appendComment(output, "#. ", message.description());
      }
      List<String> references = strings(metadata.get("references"));
      if (!references.isEmpty()) {
        for (String reference : references) {
          if (reference.isBlank() || reference.chars().anyMatch(Character::isWhitespace)) {
            throw invalid("INVALID_GETTEXT_REFERENCE", "Unsafe gettext source reference");
          }
        }
        output.append("#: ").append(String.join(" ", references)).append('\n');
      }
      List<String> flags = strings(metadata.get("flags"));
      if (!flags.isEmpty()) {
        for (String flag : flags) {
          if (flag.isBlank() || flag.indexOf(',') >= 0 || flag.indexOf('\n') >= 0) {
            throw invalid("INVALID_GETTEXT_FLAG", "Unsafe gettext format flag");
          }
        }
        output.append("#, ").append(String.join(", ", flags)).append('\n');
      }
      if (metadata.containsKey("gettextPrevious")) {
        appendPrevious(output, metadata.get("gettextPrevious"));
      }
      String context = metadata.get("context") instanceof String value ? value : null;
      if (context != null) {
        output.append("msgctxt ").append(quote(context)).append('\n');
      }
      String source =
          metadata.get("sourceMessage") instanceof String value
              ? value
              : context == null
                  ? metadata.get("gettextOriginalId") instanceof String original
                      ? original
                      : entry.getKey()
                  : restore(message, message.defaultMessage(), null);
      output.append("msgid ").append(quote(source)).append('\n');
      if (message.variants() == null) {
        String translation =
            Boolean.TRUE.equals(metadata.get("gettextUntranslated"))
                ? ""
                : restore(message, message.defaultMessage(), null);
        output.append("msgstr ").append(quote(translation)).append('\n');
      } else {
        if (!(metadata.get("sourcePlural") instanceof String sourcePlural)
            || !(metadata.get("gettextPluralIndexes") instanceof Map<?, ?> rawIndexes)) {
          throw invalid(
              "INVALID_GETTEXT_PLURAL_METADATA",
              "Gettext plural writing requires source text and native indexes");
        }
        output.append("msgid_plural ").append(quote(sourcePlural)).append('\n');
        TreeMap<Integer, String> indexes = new TreeMap<>();
        for (Map.Entry<?, ?> index : rawIndexes.entrySet()) {
          if (!(index.getKey() instanceof String key)
              || !(index.getValue() instanceof String selector)
              || !message.variants().containsKey(selector)) {
            throw invalid("INVALID_GETTEXT_PLURAL_METADATA", "Invalid native plural index");
          }
          try {
            indexes.put(Integer.parseInt(key), selector);
          } catch (NumberFormatException exception) {
            throw invalid("INVALID_GETTEXT_PLURAL_METADATA", "Invalid native plural index");
          }
        }
        Set<Integer> untranslated = integers(metadata.get("gettextUntranslatedIndexes"));
        for (Map.Entry<Integer, String> index : indexes.entrySet()) {
          String value =
              untranslated.contains(index.getKey())
                  ? ""
                  : restore(message, message.variants().get(index.getValue()), index.getKey());
          output
              .append("msgstr[")
              .append(index.getKey())
              .append("] ")
              .append(quote(value))
              .append('\n');
        }
      }
    }
    return output.toString();
  }

  private static void appendHeader(
      StringBuilder output, String locale, Map<?, ?> forms, List<?> fields) {
    output.append("msgid \"\"\nmsgstr \"\"\n");
    output.append(quote("Content-Type: text/plain; charset=UTF-8\n")).append('\n');
    if (locale != null) {
      output.append(quote("Language: " + locale.replace('-', '_') + "\n")).append('\n');
    }
    if (forms != null) {
      output
          .append(
              quote(
                  "Plural-Forms: nplurals="
                      + forms.get("nplurals")
                      + "; plural="
                      + forms.get("expression")
                      + ";\n"))
          .append('\n');
    }
    for (Object item : fields) {
      Map<?, ?> field = (Map<?, ?>) item;
      String[] lines = ((String) field.get("value")).split("\n", -1);
      output.append(quote(field.get("name") + ": " + lines[0] + "\n")).append('\n');
      for (int index = 1; index < lines.length; index++) {
        output.append(quote(" " + lines[index] + "\n")).append('\n');
      }
    }
  }

  private static Map<?, ?> domainHeader(LocalizationMessage message) {
    if (message.metadata() == null || !message.metadata().containsKey("gettextDomainHeader")) {
      return null;
    }
    if (!(message.metadata().get("gettextDomainHeader") instanceof Map<?, ?> header)
        || header.keySet().stream()
            .anyMatch(
                key -> !"locale".equals(key) && !"pluralForms".equals(key) && !"fields".equals(key))
        || header.containsKey("locale")
            && (!(header.get("locale") instanceof String locale) || locale.isBlank())
        || header.containsKey("pluralForms")
            && (!(header.get("pluralForms") instanceof Map<?, ?> forms)
                || !(forms.get("nplurals") instanceof Number count)
                || count.intValue() < 1
                || count.intValue() > 100
                || !(forms.get("expression") instanceof String expression)
                || expression.isBlank())
        || header.containsKey("fields") && !validHeaderFields(header.get("fields"))) {
      throw invalid("INVALID_GETTEXT_DOMAIN_HEADER", "Invalid GNU gettext domain header metadata");
    }
    return header;
  }

  private static boolean validHeaderFields(Object value) {
    if (!(value instanceof List<?> fields) || fields.isEmpty()) {
      return false;
    }
    for (Object item : fields) {
      if (!(item instanceof Map<?, ?> field)
          || field.size() != 2
          || !(field.get("name") instanceof String name)
          || !(field.get("value") instanceof String content)
          || name.indexOf(':') >= 0
          || name.indexOf('\n') >= 0
          || name.indexOf('\r') >= 0
          || name.indexOf('\0') >= 0
          || "Content-Type".equalsIgnoreCase(name)
          || "Language".equalsIgnoreCase(name)
          || "Plural-Forms".equalsIgnoreCase(name)
          || content.indexOf('\r') >= 0
          || content.indexOf('\0') >= 0) {
        return false;
      }
      String[] continuations = content.split("\n", -1);
      for (int index = 1; index < continuations.length; index++) {
        if (continuations[index].indexOf(':') >= 0) {
          return false;
        }
      }
    }
    return true;
  }

  private static void validateIdentity(String id, LocalizationMessage message, String domain) {
    if (message.metadata() == null || !message.metadata().containsKey("gettextOriginalId")) {
      return;
    }
    if (!(message.metadata().get("gettextOriginalId") instanceof String original)
        || original.isBlank()
        || !id.equals(
            original
                + "@domain="
                + GettextPoParser.escapeDomain(domain == null ? "messages" : domain))
        || message.metadata().get("context") instanceof String context
            && !context.equals(original)) {
      throw invalid("INVALID_GETTEXT_DOMAIN_ID", "Invalid domain-qualified GNU gettext identity");
    }
  }

  private static String domain(LocalizationMessage message) {
    if (message.metadata() == null || !message.metadata().containsKey("gettextDomain")) {
      return null;
    }
    if (!(message.metadata().get("gettextDomain") instanceof String value)
        || value.indexOf('\0') >= 0
        || value.indexOf('/') >= 0
        || value.indexOf('\\') >= 0
        || value.codePoints().anyMatch(Character::isWhitespace)) {
      throw invalid("INVALID_GETTEXT_DOMAIN", "Invalid GNU gettext translation domain");
    }
    return value;
  }

  private static void appendPrevious(StringBuilder output, Object metadata) {
    if (!(metadata instanceof Map<?, ?> previous)
        || !(previous.get("id") instanceof String id)
        || previous.keySet().stream()
            .anyMatch(key -> !"context".equals(key) && !"id".equals(key) && !"plural".equals(key))
        || previous.containsKey("context") && !(previous.get("context") instanceof String)
        || previous.containsKey("plural") && !(previous.get("plural") instanceof String)
        || id.indexOf('\0') >= 0
        || previous.get("context") instanceof String context && context.indexOf('\0') >= 0
        || previous.get("plural") instanceof String plural && plural.indexOf('\0') >= 0) {
      throw invalid("INVALID_GETTEXT_PREVIOUS", "Invalid previous GNU gettext message history");
    }
    if (previous.get("context") instanceof String context) {
      output.append("#| msgctxt ").append(quote(context)).append('\n');
    }
    output.append("#| msgid ").append(quote(id)).append('\n');
    if (previous.get("plural") instanceof String plural) {
      output.append("#| msgid_plural ").append(quote(plural)).append('\n');
    }
  }

  private static Map<?, ?> pluralForms(Map<String, LocalizationMessage> messages) {
    Map<?, ?> selected = null;
    for (LocalizationMessage message : messages.values()) {
      if (message.metadata() != null
          && message.metadata().get("gettextPluralForms") instanceof Map<?, ?> current
          && !message.metadata().containsKey("gettextDomainHeader")) {
        if (selected != null && !selected.equals(current)) {
          throw invalid(
              "INCONSISTENT_GETTEXT_PLURAL_FORMS",
              "Gettext catalogs cannot contain conflicting plural formulas");
        }
        selected = current;
      }
    }
    return selected;
  }

  static String restore(LocalizationMessage message, String value, Integer pluralIndex) {
    Map<String, List<LocalizationPlaceholder>> placeholders = new HashMap<>();
    if (message.placeholders() != null) {
      for (LocalizationPlaceholder placeholder : message.placeholders()) {
        placeholders
            .computeIfAbsent(placeholder.name(), unused -> new ArrayList<>())
            .add(placeholder);
      }
    }
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Object percentMetadata =
        pluralIndex == null
            ? metadata.get("gettextEscapedPercents")
            : nested(metadata.get("gettextPluralEscapedPercents"), pluralIndex);
    Set<Integer> escapedPercents = integers(percentMetadata);
    Object separatorMetadata =
        pluralIndex == null
            ? metadata.get("gettextPrintfLineSeparators")
            : nested(metadata.get("gettextPluralPrintfLineSeparators"), pluralIndex);
    Map<Integer, String> lineSeparators = new HashMap<>();
    if (separatorMetadata instanceof List<?> separators) {
      for (Object separator : separators) {
        if (separator instanceof Map<?, ?> descriptor
            && descriptor.get("position") instanceof Number position
            && descriptor.get("source") instanceof String source) {
          lineSeparators.put(position.intValue(), source);
        }
      }
    }
    Map<String, Integer> occurrences = new HashMap<>();
    Matcher matcher = ARGUMENT.matcher(value);
    StringBuilder output = new StringBuilder();
    int previous = 0;
    int scalarOffset = 0;
    while (matcher.find()) {
      List<LocalizationPlaceholder> choices = placeholders.get(matcher.group(1));
      if (choices == null) {
        continue;
      }
      scalarOffset =
          appendLiteral(
              output,
              value.substring(previous, matcher.start()),
              scalarOffset,
              escapedPercents,
              lineSeparators);
      int occurrence = occurrences.getOrDefault(matcher.group(1), 0);
      occurrences.put(matcher.group(1), occurrence + 1);
      output.append(choices.get(Math.min(occurrence, choices.size() - 1)).source());
      scalarOffset += value.codePointCount(matcher.start(), matcher.end());
      previous = matcher.end();
    }
    appendLiteral(output, value.substring(previous), scalarOffset, escapedPercents, lineSeparators);
    return output.toString();
  }

  private static int appendLiteral(
      StringBuilder output,
      String value,
      int scalarOffset,
      Set<Integer> escapedPercents,
      Map<Integer, String> lineSeparators) {
    for (int offset = 0; offset < value.length(); ) {
      int character = value.codePointAt(offset);
      if (character == '%' && escapedPercents.contains(scalarOffset)) {
        output.append("%%");
      } else if (character == '\n' && lineSeparators.containsKey(scalarOffset)) {
        output.append(lineSeparators.get(scalarOffset));
      } else {
        output.appendCodePoint(character);
      }
      scalarOffset++;
      offset += Character.charCount(character);
    }
    return scalarOffset;
  }

  private static Object nested(Object value, int index) {
    return value instanceof Map<?, ?> map ? map.get(Integer.toString(index)) : null;
  }

  private static Set<Integer> integers(Object value) {
    Set<Integer> result = new HashSet<>();
    if (value instanceof List<?> values) {
      for (Object number : values) {
        if (number instanceof Number integer) {
          result.add(integer.intValue());
        }
      }
    }
    return result;
  }

  private static List<String> strings(Object value) {
    List<String> result = new ArrayList<>();
    if (value instanceof List<?> values) {
      for (Object string : values) {
        if (string instanceof String text) {
          result.add(text);
        }
      }
    }
    return result;
  }

  private static void appendComment(StringBuilder output, String prefix, String comment) {
    if (comment.indexOf('\n') >= 0
        || comment.indexOf('\r') >= 0
        || comment.indexOf('\u2028') >= 0
        || comment.indexOf('\u2029') >= 0) {
      throw invalid("INVALID_GETTEXT_COMMENT", "Gettext comments must fit on one physical line");
    }
    output.append(prefix).append(comment).append('\n');
  }

  static String quote(String value) {
    if (value.indexOf('\0') >= 0) {
      throw invalid("INVALID_GETTEXT_NUL", "GNU gettext silently truncates embedded NUL bytes");
    }
    StringBuilder output = new StringBuilder("\"");
    for (int index = 0; index < value.length(); ) {
      int character = value.codePointAt(index);
      switch (character) {
        case '\\' -> output.append("\\\\");
        case '"' -> output.append("\\\"");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\u0007' -> output.append("\\a");
        case '\u000b' -> output.append("\\v");
        default -> {
          if (character < 0x20 || character == 0x7f) {
            output.append('\\').append(String.format("%03o", character));
          } else {
            output.appendCodePoint(character);
          }
        }
      }
      index += Character.charCount(character);
    }
    return output.append('"').toString();
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }
}
