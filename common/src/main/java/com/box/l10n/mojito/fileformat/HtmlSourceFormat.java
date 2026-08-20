package com.box.l10n.mojito.fileformat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal, byte-preserving implementation of Mojito's customized HTML_ALPHA resource filter. */
final class HtmlSourceFormat {

  private static final Pattern ATTRIBUTE =
      Pattern.compile("(?i)([a-z_:][a-z0-9_:.-]*)\\s*=\\s*([\"'])(.*?)\\2", Pattern.DOTALL);
  private static final Pattern COLLAPSED_WHITESPACE = Pattern.compile("[\\t\\n\\r\\f ]+");
  private static final Pattern IMAGE_PLACEHOLDER = Pattern.compile("<br id='p([1-9][0-9]*)'/>");
  private static final Pattern DOCUMENT_PART_BOUNDARY =
      Pattern.compile("(?i)<!doctype\\b|<head(?:\\s|>)|<(?:script|style)(?:\\s|>)");
  private static final String IMAGE_DESCRIPTION =
      "Do not translate: extracted image URL, adapt if needed";

  private HtmlSourceFormat() {}

  static LocalizationCatalog parse(String source, boolean includeImages, boolean suppressEmpty) {
    LocalizationCatalog catalog = new LocalizationCatalog(LocalizationFileFormat.HTML);
    for (Entry entry : entries(source, includeImages, suppressEmpty)) {
      Map<String, Object> metadata =
          entry.inline().isEmpty() ? null : Map.of("mojitoInlineCodes", entry.inline());
      catalog.add(
          entry.id(),
          LocalizationMessage.of(entry.value(), entry.description(), null, null, metadata));
    }
    return catalog;
  }

  static LocalizationSourceSkeleton extract(
      byte[] bytes, boolean includeImages, boolean suppressEmpty) {
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes);
    String source = LocalizationFileConverters.decode(bytes, null);
    List<LocalizationSourceSkeleton.LocalizationSourceSlot> slots = new ArrayList<>();
    for (Entry entry : owners(entries(source, includeImages, suppressEmpty))) {
      slots.add(
          new LocalizationSourceSkeleton.LocalizationSourceSlot(
              entry.id(),
              null,
              encoding.offset(source, entry.start()),
              encoding.offset(source, entry.end())));
    }
    return new LocalizationSourceSkeleton(
        1, LocalizationFileFormat.HTML.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    return render(skeleton, translations, false);
  }

  static byte[] render(
      LocalizationSourceSkeleton skeleton,
      Map<String, String> translations,
      boolean removeUntranslated) {
    if (skeleton.schemaVersion() != 1
        || !LocalizationFileFormat.HTML.id().equals(skeleton.sourceFormat())) {
      throw invalidSkeleton("Unsupported HTML source skeleton");
    }
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] original = encoding.encode(skeleton.source());
    List<Entry> entries = matchingEntries(skeleton);
    Map<String, Entry> byId = new LinkedHashMap<>();
    for (Entry entry : entries) {
      byId.put(entry.id(), entry);
    }
    for (String id : translations.keySet()) {
      if (!byId.containsKey(id)) {
        throw new LocalizationParseException(
            "UNKNOWN_SKELETON_SLOT", "Unknown HTML source slot: " + id);
      }
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream(original.length);
    int copied = 0;
    List<Entry> owners = owners(entries);
    for (int index = 0; index < owners.size(); index++) {
      Entry entry = owners.get(index);
      LocalizationSourceSkeleton.LocalizationSourceSlot slot = skeleton.slots().get(index);
      if (slot.start() < copied || slot.end() < slot.start() || slot.end() > original.length) {
        throw invalidSkeleton("Invalid HTML source-slot byte ownership");
      }
      output.write(original, copied, slot.start() - copied);
      String translation = translations.get(slot.id());
      String localized;
      if (translation != null) {
        localized = renderValue(entry, translation, entries, translations, removeUntranslated);
      } else if (removeUntranslated) {
        localized = omitValue(entry, entries, translations);
      } else {
        localized =
            applyNestedAttributes(
                skeleton.source().substring(entry.start(), entry.end()),
                entry.start(),
                entries,
                translations,
                false);
      }
      byte[] bytes = localized.getBytes(encoding.charset());
      output.write(bytes, 0, bytes.length);
      copied = slot.end();
    }
    output.write(original, copied, original.length - copied);
    return output.toByteArray();
  }

