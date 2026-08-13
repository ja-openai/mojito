package com.box.l10n.mojito.fileformat;

import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Native XML resource rules owned by Mojito's customized RESX filter configuration. */
final class XmlResourceParser {

  private XmlResourceParser() {}

  static LocalizationCatalog parse(LocalizationFileFormat format, String source) {
    Element root = SecureXmlParser.parse(source).getDocumentElement();
    if (!"root".equals(root.getNodeName())) {
      throw new LocalizationParseException(
          "INVALID_XML_ROOT", "RESX resources require a root element");
    }

    LocalizationCatalog catalog = new LocalizationCatalog(format);
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
    StringBuilder output =
        new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n");
    for (Map.Entry<String, LocalizationMessage> entry :
        catalog.messages().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
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
    return output.append("</root>\n").toString();
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
