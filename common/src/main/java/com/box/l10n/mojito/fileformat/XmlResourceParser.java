package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Native XML resource rules owned by Mojito's customized RESX filter configuration. */
final class XmlResourceParser {

  private static final Pattern XTB_DOCTYPE =
      Pattern.compile(
          "(?s)^(\\s*(?:<\\?xml\\b.*?\\?>\\s*)?)<!DOCTYPE\\s+translationbundle\\s*>(\\s*<translationbundle\\b)");

  private XmlResourceParser() {}

  static LocalizationCatalog parse(LocalizationFileFormat format, String source) {
    String safe =
        format == LocalizationFileFormat.XTB
            ? XTB_DOCTYPE.matcher(source).replaceFirst("$1$2")
            : source;
    Element root = SecureXmlParser.parse(safe).getDocumentElement();
    String rootName = format == LocalizationFileFormat.XTB ? "translationbundle" : "root";
    if (!rootName.equals(root.getNodeName())) {
      throw new LocalizationParseException(
          "INVALID_XML_ROOT", "XML resources require their format-owned root element");
    }

    LocalizationCatalog catalog = new LocalizationCatalog(format);
    if (format == LocalizationFileFormat.XTB) {
      if (root.hasAttribute("lang") && !root.getAttribute("lang").isBlank()) {
        catalog.setLocale(root.getAttribute("lang"));
      }
      parseXtb(root, catalog);
      return catalog;
    }
    NodeList entries = root.getElementsByTagName("data");
    for (int index = 0; index < entries.getLength(); index++) {
      Element entry = (Element) entries.item(index);
      String name = entry.getAttribute("name");
      if (entry.hasAttribute("type")
          || entry.hasAttribute("mimetype")
          || name.startsWith(">")
          || name.endsWith(".Name")) {
        continue;
      }
      Element value = child(entry, "value");
      if (value == null || !hasText(value)) {
        continue;
      }
      Element comment = name.startsWith("$") ? null : child(entry, "comment");
      catalog.add(
          name,
          LocalizationMessage.of(
              value.getTextContent(),
              comment == null ? null : comment.getTextContent(),
              null,
              null,
              null));
    }
    return catalog;
  }

  static String write(LocalizationFileFormat format, LocalizationCatalog catalog) {
    if (!format.id().equals(catalog.sourceFormat())) {
      throw new LocalizationParseException(
          "INVALID_SOURCE_FORMAT", "Catalog does not contain RESX resources");
    }
    boolean xtb = format == LocalizationFileFormat.XTB;
    StringBuilder output = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    if (xtb) {
      output.append("<translationbundle");
      if (catalog.locale() != null) {
        output.append(" lang=\"").append(escapeAttribute(catalog.locale())).append('"');
      }
      output.append(">\n");
    } else {
      output.append("<root>\n");
    }
    for (Map.Entry<String, LocalizationMessage> entry :
        catalog.messages().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      if (xtb) {
        output.append("  <translation key=\"").append(escapeAttribute(entry.getKey())).append('"');
        if (entry.getValue().description() != null) {
          output
              .append(" desc=\"")
              .append(escapeAttribute(entry.getValue().description()))
              .append('"');
        }
        output
            .append('>')
            .append(renderXtb(entry.getValue(), entry.getValue().defaultMessage(), null))
            .append("</translation>\n");
      } else {
        output
            .append("  <data name=\"")
            .append(escapeAttribute(entry.getKey()))
            .append("\" xml:space=\"preserve\">\n    <value>")
            .append(escapeText(entry.getValue().defaultMessage()))
            .append("</value>");
        if (entry.getValue().description() != null) {
          output
              .append("\n    <comment>")
              .append(escapeText(entry.getValue().description()))
              .append("</comment>");
        }
        output.append("\n  </data>\n");
      }
    }
    return output.append(xtb ? "</translationbundle>\n" : "</root>\n").toString();
  }

  static String renderXtb(LocalizationMessage message, String translation, String source) {
    String result = escapeText(translation);
    for (LocalizationPlaceholder placeholder :
        message.placeholders() == null
            ? List.<LocalizationPlaceholder>of()
            : message.placeholders()) {
      String marker = "{" + placeholder.name() + "}";
      if (!result.contains(marker)) {
        throw new LocalizationParseException(
            "INVALID_SKELETON_MARKUP", "Missing XTB placeholder: " + placeholder.name());
      }
      String nativeCode = placeholder.source();
      if (source != null) {
        Matcher matcher =
            Pattern.compile(
                    "<ph\\b[^>]*\\bname\\s*=\\s*([\"'])"
                        + Pattern.quote(placeholder.name())
                        + "\\1[^>]*/>")
                .matcher(source);
        if (!matcher.find()) {
          throw new LocalizationParseException(
              "INVALID_SKELETON_MARKUP", "Missing source-owned XTB placeholder");
        }
        nativeCode = matcher.group();
      } else if (placeholder.example() != null) {
        nativeCode =
            nativeCode.substring(0, nativeCode.length() - 2)
                + " example=\""
                + escapeAttribute(placeholder.example())
                + "\"/>";
      }
      result = result.replace(marker, nativeCode);
    }
    return result;
  }

  private static void parseXtb(Element root, LocalizationCatalog catalog) {
    NodeList entries = root.getElementsByTagName("translation");
    for (int index = 0; index < entries.getLength(); index++) {
      Element entry = (Element) entries.item(index);
      if (!hasText(entry)) {
        continue;
      }
      StringBuilder message = new StringBuilder();
      List<LocalizationPlaceholder> placeholders = new ArrayList<>();
      for (Node child = entry.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child.getNodeType() == Node.TEXT_NODE
            || child.getNodeType() == Node.CDATA_SECTION_NODE) {
          message.append(child.getNodeValue());
        } else if (child instanceof Element code
            && "ph".equals(code.getNodeName())
            && code.hasAttribute("name")) {
          String name = code.getAttribute("name");
          if (name.isBlank()) {
            throw new LocalizationParseException(
                "INVALID_XTB_PLACEHOLDER", "XTB placeholder names must not be empty");
          }
          message.append('{').append(name).append('}');
          LocalizationPlaceholder placeholder =
              new LocalizationPlaceholder(
                  name,
                  "<ph name=\"" + escapeAttribute(name) + "\"/>",
                  "value",
                  null,
                  code.hasAttribute("example") ? code.getAttribute("example") : null);
          if (!placeholders.contains(placeholder)) {
            placeholders.add(placeholder);
          }
        } else if (child instanceof Element) {
          throw new LocalizationParseException(
              "INVALID_XTB_PLACEHOLDER", "Unsupported XTB inline element");
        }
      }
      catalog.add(
          entry.getAttribute("key"),
          LocalizationMessage.of(
              message.toString(),
              entry.hasAttribute("desc") ? entry.getAttribute("desc") : null,
              null,
              placeholders,
              null));
    }
  }

  static String escapeText(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeAttribute(String value) {
    return escapeText(value).replace("\"", "&quot;");
  }

  private static Element child(Element parent, String name) {
    for (Node current = parent.getFirstChild();
        current != null;
        current = current.getNextSibling()) {
      if (current instanceof Element element && name.equals(element.getNodeName())) {
        return element;
      }
    }
    return null;
  }

  private static boolean hasText(Element value) {
    for (Node current = value.getFirstChild();
        current != null;
        current = current.getNextSibling()) {
      if (current.getNodeType() == Node.TEXT_NODE
          || current.getNodeType() == Node.CDATA_SECTION_NODE) {
        return true;
      }
    }
    return false;
  }
}