  private static List<Entry> matchingEntries(LocalizationSourceSkeleton skeleton) {
    for (boolean includeImages : List.of(false, true)) {
      for (boolean suppressEmpty : List.of(true, false)) {
        List<Entry> parsed = entries(skeleton.source(), includeImages, suppressEmpty);
        List<Entry> owners = owners(parsed);
        if (owners.size() != skeleton.slots().size()) {
          continue;
        }
        boolean matches = true;
        for (int index = 0; index < owners.size(); index++) {
          if (!owners.get(index).id().equals(skeleton.slots().get(index).id())) {
            matches = false;
            break;
          }
        }
        if (matches) {
          return parsed;
        }
      }
    }
    throw invalidSkeleton("HTML source slots do not own the original document");
  }

  private static String renderValue(
      Entry entry,
      String translation,
      List<Entry> entries,
      Map<String, String> translations,
      boolean removeUntranslated) {
    if (entry.attribute()) {
      return escapeAttribute(translation);
    }
    List<String> parts = new ArrayList<>();
    Matcher placeholder = IMAGE_PLACEHOLDER.matcher(translation);
    Set<Integer> seen = new HashSet<>();
    int cursor = 0;
    while (placeholder.find()) {
      parts.add(escapeText(translation.substring(cursor, placeholder.start())));
      int number = Integer.parseInt(placeholder.group(1));
      if (number < 1 || number > entry.tags().size()) {
        throw invalidMarkup("HTML translation references an unknown inline code");
      }
      if (!seen.add(number)) {
        throw invalidMarkup("HTML translation duplicates an owned inline image code");
      }
      String image = entry.tags().get(number - 1);
      int imageStart = entry.imageStarts().get(number - 1);
      parts.add(
          applyNestedAttributes(image, imageStart, entries, translations, removeUntranslated));
      cursor = placeholder.end();
    }
    parts.add(escapeText(translation.substring(cursor)));
    for (int index = 0; index < entry.tags().size(); index++) {
      String expected = "<br id='p" + (index + 1) + "'/>";
      if (!translation.contains(expected)) {
        throw invalidMarkup("HTML translation removed an owned inline image code");
      }
    }
    return String.join("", parts);
  }

  private static String omitValue(
      Entry entry, List<Entry> entries, Map<String, String> translations) {
    if (entry.attribute()) {
      return "";
    }
    StringBuilder retainedMarkup = new StringBuilder();
    for (int index = 0; index < entry.tags().size(); index++) {
      retainedMarkup.append(
          applyNestedAttributes(
              entry.tags().get(index),
              entry.imageStarts().get(index),
              entries,
              translations,
              true));
    }
    return retainedMarkup.toString();
  }

  private static String applyNestedAttributes(
      String source,
      int start,
      List<Entry> entries,
      Map<String, String> translations,
      boolean removeUntranslated) {
    String result = source;
    List<Entry> attributes =
        entries.stream()
            .filter(
                entry ->
                    entry.attribute()
                        && entry.start() >= start
                        && entry.end() <= start + source.length()
                        && (translations.containsKey(entry.id()) || removeUntranslated))
            .sorted(java.util.Comparator.comparingInt(Entry::start).reversed())
            .toList();
    for (Entry entry : attributes) {
      result =
          result.substring(0, entry.start() - start)
              + escapeAttribute(translations.getOrDefault(entry.id(), ""))
              + result.substring(entry.end() - start);
    }
    return result;
  }

  private static List<Entry> owners(List<Entry> entries) {
    List<Entry> ordered =
        entries.stream().sorted(java.util.Comparator.comparingInt(Entry::start)).toList();
    List<Entry> owners = new ArrayList<>();
    for (Entry entry : ordered) {
      Entry parent = owners.isEmpty() ? null : owners.get(owners.size() - 1);
      if (parent == null
          || parent.attribute()
          || entry.start() < parent.start()
          || entry.end() > parent.end()) {
        owners.add(entry);
      }
    }
    return owners;
  }

