package com.box.l10n.mojito.service.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class McpToolRegistry {

  private final Map<String, McpToolHandler> handlersByName;

  public McpToolRegistry(List<McpToolHandler> handlers) {
    Objects.requireNonNull(handlers);

    LinkedHashMap<String, McpToolHandler> handlersByName = new LinkedHashMap<>();
    for (McpToolHandler handler : handlers) {
      String toolName = handler.descriptor().name();
      McpToolHandler previous = handlersByName.putIfAbsent(toolName, handler);
      if (previous != null) {
        throw new IllegalStateException("Duplicate MCP tool name: " + toolName);
      }
    }

    this.handlersByName = Collections.unmodifiableMap(new LinkedHashMap<>(handlersByName));
  }

  public List<McpToolDescriptor> listTools() {
    return handlersByName.values().stream().map(McpToolHandler::descriptor).toList();
  }

  public McpToolHandler getRequired(String toolName) {
    if (toolName == null || toolName.trim().isEmpty()) {
      throw new IllegalArgumentException("toolName is required");
    }

    McpToolHandler handler = handlersByName.get(toolName);
    if (handler == null) {
      throw new IllegalArgumentException("Unknown MCP tool: " + toolName);
    }

    return handler;
  }
}
