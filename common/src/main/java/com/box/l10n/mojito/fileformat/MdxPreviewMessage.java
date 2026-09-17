package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit, non-executable sample state for a separately extracted MF2 catalog message. */
public record MdxPreviewMessage(String resource, String name, Map<String, Object> args) {
  public static final String COMPONENT = "PreviewMessage";
  private static final Pattern ATTRIBUTE =
      Pattern.compile("\\s+(resource|name|args)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public static MdxPreviewMessage parse(String source) {
    if (source == null
        || source.length() > 16_384
        || !source.startsWith("<PreviewMessage ")
        || !source.endsWith("/>")) throw invalid();
    String attributes = source.substring(15, source.length() - 2);
    Matcher matcher = ATTRIBUTE.matcher(attributes);
    Map<String, String> values = new LinkedHashMap<>();
    int end = 0;
    while (matcher.find()) {
      if (matcher.start() != end) throw invalid();
      String value = matcher.group(2) == null ? matcher.group(3) : matcher.group(2);
      // JSX entities have a different decoding stage; keep the adapter's literal contract exact.
      if (value.contains("&") || values.put(matcher.group(1), value) != null) throw invalid();
      end = matcher.end();
    }
    if (!attributes.substring(end).isBlank()
        || !values.keySet().equals(Set.of("resource", "name", "args"))
        || values.get("resource").length() > 512
        || values.get("name").isBlank()
        || values.get("name").length() > 256) throw invalid();
    try {
      JsonNode node = JSON.readTree(values.get("args"));
      if (!node.isObject() || node.size() > 32) throw invalid();
      Map<String, Object> args = new LinkedHashMap<>();
      node.fields()
          .forEachRemaining(
              entry -> {
                JsonNode value = entry.getValue();
                if (entry.getKey().isBlank()
                    || entry.getKey().length() > 128
                    || Set.of("__proto__", "prototype", "constructor").contains(entry.getKey())
                    || !value.isValueNode()
                    || value.isNull()
                    || (value.isTextual() && value.textValue().length() > 2048)
                    || (value.isNumber() && !Double.isFinite(value.doubleValue()))) throw invalid();
                args.put(entry.getKey(), JSON.convertValue(value, Object.class));
              });
      return new MdxPreviewMessage(
          values.get("resource"), values.get("name"), Collections.unmodifiableMap(args));
    } catch (java.io.IOException exception) {
      throw invalid();
    }
  }

  /** Flat catalogs only; names use ordinary JSON key identities. */
  public static Map<String, String> parseCatalog(String content) {
    try {
      JsonNode node = JSON.readTree(content);
      if (node == null || !node.isObject() || node.size() > 2_000) throw invalid();
      Map<String, String> messages = new LinkedHashMap<>();
      node.fields()
          .forEachRemaining(
              entry -> {
                if (entry.getKey().isBlank()
                    || entry.getKey().length() > 256
                    || !entry.getValue().isTextual()
                    || entry.getValue().textValue().isBlank()) throw invalid();
                messages.put(entry.getKey(), entry.getValue().textValue());
              });
      return Collections.unmodifiableMap(messages);
    } catch (java.io.IOException exception) {
      throw invalid();
    }
  }

  private static LocalizationParseException invalid() {
    return new LocalizationParseException(
        "INVALID_PREVIEW_MESSAGE",
        "PreviewMessage requires literal resource, name and bounded JSON primitive args; its catalog must be flat JSON strings");
  }
}
