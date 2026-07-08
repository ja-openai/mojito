package com.box.l10n.mojito.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class McpServerService {

  private final McpToolRegistry mcpToolRegistry;

  public McpServerService(McpToolRegistry mcpToolRegistry) {
    this.mcpToolRegistry = Objects.requireNonNull(mcpToolRegistry);
  }

  public List<McpToolDescriptor> listTools() {
    return mcpToolRegistry.listTools();
  }

  public McpToolCallResult callTool(String toolName, JsonNode arguments) {
    try {
      return mcpToolRegistry.getRequired(toolName).call(arguments);
    } catch (AccessDeniedException exception) {
      return McpToolCallResult.error(
          errorMessage(exception.getMessage(), "MCP tool access denied", toolName));
    } catch (IllegalArgumentException exception) {
      return McpToolCallResult.error(
          errorMessage(exception.getMessage(), "MCP tool call failed", toolName));
    }
  }

  private String errorMessage(String message, String fallback, String toolName) {
    if (message != null && !message.isBlank()) {
      return message;
    }
    if (toolName == null || toolName.isBlank()) {
      return fallback;
    }
    return fallback + ": " + toolName;
  }
}
