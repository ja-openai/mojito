package com.box.l10n.mojito.mf2.inflection;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared JSON field validation helpers for generated MF2 inflection artifacts. */
final class InflectionJsonFields {

  private InflectionJsonFields() {}

  static JsonNode requiredObjectRoot(JsonNode node, String artifactLabel) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Expected object root: " + artifactLabel);
    }
    return node;
  }

  static JsonNode requiredArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("Expected array field: " + field);
    }
    return value;
  }

  static JsonNode optionalArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("Expected array field: " + field);
    }
    return value;
  }

  static JsonNode requiredObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("Expected object field: " + field);
    }
    return value;
  }

  static JsonNode optionalObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("Expected object field: " + field);
    }
    return value;
  }

  static int requiredInt(JsonNode node, String field) {
    return requiredIntValue(node.get(field), field);
  }

  static int requiredIntValue(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("Expected int field: " + field);
    }
    return value.asInt();
  }

  static Integer optionalInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("Expected int field: " + field);
    }
    return value.asInt();
  }

  static int optionalInt(JsonNode node, String field, int defaultValue) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return defaultValue;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("Expected int field: " + field);
    }
    return value.asInt();
  }

  static Long optionalLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("Expected long field: " + field);
    }
    return value.asLong();
  }

  static long requiredLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("Expected long field: " + field);
    }
    return value.asLong();
  }

  static double requiredDouble(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isNumber()) {
      throw new IllegalArgumentException("Expected numeric field: " + field);
    }
    return value.asDouble();
  }

  static boolean requiredBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("Expected boolean field: " + field);
    }
    return value.asBoolean();
  }

  static Boolean optionalBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("Expected boolean field: " + field);
    }
    return value.asBoolean();
  }

  static String requiredText(JsonNode node, String field) {
    return requiredTextValue(node.get(field), field);
  }

  static String requiredTextValue(JsonNode value, String field) {
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Expected nonblank text field: " + field);
    }
    return value.asText();
  }

  static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Expected nonblank text field: " + field);
    }
    return value.asText();
  }

  static String requiredSha256Hex(JsonNode node, String field) {
    return requireSha256Hex(requiredText(node, field), field);
  }

  static String requireSha256Hex(String value, String field) {
    String digest = requireText(value, field);
    if (digest.length() != 64) {
      throw new IllegalArgumentException(
          field + " must be a 64-character lowercase SHA-256 hex digest");
    }
    for (int i = 0; i < digest.length(); i++) {
      char c = digest.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
        throw new IllegalArgumentException(
            field + " must be a 64-character lowercase SHA-256 hex digest");
      }
    }
    return digest;
  }

  static int requiredStringIndex(JsonNode node, String field, List<String> strings) {
    int index = requiredInt(node, field);
    if (index < 0 || index >= strings.size()) {
      throw new IllegalArgumentException(
          "String index out of bounds for field " + field + ": " + index);
    }
    return index;
  }

  static Integer optionalStringIndex(JsonNode node, String field, List<String> strings) {
    Integer index = optionalInt(node, field);
    if (index == null) {
      return null;
    }
    if (index < 0 || index >= strings.size()) {
      throw new IllegalArgumentException(
          "String index out of bounds for field " + field + ": " + index);
    }
    return index;
  }

  static String requiredAllowedText(
      JsonNode node, String field, Set<String> allowedValues, String artifactLabel) {
    String value = requiredText(node, field);
    if (!allowedValues.contains(value)) {
      throw new IllegalArgumentException(
          "Unsupported " + artifactLabel + " " + field + " value: " + value);
    }
    return value;
  }

  static Map<String, Integer> integerMap(
      JsonNode node, String field, Set<String> allowedKeys, String artifactLabel) {
    Map<String, Integer> values = new LinkedHashMap<>();
    node.fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              if (allowedKeys != null && !allowedKeys.contains(key)) {
                throw new IllegalArgumentException(
                    "Unsupported " + artifactLabel + " " + field + " key: " + key);
              }
              JsonNode value = entry.getValue();
              if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) {
                throw new IllegalArgumentException(
                    "Expected non-negative " + artifactLabel + " count: " + field);
              }
              values.put(key, value.asInt());
            });
    return unmodifiableLinkedMap(values);
  }

  static List<String> textArray(
      JsonNode node, String field, Set<String> allowedValues, String artifactLabel) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : node) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw new IllegalArgumentException("Expected nonblank text array value: " + field);
      }
      String text = value.asText();
      if (allowedValues != null && !allowedValues.contains(text)) {
        throw new IllegalArgumentException(
            "Unsupported " + artifactLabel + " " + field + " value: " + text);
      }
      values.add(text);
    }
    return List.copyOf(values);
  }

  static <K, V> Map<K, V> unmodifiableLinkedMap(Map<K, V> values) {
    Map<K, V> copied = new LinkedHashMap<>();
    for (Map.Entry<K, V> entry : values.entrySet()) {
      copied.put(
          Objects.requireNonNull(entry.getKey(), "map key"),
          Objects.requireNonNull(entry.getValue(), "map value"));
    }
    return Collections.unmodifiableMap(copied);
  }

  static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }
}
