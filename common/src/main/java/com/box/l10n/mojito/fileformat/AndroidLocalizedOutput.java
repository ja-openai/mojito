package com.box.l10n.mojito.fileformat;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Safe, explicit Android localized-resource cleanup independent of the Okapi postprocessor. */
final class AndroidLocalizedOutput {

  private AndroidLocalizedOutput() {}

  static byte[] process(
      byte[] bytes,
      LocalizationFilterOptions options,
      boolean removeUntranslated,
      String untranslatedMarker) {
    SourceSkeletonEncoding encoding =
        SourceSkeletonEncoding.detect(
            bytes, LocalizationFileConverters.xmlCharset(LocalizationFileFormat.ANDROID, bytes));
    String source = encoding.decode(bytes, encoding.bom().length, bytes.length);
    Document document = SecureXmlParser.parse(source);
    Element root = document.getDocumentElement();
    clean(root, options, removeUntranslated, untranslatedMarker);
    if (options.enabled("postEmptyResourcesToEmptyFile") && !containsResource(root)) {
      return new byte[0];
    }
    StringBuilder result = new StringBuilder();
    if (source.stripLeading().startsWith("<?xml")) {
      result.append(source, source.indexOf("<?xml"), source.indexOf("?>") + 2).append('\n');
    }
    for (Node node = document.getFirstChild();
        node != null && node != root;
        node = node.getNextSibling()) {
      if (node.getNodeType() == Node.COMMENT_NODE) {
        result.append("<!--").append(node.getNodeValue()).append("-->");
      } else if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
        result.append("<?").append(node.getNodeName());
        if (node.getNodeValue() != null && !node.getNodeValue().isEmpty()) {
          result.append(' ').append(node.getNodeValue());
        }
        result.append("?>");
      }
    }
    append(root, result, 0, options.indentation());
    result.append('\n');
    return encoding.encode(result.toString());
  }

  private static void clean(
      Element element,
      LocalizationFilterOptions options,
      boolean removeUntranslated,
      String untranslatedMarker) {
    if (options.enabled("removeDescription")) {
      element.removeAttribute("description");
    }
    List<Element> children = new ArrayList<>();
    NodeList nodes = element.getChildNodes();
    for (int index = 0; index < nodes.getLength(); index++) {
      if (nodes.item(index) instanceof Element child) {
        children.add(child);
      }
    }
    for (Element child : children) {
      if (options.enabled("postRemoveTranslatableFalse") && isProtected(child)) {
        element.removeChild(child);
        continue;
      }
      boolean suppressed = child.getAttribute("description").contains("DO NOT TRANSLATE");
      clean(child, options, removeUntranslated, untranslatedMarker);
      if (removeUntranslated
          && !isProtected(child)
          && !suppressed
          && untranslated(child.getTextContent(), untranslatedMarker)) {
        element.removeChild(child);
        continue;
      }
      if (removeUntranslated && "plurals".equals(child.getLocalName()) && !hasOther(child)) {
        element.removeChild(child);
      }
    }
  }

  private static boolean hasOther(Element plurals) {
    NodeList children = plurals.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element item
          && "item".equals(item.getLocalName())
          && "other".equals(item.getAttribute("quantity").trim())) {
        return true;
      }
    }
    return false;
  }

  private static boolean untranslated(String value, String untranslatedMarker) {
    return untranslatedMarker.equals(value)
        || value.length() == untranslatedMarker.length() + 2
            && value.charAt(0) == '"'
            && value.charAt(value.length() - 1) == '"'
            && value.startsWith(untranslatedMarker, 1);
  }

  private static boolean isProtected(Element element) {
    String value = element.getAttribute("translatable").trim();
    return "false".equals(value) || "False".equals(value) || "FALSE".equals(value);
  }

  private static boolean containsResource(Element root) {
    NodeList children = root.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element child
          && List.of("string", "plurals", "string-array", "array", "item", "bag")
              .contains(child.getLocalName())) {
        return true;
      }
    }
    return false;
  }

  private static void append(Element element, StringBuilder output, int level, int indent) {
    output.append(" ".repeat(level * indent)).append('<').append(element.getTagName());
    NamedNodeMap attributes = element.getAttributes();
    for (int index = 0; index < attributes.getLength(); index++) {
      Node attribute = attributes.item(index);
      output
          .append(' ')
          .append(attribute.getNodeName())
          .append("=\"")
          .append(escape(attribute.getNodeValue(), true))
          .append('"');
    }
    NodeList children = element.getChildNodes();
    boolean nested = false;
    boolean text = false;
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      nested |= child instanceof Element || child.getNodeType() == Node.COMMENT_NODE;
      text |= child.getNodeType() == Node.TEXT_NODE && !child.getNodeValue().isBlank();
    }
    if ("resources".equals(element.getLocalName()) && !nested && !text) {
      output.append("/>");
      return;
    }
    output.append('>');
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element value) {
        if (nested && !text) {
          output.append('\n');
        }
        append(value, output, nested && !text ? level + 1 : 0, indent);
      } else if (child.getNodeType() == Node.COMMENT_NODE) {
        if (nested && !text) {
          output.append('\n').append(" ".repeat((level + 1) * indent));
        }
        output.append("<!--").append(child.getNodeValue()).append("-->");
      } else if (child.getNodeType() == Node.TEXT_NODE
          || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        if (text || !child.getNodeValue().isBlank()) {
          output.append(escape(child.getNodeValue(), false));
        }
      }
    }
    if (nested && !text) {
      output.append('\n').append(" ".repeat(level * indent));
    }
    output.append("</").append(element.getTagName()).append('>');
  }

  private static String escape(String value, boolean attribute) {
    String result = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    if (attribute) {
      result =
          result
              .replace("\"", "&quot;")
              .replace("\t", "&#9;")
              .replace("\r", "&#13;")
              .replace("\n", "&#10;");
    }
    return result;
  }
}
