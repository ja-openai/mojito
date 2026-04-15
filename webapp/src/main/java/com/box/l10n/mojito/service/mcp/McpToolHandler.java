package com.box.l10n.mojito.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public interface McpToolHandler {

  McpToolDescriptor descriptor();

  McpToolCallResult call(JsonNode arguments);
}
