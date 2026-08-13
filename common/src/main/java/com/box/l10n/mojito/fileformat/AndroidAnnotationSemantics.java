package com.box.l10n.mojito.fileformat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves annotation semantics produced by Android's unescaped native span encoding. */
final class AndroidAnnotationSemantics {

  private static final Pattern ATTRIBUTE =
      Pattern.compile("([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*\"([^\"]*)\"");

  private static final Map<String, Integer> COLOR_NAMES =
      Map.ofEntries(
          Map.entry("black", 0xff000000),
          Map.entry("darkgray", 0xff444444),
          Map.entry("gray", 0xff888888),
          Map.entry("lightgray", 0xffcccccc),
          Map.entry("white", 0xffffffff),
          Map.entry("red", 0xffff0000),
          Map.entry("green", 0xff00ff00),
          Map.entry("blue", 0xff0000ff),
          Map.entry("yellow", 0xffffff00),
          Map.entry("cyan", 0xff00ffff),
          Map.entry("magenta", 0xffff00ff),
          Map.entry("aqua", 0xff00ffff),
          Map.entry("fuchsia", 0xffff00ff),
          Map.entry("darkgrey", 0xff444444),
          Map.entry("grey", 0xff888888),
          Map.entry("lightgrey", 0xffcccccc),
          Map.entry("lime", 0xff00ff00),
          Map.entry("maroon", 0xff800000),
          Map.entry("navy", 0xff000080),
          Map.entry("olive", 0xff808000),
          Map.entry("purple", 0xff800080),
          Map.entry("silver", 0xffc0c0c0),
          Map.entry("teal", 0xff008080));

  private AndroidAnnotationSemantics() {}

  static List<Map<String, Object>> spans(String source) {
    List<Map<String, Object>> result = new ArrayList<>();
    int span = 0;
    for (int index = 0; index < source.length(); index++) {
      if (source.charAt(index) != '<'
          || index + 1 >= source.length()
          || !Character.isLetter(source.charAt(index + 1))) {
        continue;
      }
      int end = tagEnd(source, index + 1);
      if (end < 0) {
        continue;
      }
      String tag = source.substring(index + 1, end);
      int nameEnd = 0;
      while (nameEnd < tag.length() && !Character.isWhitespace(tag.charAt(nameEnd))) {
        nameEnd++;
      }
      String name = tag.substring(0, nameEnd);
      if ("annotation".equals(name)) {
        List<Map<String, String>> original = new ArrayList<>();
        StringBuilder encoded = new StringBuilder(name);
        Matcher attributes = ATTRIBUTE.matcher(tag.substring(nameEnd));
        while (attributes.find()) {
          String key = attributes.group(1);
          String value = decodeAttribute(attributes.group(2));
          original.add(annotation(key, value));
          encoded.append(';').append(key).append('=').append(value);
        }
        List<Map<String, String>> runtime = decodeAnnotations(encoded.toString());
        if (!runtime.equals(original)) {
          Map<String, Object> projection = new LinkedHashMap<>();
          projection.put("span", span);
          projection.put("annotations", runtime);
          result.add(projection);
        }
      }
      span++;
      index = end;
    }
    return result;
  }

  static List<Map<String, Object>> styles(String source) {
    List<Map<String, Object>> result = new ArrayList<>();
    int span = 0;
    for (int index = 0; index < source.length(); index++) {
      if (source.charAt(index) != '<'
          || index + 1 >= source.length()
          || !Character.isLetter(source.charAt(index + 1))) {
        continue;
      }
      int end = tagEnd(source, index + 1);
      if (end < 0) {
        continue;
      }
      String tag = source.substring(index + 1, end);
      int nameEnd = 0;
      while (nameEnd < tag.length() && !Character.isWhitespace(tag.charAt(nameEnd))) {
        nameEnd++;
      }
      String name = tag.substring(0, nameEnd);
      if ("font".equals(name) || "a".equals(name)) {
        List<Map<String, String>> attributes = new ArrayList<>();
        StringBuilder encoded = new StringBuilder(name);
        Matcher matches = ATTRIBUTE.matcher(tag.substring(nameEnd));
        while (matches.find()) {
          String key = matches.group(1);
          String value = decodeAttribute(matches.group(2));
          attributes.add(annotation(key, value));
          encoded.append(';').append(key).append('=').append(value);
        }
        List<Map<String, Object>> runtime =
            styleEffects(name, encoded.toString(), attributes, true);
        List<Map<String, Object>> original =
            styleEffects(name, encoded.toString(), attributes, false);
        if (!runtime.equals(original)
            || runtime.stream().anyMatch(effect -> effect.containsKey("color"))) {
          Map<String, Object> projection = new LinkedHashMap<>();
          projection.put("span", span);
          projection.put("effects", runtime);
          result.add(projection);
        }
      }
      span++;
      index = end;
    }
    return result;
  }

