package com.box.l10n.mojito.service.mcp;

import java.util.Map;
import java.util.Objects;

public record McpToolParameter(
    String name,
    String description,
    boolean required,
    String jsonType,
    Map<String, Object> jsonSchema) {

  public McpToolParameter(String name, String description, boolean required) {
    this(name, description, required, "string");
  }

  public McpToolParameter(String name, String description, boolean required, Class<?> valueType) {
    this(name, description, required, toJsonType(valueType));
  }

  public McpToolParameter(String name, String description, boolean required, String jsonType) {
    this(name, description, required, jsonType, null);
  }

  public McpToolParameter(
      String name, String description, boolean required, Map<String, Object> jsonSchema) {
    this(name, description, required, null, jsonSchema);
  }

  public McpToolParameter {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    if (jsonSchema == null) {
      jsonType = requireNonBlank(jsonType, "jsonType");
    } else {
      jsonType = jsonType == null || jsonType.isBlank() ? null : jsonType.trim();
      jsonSchema = Map.copyOf(jsonSchema);
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }

  private static String toJsonType(Class<?> valueType) {
    Objects.requireNonNull(valueType);
    if (Boolean.class.equals(valueType) || boolean.class.equals(valueType)) {
      return "boolean";
    }
    if (Integer.class.equals(valueType)
        || int.class.equals(valueType)
        || Long.class.equals(valueType)
        || long.class.equals(valueType)) {
      return "integer";
    }
    return "string";
  }
}
