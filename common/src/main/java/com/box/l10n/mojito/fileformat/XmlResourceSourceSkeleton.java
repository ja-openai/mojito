package com.box.l10n.mojito.fileformat;

import com.box.l10n.mojito.fileformat.LocalizationSourceSkeleton.LocalizationSourceSlot;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringEscapeUtils;

/** Byte-preserving value ownership for customized Mojito XML localization resources. */
final class XmlResourceSourceSkeleton {

  private static final Pattern ATTRIBUTE =
      Pattern.compile("([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*([\"'])(.*?)\\2", Pattern.DOTALL);

  private XmlResourceSourceSkeleton() {}

  static LocalizationSourceSkeleton extract(LocalizationFileFormat format, byte[] bytes) {
    Charset declared = LocalizationFileConverters.xmlCharset(format, bytes);
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.detect(bytes, declared);
    String source = LocalizationFileConverters.decode(bytes, declared);
    LocalizationCatalog catalog = LocalizationFileConverters.parse(format, bytes);
    List<LocalizationSourceSlot> slots = new ArrayList<>();
    Set<String> assigned = new HashSet<>();
    Deque<OpenElement> elements = new ArrayDeque<>();
    for (int position = 0; position < source.length(); ) {
      if (source.charAt(position) != '<') {
        position++;
        continue;
      }
      if (source.startsWith("<!--", position)) {
        position = skip(source, position, "-->");
        continue;
      }
      if (source.startsWith("<![CDATA[", position)) {
        position = skip(source, position, "]]>");
        continue;
      }
      if (source.startsWith("<?", position)) {
        position = skip(source, position, "?>");
        continue;
      }
      int end = tagEnd(source, position);
      String token = source.substring(position + 1, end).trim();
      if (token.startsWith("!")
          && !(format == LocalizationFileFormat.XTB
              && token.matches("!DOCTYPE\\s+translationbundle\\s*"))) {
        throw invalid("UNSUPPORTED_SKELETON_MARKUP", "Unsupported XML resource declaration");
      }
      if (token.startsWith("!")) {
        position = end + 1;
        continue;
      }
      if (token.startsWith("/")) {
        if (elements.isEmpty()) {
          throw invalid("INVALID_SKELETON", "Unbalanced XML resource elements");
        }
        OpenElement current = elements.pop();
        OpenElement parent = elements.peek();
        if (format == LocalizationFileFormat.RESX
                && "value".equals(current.name())
                && parent != null
                && "data".equals(parent.name())
            || format == LocalizationFileFormat.XTB && "translation".equals(current.name())) {
          String identity =
              format == LocalizationFileFormat.XTB ? current.identity() : parent.identity();
          if (catalog.messages().containsKey(identity)) {
            if (!assigned.add(identity)) {
              throw invalid("INVALID_SKELETON", "Duplicate XML resource source slot");
            }
            slots.add(
                new LocalizationSourceSlot(
                    identity,
                    null,
                    encoding.offset(source, current.bodyStart()),
                    encoding.offset(source, position)));
          }
        }
      } else if (!token.endsWith("/")) {
        String name = token.split("\\s+", 2)[0];
        String identity = null;
        if (format == LocalizationFileFormat.RESX && "data".equals(name)
            || format == LocalizationFileFormat.XTB && "translation".equals(name)) {
          Matcher matcher = ATTRIBUTE.matcher(token.substring(name.length()));
          while (matcher.find()) {
            if ((format == LocalizationFileFormat.RESX ? "name" : "key").equals(matcher.group(1))) {
              identity = StringEscapeUtils.unescapeXml(matcher.group(3));
              break;
            }
          }
        }
        elements.push(new OpenElement(name, identity, end + 1));
      }
      position = end + 1;
    }
    if (!elements.isEmpty() || !assigned.equals(catalog.messages().keySet())) {
      throw invalid(
          "UNSUPPORTED_SKELETON_SOURCE", "XML resources have unowned translatable values");
    }
    return new LocalizationSourceSkeleton(1, format.id(), encoding.name(), source, slots);
  }

  static byte[] render(LocalizationSourceSkeleton skeleton, Map<String, String> translations) {
    if (skeleton.schemaVersion() != 1) {
      throw invalid("INVALID_SKELETON", "Unsupported XML resource skeleton version");
    }
    LocalizationFileFormat format = LocalizationFileFormat.fromId(skeleton.sourceFormat());
    SourceSkeletonEncoding encoding = SourceSkeletonEncoding.named(skeleton.encoding());
    byte[] source = encoding.encode(skeleton.source());
    LocalizationCatalog catalog = LocalizationFileConverters.parse(format, source);
    Set<String> identities = new HashSet<>();
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.variant() != null || slot.selector() != null || !identities.add(slot.id())) {
        throw invalid("INVALID_SKELETON", "Invalid XML resource source slot");
      }
    }
    if (!identities.containsAll(translations.keySet())) {
      throw invalid("UNKNOWN_SKELETON_SLOT", "Translation has no original XML resource slot");
    }
    ByteArrayOutputStream localized = new ByteArrayOutputStream(source.length);
    int previous = 0;
    for (LocalizationSourceSlot slot : skeleton.slots()) {
      if (slot.start() < previous || slot.end() < slot.start() || slot.end() > source.length) {
        throw invalid("INVALID_SKELETON", "Invalid XML resource source slot range");
      }
      localized.write(source, previous, slot.start() - previous);
      String translation = translations.get(slot.id());
      if (translation == null) {
        localized.write(source, slot.start(), slot.end() - slot.start());
      } else {
        LocalizationMessage message = catalog.messages().get(slot.id());
        if (message == null) {
          throw invalid("INVALID_SKELETON", "Missing XML resource source descriptor");
        }
        String rendered =
            format == LocalizationFileFormat.XTB
                ? XmlResourceParser.renderXtb(
                    message, translation, encoding.decode(source, slot.start(), slot.end()))
                : XmlResourceParser.escapeText(translation);
        byte[] replacement = rendered.getBytes(encoding.charset());
        localized.write(replacement, 0, replacement.length);
      }
      previous = slot.end();
    }
    localized.write(source, previous, source.length - previous);
    return localized.toByteArray();
  }

  private static int skip(String source, int start, String closing) {
    int end = source.indexOf(closing, start);
    if (end < 0) {
      throw invalid("INVALID_SKELETON", "Unclosed XML resource declaration");
    }
    return end + closing.length();
  }

  private static int tagEnd(String source, int start) {
    char quote = 0;
    for (int position = start + 1; position < source.length(); position++) {
      char current = source.charAt(position);
      if (quote != 0) {
        if (current == quote) {
          quote = 0;
        }
      } else if (current == '\'' || current == '"') {
        quote = current;
      } else if (current == '>') {
        return position;
      }
    }
    throw invalid("INVALID_SKELETON", "Unclosed XML resource element");
  }

  private static LocalizationParseException invalid(String code, String message) {
    return new LocalizationParseException(code, message);
  }

  private record OpenElement(String name, String identity, int bodyStart) {}
}
