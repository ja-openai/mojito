package com.box.l10n.mojito.fileformat;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

final class SecureXmlParser {

  private static final Pattern APPLE_PLIST_DOCTYPE =
      Pattern.compile(
          "(?s)^(\\s*(?:<\\?xml\\b[^>]*>\\s*)?(?:(?:<\\?.*?\\?>|<!--.*?-->)\\s*)*)"
              + "<!DOCTYPE\\s+plist\\s+PUBLIC\\s+([\\\"'])-//Apple//DTD PLIST 1\\.0//EN\\2\\s+"
              + "([\\\"'])https?://www\\.apple\\.com/DTDs/PropertyList-1\\.0\\.dtd\\3\\s*>");

  private SecureXmlParser() {}

  static Document parseApplePlist(String source) {
    String normalized = APPLE_PLIST_DOCTYPE.matcher(source).replaceFirst("$1");
    Document document = parse(normalized, false);
    validateAppleCharacterReferences(normalized);
    return document;
  }

  private static void validateAppleCharacterReferences(String source) {
    Deque<String> elements = new ArrayDeque<>();
    for (int index = 0; index < source.length(); index++) {
      if (source.charAt(index) == '<') {
        if (source.startsWith("<!--", index)) {
          index = source.indexOf("-->", index + 4) + 2;
        } else if (source.startsWith("<![CDATA[", index)) {
          index = source.indexOf("]]>", index + 9) + 2;
        } else if (source.startsWith("<?", index)) {
          index = source.indexOf("?>", index + 2) + 1;
        } else {
          int end = appleTagEnd(source, index + 1);
          String tag = source.substring(index + 1, end).strip();
          if (tag.startsWith("/")) {
            elements.pop();
          } else if (!tag.endsWith("/")) {
            elements.push(tag.split("\\s+", 2)[0]);
          }
          index = end;
        }
        continue;
      }
      if (source.charAt(index) != '&') {
        continue;
      }
      String owner = elements.peek();
      if ("integer".equals(owner) || "date".equals(owner) || "data".equals(owner)) {
        throw new LocalizationParseException(
            "INVALID_XML", "Apple plist " + owner + " values cannot contain XML references");
      }
      if (!source.startsWith("&#", index)) {
        continue;
      }
      int digits = index + 2;
      if (digits < source.length() && source.charAt(digits) == 'x') {
        digits++;
      }
      int semicolon = source.indexOf(';', digits);
      if (semicolon - digits > 8) {
        throw new LocalizationParseException(
            "INVALID_XML", "Apple plist character references cannot exceed eight digits");
      }
      index = semicolon;
    }
  }

  static int appleTagEnd(String source, int start) {
    char quote = 0;
    for (int index = start; index < source.length(); index++) {
      char current = source.charAt(index);
      if (quote != 0) {
        if (current == quote) {
          quote = 0;
        }
      } else if (current == '\'' || current == '"') {
        quote = current;
      } else if (current == '>') {
        return index;
      }
    }
    throw new LocalizationParseException("INVALID_XML", "Unclosed Apple XML element");
  }

  static Document parse(String source) {
    return parse(source, true);
  }

  private static Document parse(String source, boolean android) {
    try {
      validateXmlCharacters(source);
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new ErrorHandler() {
            @Override
            public void warning(SAXParseException error) throws SAXParseException {
              throw error;
            }

            @Override
            public void error(SAXParseException error) throws SAXParseException {
              throw error;
            }

            @Override
            public void fatalError(SAXParseException error) throws SAXParseException {
              throw error;
            }
          });
      Document document = builder.parse(new InputSource(new StringReader(source)));
      validateXmlCharacters(document, android);
      return document;
    } catch (LocalizationParseException exception) {
      throw exception;
    } catch (SAXParseException exception) {
      if (exception.getMessage() != null && exception.getMessage().contains("DOCTYPE")) {
        throw new LocalizationParseException(
            "UNSAFE_XML", "XML document types are not allowed", exception);
      }
      throw new LocalizationParseException("INVALID_XML", "Invalid XML resource", exception);
    } catch (Exception exception) {
      throw new LocalizationParseException("INVALID_XML", "Invalid XML resource", exception);
    }
  }

  private static void validateXmlCharacters(String value) {
    if (value != null
        && value
            .codePoints()
            .anyMatch(
                character ->
                    !(character == 0x09
                        || character == 0x0A
                        || character == 0x0D
                        || character >= 0x20 && character <= 0xD7FF
                        || character >= 0xE000 && character <= 0xFFFD
                        || character >= 0x10000 && character <= 0x10FFFF))) {
      throw new LocalizationParseException(
          "INVALID_XML", "XML content contains a character forbidden by XML 1.0");
    }
  }

  private static void validateXmlCharacters(Node node, boolean android) {
    validateXmlCharacters(node.getNodeValue());
    if (node.getNodeType() == Node.ELEMENT_NODE || node.getNodeType() == Node.ATTRIBUTE_NODE) {
      String name = node.getNodeName();
      int colon = name.indexOf(':');
      if (colon == 0 || colon == name.length() - 1 || colon != name.lastIndexOf(':')) {
        throw new LocalizationParseException("INVALID_XML", "Invalid XML namespace-qualified name");
      }
    } else if (android
        && node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
        && node.getNodeName().indexOf(':') >= 0) {
      throw new LocalizationParseException(
          "INVALID_XML", "Android processing-instruction targets cannot contain a colon");
    }
    NamedNodeMap attributes = node.getAttributes();
    if (attributes != null) {
      for (int index = 0; index < attributes.getLength(); index++) {
        validateXmlCharacters(attributes.item(index), android);
      }
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      validateXmlCharacters(child, android);
    }
  }
}
