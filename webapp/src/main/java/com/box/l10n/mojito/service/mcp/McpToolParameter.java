package com.box.l10n.mojito.service.mcp;

import java.util.Objects;

public record McpToolParameter(String name, String description, boolean required, String jsonType) {

  public McpToolParameter(String name, String description, boolean required) {
    this(name, description, required, "string");
  }

  public McpToolParameter(String name, String description, boolean required, Class<?> valueType) {
    this(name, description, required, toJsonType(valueType));
  }

  public McpToolParameter {
    name = requireNonBlank(name, "name");
    description = requireNonBlank(description, "description");
    jsonType = requireNonBlank(jsonType, "jsonType");
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
    return "string";
  }
}
