package com.box.l10n.mojito.service.mcp;

import java.util.Map;
import java.util.Objects;

/** Expected operational tool failure that should remain an MCP result, not an HTTP failure. */
public class McpToolExecutionException extends RuntimeException {

  private final String code;
  private final Map<String, Object> details;

  public McpToolExecutionException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = Objects.requireNonNull(code);
    this.details = Map.copyOf(details);
  }

  public String getCode() {
    return code;
  }

  public Map<String, Object> getDetails() {
    return details;
  }
}
