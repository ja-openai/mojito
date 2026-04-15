package com.box.l10n.mojito.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolCallResult(boolean error, String message, JsonNode structuredContent) {

  public static McpToolCallResult success(String message, JsonNode structuredContent) {
    return new McpToolCallResult(false, message, structuredContent);
  }

  public static McpToolCallResult error(String message) {
    return new McpToolCallResult(true, message, null);
  }
}
