package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native PO ownership that keeps headers, comments, obsolete entries, and C-string layout. */
final class GettextPoSourceSkeleton {

  private GettextPoSourceSkeleton() {}

  static LocalizationSourceSkeleton extract(byte[] bytes) {
    Charset charset = LocalizationFileConverters.gettextCharset(bytes);
    SourceSkeletonEncoding encoding =
        StandardCharsets.ISO_8859_1.equals(charset)
            ? SourceSkeletonEncoding.named("ISO-8859-1")
            : StandardCharsets.US_ASCII.equals(charset)
                ? SourceSkeletonEncoding.named("US-ASCII")
                : Charset.forName("windows-1252").equals(charset)
                    ? SourceSkeletonEncoding.named("CP1252")
                    : StandardCharsets.UTF_8.equals(charset)
                        ? SourceSkeletonEncoding.named("UTF-8")
                        : null;
    if (encoding == null) {
      throw invalid("UNSUPPORTED_SKELETON_ENCODING", "Unsupported gettext source encoding");
    }
    String source = LocalizationFileConverters.decode(bytes, charset);
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.GETTEXT_PO, bytes);
    Scanner scanner = new Scanner(source, encoding, charset, catalog);
    List<LocalizationSourceSlot> slots = scanner.scan();
    int required = 0;
    for (LocalizationMessage message : catalog.messages().values()) {
      required +=
          message.variants() == null
              ? 1
              : ((Map<?, ?>) message.metadata().get("gettextPluralIndexes")).size();
    }
    if (slots.size() != required) {
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Missing native gettext translation slot");
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.GETTEXT_PO.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported gettext source-skeleton version");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    LocalizationCatalog catalog =
        LocalizationFileConverters.parse(LocalizationFileFormat.GETTEXT_PO, original);
    Set<String> known = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      String key = slot.variant() == null ? slot.id() : slot.id() + "#" + slot.variant();
      if (!known.add(key)) {
        throw invalid("INVALID_SKELETON", "Duplicate native gettext translation slot");
      }
    }
    if (!known.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no native gettext source slot");
    }
    ByteArrayOutputStream result = new ByteArrayOutputStream(original.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalid("INVALID_SKELETON", "Invalid native gettext source-slot range");
      }
      result.write(original, previous, slot.start() - previous);
      String key = slot.variant() == null ? slot.id() : slot.id() + "#" + slot.variant();
      String translated = translations.get(key);
      if (translated == null) {
        result.write(original, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage descriptor = catalog.messages().get(slot.id());
        if (descriptor == null) {
          throw invalid("INVALID_SKELETON", "Missing native gettext source descriptor");
        }
        Integer pluralIndex = pluralIndex(descriptor, slot.variant());
        String nativeValue = GettextPoWriter.restore(descriptor, translated, pluralIndex);
        String quoted = GettextPoWriter.quote(nativeValue);
        if (!encoding.charset().newEncoder().canEncode(quoted)) {
          throw invalid(
              "INVALID_GETTEXT_ENCODING", "Translation cannot use the original PO charset");
        }
        String originalValue = encoding.decode(original, slot.start(), slot.end());
        String preserved = preserveQuotedLayout(originalValue, quoted);
        result.writeBytes(preserved.getBytes(encoding.charset()));
      }
      previous = slot.end();
    }
    result.write(original, previous, original.length - previous);
    return result.toByteArray();
  }

  private static Integer pluralIndex(LocalizationMessage descriptor, String variant) {
    if (variant == null) {
      if (descriptor.variants() != null) {
        throw invalid("INVALID_SKELETON", "Plural gettext source slot has no native variant");
      }
      return null;
    }
    if (descriptor.metadata() == null
        || !(descriptor.metadata().get("gettextPluralIndexes") instanceof Map<?, ?> indexes)) {
      throw invalid("INVALID_SKELETON", "Plural gettext source slot has no native indexes");
    }
    for (Map.Entry<?, ?> index : indexes.entrySet()) {
      if (variant.equals(index.getValue())) {
        return Integer.parseInt((String) index.getKey());
      }
    }
    throw invalid("INVALID_SKELETON", "Plural gettext source slot has no native index");
  }

  private static String preserveQuotedLayout(String original, String quoted) {
    List<Range> segments = new ArrayList<>();
    for (int index = 0; index < original.length(); ) {
      if (original.charAt(index) != '"') {
        index++;
        continue;
      }
      int end = quoteEnd(original, index);
      segments.add(new Range(index, end));
      index = end;
    }
    if (segments.isEmpty()) {
      throw invalid("INVALID_SKELETON", "Native gettext source slot has no quoted C string");
    }
    String content = quoted.substring(1, quoted.length() - 1);
    StringBuilder result = new StringBuilder(original.length() + content.length());
    int source = 0;
    int position = 0;
    for (int index = 0; index < segments.size(); index++) {
      Range segment = segments.get(index);
      result.append(original, source, segment.start()).append('"');
      int next;
      if (index + 1 == segments.size()) {
        next = content.length();
      } else {
        int width = original.codePointCount(segment.start() + 1, segment.end() - 1);
        int remaining = content.codePointCount(position, content.length());
        next = content.offsetByCodePoints(position, Math.min(width, remaining));
        while (next < content.length() && oddTrailingSlash(content, position, next)) {
          next = content.offsetByCodePoints(next, 1);
        }
      }
      result.append(content, position, next).append('"');
      position = next;
      source = segment.end();
    }
    return result.append(original, source, original.length()).toString();
  }

  private static boolean oddTrailingSlash(String value, int start, int end) {
    int count = 0;
    while (--end >= start && value.charAt(end) == '\\') {
      count++;
    }
    return count % 2 == 1;
  }

  private static int quoteEnd(String source, int start) {
    for (int index = start + 1; index < source.length(); index++) {
      if (source.charAt(index) == '\\') {
        index++;
      } else if (source.charAt(index) == '"') {
        return index + 1;
      }
    }
    throw invalid("INVALID_SKELETON", "Unterminated native gettext C string");
  }

  private static boolean horizontal(char value) {
    return value == ' ' || value == '\t' || value == '\u000b' || value == '\f';
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private record Range(int start, int end) {}

  private static final class Scanner {

    private final String source;
    private final SourceSkeletonEncoding encoding;
    private final GettextPoParser parser;
    private final LocalizationCatalog catalog;
    private final List<LocalizationSourceSlot> slots = new ArrayList<>();
    private String domain;
    private String context;
    private String id;
    private Directive active;

    private Scanner(
        String source,
        SourceSkeletonEncoding encoding,
        Charset charset,
        LocalizationCatalog catalog) {
      this.source = source;
      this.encoding = encoding;
      this.parser = new GettextPoParser(charset);
      this.catalog = catalog;
    }

    private List<LocalizationSourceSlot> scan() {
      int index = 0;
      boolean lineStart = true;
      while (index < source.length()) {
        char character = source.charAt(index);
        if (character == '\r' || character == '\n') {
          index++;
          lineStart = true;
          continue;
        }
        if (horizontal(character)) {
          index++;
          continue;
        }
        if (character == '#' && lineStart) {
          finish();
          if (id != null) {
            context = null;
            id = null;
          }
          while (index < source.length()
              && source.charAt(index) != '\r'
              && source.charAt(index) != '\n') {
            index++;
          }
          continue;
        }
        lineStart = false;
        if (character == '"') {
          if (active == null) {
            throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unowned gettext continuation");
          }
          int end = quoteEnd(source, index);
          active.append(source, index, end, parser);
          index = end;
          continue;
        }
        finish();
        int start = index;
        while (index < source.length()
            && (source.charAt(index) >= 'a' && source.charAt(index) <= 'z'
                || source.charAt(index) == '_')) {
          index++;
        }
        String keyword = source.substring(start, index);
        while (index < source.length() && horizontal(source.charAt(index))) {
          index++;
        }
        if ("msgstr".equals(keyword) && index < source.length() && source.charAt(index) == '[') {
          int bracket = ++index;
          while (index < source.length() && source.charAt(index) != ']') {
            index++;
          }
          if (index == source.length()) {
            throw invalid("INVALID_SKELETON", "Unterminated native gettext plural index");
          }
          keyword += "[" + source.substring(bracket, index).strip() + "]";
          index++;
          while (index < source.length() && horizontal(source.charAt(index))) {
            index++;
          }
        }
        if (index == source.length() || source.charAt(index) != '"') {
          throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Native gettext directive has no C string");
        }
        if ("msgctxt".equals(keyword) && id != null || "msgid".equals(keyword) && id != null) {
          context = null;
          id = null;
        }
        active = new Directive(keyword);
        int end = quoteEnd(source, index);
        active.append(source, index, end, parser);
        index = end;
      }
      finish();
      return slots;
    }

    private void finish() {
      if (active == null) {
        return;
      }
      Directive directive = active;
      active = null;
      switch (directive.keyword()) {
        case "domain" -> {
          domain = directive.value().toString();
          context = null;
          id = null;
        }
        case "msgctxt" -> context = directive.value().toString();
        case "msgid" -> id = directive.value().toString();
        case "msgstr" -> add(directive, null);
        default -> {
          if (directive.keyword().startsWith("msgstr[")) {
            int end = directive.keyword().indexOf(']');
            add(directive, Integer.parseInt(directive.keyword().substring(7, end)));
          }
        }
      }
    }

    private void add(Directive directive, Integer index) {
      if (id == null || id.isEmpty() && context == null) {
        return;
      }
      String selected = resolve();
      LocalizationMessage descriptor = catalog.messages().get(selected);
      String variant = null;
      if (index != null) {
        if (descriptor.metadata() == null
            || !(descriptor.metadata().get("gettextPluralIndexes") instanceof Map<?, ?> indexes)
            || !(indexes.get(Integer.toString(index)) instanceof String category)) {
          throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unmapped native gettext plural index");
        }
        variant = category;
      }
      slots.add(
          new LocalizationSourceSlot(
              selected,
              variant,
              encoding.offset(source, directive.start()),
              encoding.offset(source, directive.end())));
    }

    private String resolve() {
      String original = context == null ? id : context;
      for (Map.Entry<String, LocalizationMessage> candidate : catalog.messages().entrySet()) {
        Map<String, Object> metadata =
            candidate.getValue().metadata() == null ? Map.of() : candidate.getValue().metadata();
        String identity =
            metadata.get("gettextOriginalId") instanceof String value ? value : candidate.getKey();
        String selectedDomain =
            metadata.get("gettextDomain") instanceof String value ? value : null;
        if (original.equals(identity)
            && Objects.equals(effective(domain), effective(selectedDomain))) {
          return candidate.getKey();
        }
      }
      throw invalid("UNSUPPORTED_SKELETON_SOURCE", "Unmapped native gettext source identity");
    }

    private static String effective(String domain) {
      return domain == null ? "messages" : domain;
    }
  }

  private static final class Directive {

    private final String keyword;
    private final StringBuilder value = new StringBuilder();
    private int start = -1;
    private int end;

    private Directive(String keyword) {
      this.keyword = keyword;
    }

    private void append(String source, int from, int to, GettextPoParser parser) {
      if (start < 0) {
        start = from;
      }
      end = to;
      value.append(parser.quoted(source.substring(from, to)));
    }

    private String keyword() {
      return keyword;
    }

    private StringBuilder value() {
      return value;
    }

    private int start() {
      return start;
    }

    private int end() {
      return end;
    }
  }
}