  private static List<Entry> entries(String source, boolean includeImages, boolean suppressEmpty) {
    List<Entry> entries = new ArrayList<>();
    Generator generator = new Generator();
    int position = 0;
    while (position < source.length()) {
      if (source.startsWith("<!--", position)) {
        int end = source.indexOf("-->", position + 4);
        if (end < 0) {
          throw invalid("Unterminated HTML comment");
        }
        position = end + 3;
        continue;
      }
      if (source.charAt(position) != '<') {
        position++;
        continue;
      }
      Tag tag = tag(source, position);
      position = tag.end();
      if (tag.name().isEmpty() || tag.closing()) {
        continue;
      }
      if (tag.name().equals("script") || tag.name().equals("style")) {
        int close = indexOfIgnoreCase(source, "</" + tag.name(), position);
        if (close < 0) {
          throw invalid("Unterminated protected HTML element");
        }
        position = tag(source, close).end();
        continue;
      }
      if (tag.name().equals("meta")) {
        Attribute name = attribute(tag, "name");
        Attribute content = attribute(tag, "content");
        if (name != null
            && content != null
            && (name.value().equalsIgnoreCase("description")
                || name.value().equalsIgnoreCase("keywords"))) {
          add(
              entries,
              generator,
              content.value(),
              null,
              null,
              List.of(),
              List.of(),
              List.of(),
              content.start(),
              content.end(),
              true,
              suppressEmpty);
        }
        continue;
      }
      if (tag.name().equals("img")) {
        addImage(entries, generator, tag, includeImages, suppressEmpty);
        continue;
      }
      Attribute title = attribute(tag, "title");
      if (title != null) {
        add(
            entries,
            generator,
            decodeEntities(title.value()),
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            title.start(),
            title.end(),
            true,
            suppressEmpty);
      }
      if (!translatableElement(tag.name())) {
        continue;
      }
      int close = indexOfIgnoreCase(source, "</" + tag.name(), position);
      int boundary = close < 0 ? source.length() : close;
      List<String> images = new ArrayList<>();
      List<Integer> imageStarts = new ArrayList<>();
      List<Integer> imageParts = new ArrayList<>();
      List<Map<String, Object>> codes = new ArrayList<>();
      int search = position;
      StringBuilder text = new StringBuilder();
      while (search < boundary) {
        int imageStart = indexOfIgnoreCase(source, "<img", search, boundary);
        if (imageStart < 0) {
          text.append(source, search, boundary);
          break;
        }
        text.append(source, search, imageStart);
        Tag image = tag(source, imageStart);
        if (image.end() > boundary) {
          throw invalid("HTML image crosses its owning element");
        }
        addImage(entries, generator, image, includeImages, suppressEmpty);
        images.add(image.text());
        imageStarts.add(image.start());
        imageParts.add(documentPart(source, image.start(), entries));
        String code = "<br id='p" + images.size() + "'/>";
        text.append(code);
        codes.add(Map.of("id", "p" + images.size(), "source", image.text()));
        search = image.end();
      }
      String visible = collapse(decodeEntities(text.toString()));
      String identity = visible;
      for (int index = 0; index < imageParts.size(); index++) {
        identity =
            identity.replace(
                "<br id='p" + (index + 1) + "'/>", "[#$dp" + imageParts.get(index) + "]");
      }
      add(
          entries,
          generator,
          visible,
          identity,
          null,
          images,
          imageStarts,
          codes,
          position,
          boundary,
          false,
          suppressEmpty);
      position = close < 0 ? boundary : tag(source, close).end();
    }
    return entries;
  }

  private static void addImage(
      List<Entry> entries,
      Generator generator,
      Tag image,
      boolean includeImages,
      boolean suppressEmpty) {
    Attribute alternate = attribute(image, "alt");
    if (alternate != null) {
      add(
          entries,
          generator,
          decodeEntities(alternate.value()),
          null,
          null,
          List.of(),
          List.of(),
          List.of(),
          alternate.start(),
          alternate.end(),
          true,
          suppressEmpty);
    }
    Attribute imageUrl = attribute(image, "src");
    if (includeImages && imageUrl != null) {
      add(
          entries,
          generator,
          decodeEntities(imageUrl.value()),
          null,
          IMAGE_DESCRIPTION,
          List.of(),
          List.of(),
          List.of(),
          imageUrl.start(),
          imageUrl.end(),
          true,
          suppressEmpty);
    }
  }

  private static void add(
      List<Entry> entries,
      Generator generator,
      String value,
      String identity,
      String description,
      List<String> tags,
      List<Integer> imageStarts,
      List<Map<String, Object>> inline,
      int start,
      int end,
      boolean attribute,
      boolean suppressEmpty) {
    if (value.isEmpty()) {
      return;
    }
    String id = generator.next(decodeEntities(identity == null ? value : identity));
    if (suppressEmpty && (value.isBlank() || value.equals("\u00a0"))) {
      return;
    }
    entries.add(
        new Entry(id, value, description, tags, imageStarts, inline, start, end, attribute));
  }

