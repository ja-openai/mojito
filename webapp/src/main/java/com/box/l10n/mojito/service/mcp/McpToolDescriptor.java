package com.box.l10n.mojito.service.mcp;

import java.util.List;

public record McpToolDescriptor(
    String name,
    String title,
    String description,
    boolean readOnly,
    boolean dryRunByDefault,
    List<McpToolParameter> parameters) {

  public McpToolDescriptor {
    name = requireNonBlank(name, "name");
    title = requireNonBlank(title, "title");
    description = requireNonBlank(description, "description");
    parameters = List.copyOf(parameters == null ? List.of() : parameters);
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }
}