  static List<Map<String, Object>> paragraphs(String source) {
    List<ParagraphRange> candidates = new ArrayList<>();
    Deque<OpenParagraph> open = new ArrayDeque<>();
    StringBuilder visible = new StringBuilder();
    int span = 0;
    for (int index = 0; index < source.length(); index++) {
      if (source.charAt(index) != '<'
          || index + 1 >= source.length()
          || source.charAt(index + 1) != '/' && !Character.isLetter(source.charAt(index + 1))) {
        visible.append(source.charAt(index));
        continue;
      }
      int end = tagEnd(source, index + 1);
      if (end < 0) {
        visible.append(source.charAt(index));
        continue;
      }
      boolean closing = source.charAt(index + 1) == '/';
      String tag = source.substring(index + (closing ? 2 : 1), end);
      int nameEnd = 0;
      while (nameEnd < tag.length()
          && !Character.isWhitespace(tag.charAt(nameEnd))
          && tag.charAt(nameEnd) != '/') {
        nameEnd++;
      }
      String name = tag.substring(0, nameEnd);
      if (closing) {
        while (!open.isEmpty()) {
          OpenParagraph candidate = open.pop();
          if (candidate.name().equals(name)) {
            if (candidate.kind() != null) {
              candidates.add(
                  new ParagraphRange(
                      candidate.span(), candidate.kind(), candidate.start(), visible.length()));
            }
            break;
          }
        }
      } else {
        String kind = null;
        if ("li".equals(name) && tag.trim().equals("li")) {
          kind = "bullet";
        } else if ("font".equals(name)) {
          StringBuilder encoded = new StringBuilder(name);
          Matcher attributes = ATTRIBUTE.matcher(tag.substring(nameEnd));
          while (attributes.find()) {
            encoded
                .append(';')
                .append(attributes.group(1))
                .append('=')
                .append(decodeAttribute(attributes.group(2)));
          }
          if (subtag(encoded.toString(), "height") != null) {
            kind = "height";
          }
        }
        OpenParagraph candidate = new OpenParagraph(name, span++, kind, visible.length());
        if (tag.endsWith("/")) {
          if (kind != null) {
            candidates.add(
                new ParagraphRange(candidate.span(), kind, visible.length(), visible.length()));
          }
        } else {
          open.push(candidate);
        }
      }
      index = end;
    }
    while (!open.isEmpty()) {
      OpenParagraph candidate = open.pop();
      if (candidate.kind() != null) {
        candidates.add(
            new ParagraphRange(
                candidate.span(), candidate.kind(), candidate.start(), visible.length()));
      }
    }
    candidates.sort(Comparator.comparingInt(ParagraphRange::span));
    List<Map<String, Object>> result = new ArrayList<>();
    for (ParagraphRange candidate : candidates) {
      int start = candidate.start();
      int end = candidate.end();
      if (start != 0 && start != visible.length() && visible.charAt(start - 1) != '\n') {
        for (start--; start > 0; start--) {
          if (visible.charAt(start - 1) == '\n') {
            break;
          }
        }
      }
      if (end != 0 && end != visible.length() && visible.charAt(end - 1) != '\n') {
        for (end++; end < visible.length(); end++) {
          if (visible.charAt(end - 1) == '\n') {
            break;
          }
        }
      }
      if (start != candidate.start() || end != candidate.end()) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("span", candidate.span());
        projection.put("kind", candidate.kind());
        projection.put("sourceStart", candidate.start());
        projection.put("sourceEnd", candidate.end());
        projection.put("start", start);
        projection.put("end", end);
        result.add(projection);
      }
    }
    return result;
  }

  static void validate(LocalizationMessage message, String source, String quantity) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Object singular = metadata.get("androidRuntimeAnnotations");
    Object plural = metadata.get("androidPluralRuntimeAnnotations");
    if (quantity == null && plural != null || quantity != null && singular != null) {
      throw invalid();
    }
    if (quantity == null) {
      validateAnnotations(singular, spans(source));
      return;
    }
    if (!(plural instanceof Map<?, ?> variants)) {
      if (plural != null || !spans(source).isEmpty()) {
        throw invalid();
      }
      return;
    }
    if (variants.isEmpty() || message.variants() == null) {
      throw invalid();
    }
    for (Map.Entry<?, ?> entry : variants.entrySet()) {
      if (!(entry.getKey() instanceof String category)
          || !message.variants().containsKey(category)) {
        throw invalid();
      }
      String canonical = message.variants().get(category);
      String markup = canonical.replace("'<'", "<").replace("''", "'");
      validateAnnotations(entry.getValue(), spans(markup));
    }
    validateAnnotations(variants.get(quantity), spans(source));
  }

  static void validateStyles(LocalizationMessage message, String source, String quantity) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Object singular = metadata.get("androidRuntimeStyles");
    Object plural = metadata.get("androidPluralRuntimeStyles");
    if (quantity == null && plural != null || quantity != null && singular != null) {
      throw invalidStyle();
    }
    if (quantity == null) {
      validateStyles(singular, styles(source));
      return;
    }
    if (!(plural instanceof Map<?, ?> variants)) {
      if (plural != null || !styles(source).isEmpty()) {
        throw invalidStyle();
      }
      return;
    }
    if (variants.isEmpty() || message.variants() == null) {
      throw invalidStyle();
    }
    for (Map.Entry<?, ?> entry : variants.entrySet()) {
      if (!(entry.getKey() instanceof String category)
          || !message.variants().containsKey(category)) {
        throw invalidStyle();
      }
      String canonical = message.variants().get(category);
      String markup = canonical.replace("'<'", "<").replace("''", "'");
      validateStyles(entry.getValue(), styles(markup));
    }
    validateStyles(variants.get(quantity), styles(source));
  }

  static void validateParagraphs(LocalizationMessage message, String source, String quantity) {
    Map<String, Object> metadata = message.metadata() == null ? Map.of() : message.metadata();
    Object singular = metadata.get("androidRuntimeParagraphSpans");
    Object plural = metadata.get("androidPluralRuntimeParagraphSpans");
    if (quantity == null && plural != null || quantity != null && singular != null) {
      throw invalidParagraph();
    }
    if (quantity == null) {
      validateParagraphs(singular, paragraphs(source));
      return;
    }
    if (!(plural instanceof Map<?, ?> variants)) {
      if (plural != null || !paragraphs(source).isEmpty()) {
        throw invalidParagraph();
      }
      return;
    }
    if (variants.isEmpty() || message.variants() == null) {
      throw invalidParagraph();
    }
    for (Map.Entry<?, ?> entry : variants.entrySet()) {
      if (!(entry.getKey() instanceof String category)
          || !message.variants().containsKey(category)) {
        throw invalidParagraph();
      }
      String canonical = message.variants().get(category);
      String markup = canonical.replace("'<'", "<").replace("''", "'");
      validateParagraphs(entry.getValue(), paragraphs(markup));
    }
    validateParagraphs(variants.get(quantity), paragraphs(source));
  }

  private static void validateAnnotations(Object actual, List<Map<String, Object>> expected) {
    if (expected.isEmpty() && actual == null) {
      return;
    }
    if (!(actual instanceof List<?> values) || values.isEmpty() || !expected.equals(values)) {
      throw invalid();
    }
  }

  private static void validateStyles(Object actual, List<Map<String, Object>> expected) {
    if (expected.isEmpty() && actual == null) {
      return;
    }
    if (!(actual instanceof List<?> values) || values.isEmpty() || !expected.equals(values)) {
      throw invalidStyle();
    }
  }

  private static void validateParagraphs(Object actual, List<Map<String, Object>> expected) {
    if (expected.isEmpty() && actual == null) {
      return;
    }
    if (!(actual instanceof List<?> values) || values.isEmpty() || !expected.equals(values)) {
      throw invalidParagraph();
    }
  }

  private static List<Map<String, Object>> styleEffects(
      String name, String encoded, List<Map<String, String>> attributes, boolean runtime) {
    List<Map<String, Object>> effects = new ArrayList<>();
    if ("a".equals(name)) {
      String value = runtime ? subtag(encoded, "href") : attribute(attributes, "href");
      if (value != null) {
        effects.add(style("link", "href", value));
      }
      return effects;
    }
    String[][] supported = {
      {"height", "height"},
      {"size", "size"},
      {"foreground", "fgcolor"},
      {"foreground", "color"},
      {"background", "bgcolor"},
      {"face", "face"}
    };
    for (String[] supportedAttribute : supported) {
      String value =
          runtime
              ? subtag(encoded, supportedAttribute[1])
              : attribute(attributes, supportedAttribute[1]);
      if (value == null) {
        continue;
      }
      if (runtime
          && ("height".equals(supportedAttribute[1]) || "size".equals(supportedAttribute[1]))) {
        try {
          Integer.parseInt(value);
        } catch (NumberFormatException exception) {
          throw invalidStyle();
        }
      }
      effects.add(style(supportedAttribute[0], supportedAttribute[1], value));
    }
    return effects;
  }

  private static String attribute(List<Map<String, String>> attributes, String key) {
    for (Map<String, String> attribute : attributes) {
      if (key.equals(attribute.get("key"))) {
        return attribute.get("value");
      }
    }
    return null;
  }

  private static String subtag(String encoded, String key) {
    String marker = ";" + key + "=";
    int start = encoded.indexOf(marker);
    if (start < 0) {
      return null;
    }
    start += marker.length();
    int end = encoded.indexOf(';', start);
    return end < 0 ? encoded.substring(start) : encoded.substring(start, end);
  }

  private static Map<String, Object> style(String kind, String attribute, String value) {
    Map<String, Object> effect = new LinkedHashMap<>();
    effect.put("kind", kind);
    effect.put("attribute", attribute);
    effect.put("value", value);
    if ("foreground".equals(kind) || "background".equals(kind)) {
      effect.put("color", color(value, "foreground".equals(kind)));
    }
    return effect;
  }

  private static Map<String, Object> color(String value, boolean foreground) {
    Map<String, Object> color = new LinkedHashMap<>();
    if (value.startsWith("@")) {
      String reference = value.substring(1);
      int packageSeparator = reference.indexOf(':');
      if (packageSeparator >= 0 && !"android".equals(reference.substring(0, packageSeparator))) {
        color.put("mode", "fallback");
        color.put("argb", "#ff000000");
        return color;
      }
      color.put("mode", "system");
      color.put("reference", value);
      color.put("fallbackArgb", "#ff000000");
      color.put("stateful", foreground);
      return color;
    }
    Integer resolved = null;
    if (value.startsWith("#")) {
      try {
        long parsed = Long.parseLong(value.substring(1), 16);
        if (value.length() == 7) {
          parsed |= 0xff000000L;
        } else if (value.length() != 9) {
          throw new IllegalArgumentException("Invalid Android color length");
        }
        resolved = (int) parsed;
      } catch (IllegalArgumentException ignored) {
        // StringBlock intentionally replaces invalid colors with opaque black.
      }
    } else {
      resolved = COLOR_NAMES.get(value.toLowerCase(Locale.ROOT));
    }
    color.put("mode", resolved == null ? "fallback" : "literal");
    color.put(
        "argb", String.format(Locale.ROOT, "#%08x", resolved == null ? 0xff000000 : resolved));
    return color;
  }

  private static List<Map<String, String>> decodeAnnotations(String encoded) {
    List<Map<String, String>> result = new ArrayList<>();
    int position = encoded.indexOf(';');
    while (position >= 0 && position < encoded.length()) {
      int equals = encoded.indexOf('=', position);
      if (equals < 0) {
        break;
      }
      int next = encoded.indexOf(';', equals);
      if (next < 0) {
        next = encoded.length();
      }
      result.add(
          annotation(encoded.substring(position + 1, equals), encoded.substring(equals + 1, next)));
      position = next;
    }
    return result;
  }

  private static Map<String, String> annotation(String key, String value) {
    Map<String, String> annotation = new LinkedHashMap<>();
    annotation.put("key", key);
    annotation.put("value", value);
    return annotation;
  }

  private static String decodeAttribute(String source) {
    return source
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
        .replace("&amp;", "&");
  }

  private static int tagEnd(String source, int offset) {
    boolean quoted = false;
    for (int index = offset; index < source.length(); index++) {
      char character = source.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (character == '>' && !quoted) {
        return index;
      }
    }
    return -1;
  }

  private static LocalizationParseException invalid() {
    return new LocalizationParseException(
        "INVALID_ANDROID_ANNOTATION",
        "Invalid or inconsistent Android runtime annotation metadata");
  }

  private static LocalizationParseException invalidStyle() {
    return new LocalizationParseException(
        "INVALID_ANDROID_STYLE", "Invalid or inconsistent Android runtime style metadata");
  }

  private static LocalizationParseException invalidParagraph() {
    return new LocalizationParseException(
        "INVALID_ANDROID_PARAGRAPH", "Invalid or inconsistent Android paragraph-span metadata");
  }

  private record OpenParagraph(String name, int span, String kind, int start) {}

  private record ParagraphRange(int span, String kind, int start, int end) {}
}