  private static int documentPart(String source, int imageStart, List<Entry> preceding) {
    int parts =
        1
            + (int)
                preceding.stream()
                    .filter(entry -> !IMAGE_DESCRIPTION.equals(entry.description()))
                    .count();
    Matcher marker = DOCUMENT_PART_BOUNDARY.matcher(source.substring(0, imageStart));
    while (marker.find()) {
      parts++;
    }
    return parts;
  }

  private static Attribute attribute(Tag tag, String name) {
    Matcher matcher = ATTRIBUTE.matcher(tag.text());
    while (matcher.find()) {
      if (matcher.group(1).equalsIgnoreCase(name)) {
        return new Attribute(
            matcher.group(3), tag.start() + matcher.start(3), tag.start() + matcher.end(3));
      }
    }
    return null;
  }

  private static Tag tag(String source, int start) {
    boolean quoted = false;
    char delimiter = 0;
    for (int position = start + 1; position < source.length(); position++) {
      char value = source.charAt(position);
      if (quoted) {
        if (value == delimiter) {
          quoted = false;
        }
      } else if (value == '"' || value == '\'') {
        quoted = true;
        delimiter = value;
      } else if (value == '>') {
        String text = source.substring(start, position + 1);
        String content = text.substring(1, text.length() - 1).trim();
        boolean closing = content.startsWith("/");
        if (closing) {
          content = content.substring(1).trim();
        }
        int separator = 0;
        while (separator < content.length()
            && (Character.isLetterOrDigit(content.charAt(separator))
                || content.charAt(separator) == '-'
                || content.charAt(separator) == ':')) {
          separator++;
        }
        String name = content.substring(0, separator).toLowerCase(Locale.ROOT);
        return new Tag(name, text, start, position + 1, closing);
      }
    }
    throw invalid("Unterminated HTML markup");
  }

  private static int indexOfIgnoreCase(String source, String match, int start) {
    return indexOfIgnoreCase(source, match, start, source.length());
  }

  private static int indexOfIgnoreCase(String source, String match, int start, int end) {
    for (int index = start; index <= end - match.length(); index++) {
      if (source.regionMatches(true, index, match, 0, match.length())) {
        return index;
      }
    }
    return -1;
  }

  private static boolean translatableElement(String name) {
    return name.equals("title")
        || name.equals("p")
        || name.equals("li")
        || name.equals("td")
        || name.equals("h1")
        || name.equals("h2")
        || name.equals("h3")
        || name.equals("label")
        || name.equals("button")
        || name.equals("span");
  }

  private static String collapse(String text) {
    return COLLAPSED_WHITESPACE.matcher(text.trim()).replaceAll(" ");
  }

  private static String decodeEntities(String source) {
    return source
        .replace("&nbsp;", "\u00a0")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
  }

  private static String escapeText(String source) {
    return source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeAttribute(String source) {
    return escapeText(source).replace("\"", "&quot;").replace("'", "&#39;");
  }

  private static LocalizationParseException invalid(String message) {
    return new LocalizationParseException("INVALID_HTML", message);
  }

  private static LocalizationParseException invalidSkeleton(String message) {
    return new LocalizationParseException("INVALID_SKELETON", message);
  }

  private static LocalizationParseException invalidMarkup(String message) {
    return new LocalizationParseException("INVALID_SKELETON_MARKUP", message);
  }

  private record Entry(
      String id,
      String value,
      String description,
      List<String> tags,
      List<Integer> imageStarts,
      List<Map<String, Object>> inline,
      int start,
      int end,
      boolean attribute) {}

  private record Tag(String name, String text, int start, int end, boolean closing) {}

  private record Attribute(String value, int start, int end) {}

  private static final class Generator {
    private final Map<String, Integer> occurrences = new LinkedHashMap<>();
    private String previous = md5("");

    Generator() {
      occurrences.put(previous, 1);
    }

    String next(String value) {
      String current = md5(value);
      String id = current + "-" + previous + "-" + occurrences.get(previous);
      occurrences.merge(current, 1, Integer::sum);
      previous = current;
      return id;
    }

    private static String md5(String value) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException unavailable) {
        throw new IllegalStateException("MD5 must be available in every Java runtime", unavailable);
      }
    }
  }
}
