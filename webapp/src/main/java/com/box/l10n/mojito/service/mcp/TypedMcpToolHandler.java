package com.box.l10n.mojito.service.mcp;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public abstract class TypedMcpToolHandler<I> implements McpToolHandler {

  private final ObjectMapper objectMapper;
  private final Class<I> inputType;
  private final McpToolDescriptor descriptor;

  protected TypedMcpToolHandler(
      ObjectMapper objectMapper, Class<I> inputType, McpToolDescriptor descriptor) {
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.inputType = Objects.requireNonNull(inputType);
    this.descriptor = Objects.requireNonNull(descriptor);
  }

  @Override
  public McpToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public final McpToolCallResult call(JsonNode arguments) {
    authorizeBeforeInputConversion(arguments);

    if (arguments == null || arguments.isNull()) {
      throw new IllegalArgumentException("arguments are required");
    }

    I input = objectMapper.convertValue(arguments, inputType);
    if (input == null) {
      throw new IllegalArgumentException("arguments could not be parsed");
    }

    try {
      Object result = execute(input);
      return McpToolCallResult.success("ok", objectMapper.valueToTree(result));
    } catch (McpToolExecutionException exception) {
      ObjectNode structuredContent = objectMapper.createObjectNode();
      structuredContent.put("code", exception.getCode());
      structuredContent.put("message", exception.getMessage());
      structuredContent.set("details", objectMapper.valueToTree(exception.getDetails()));
      return McpToolCallResult.error(exception.getMessage(), structuredContent);
    }
  }

  /** Allows role-sensitive tools to fail closed before typed input conversion. */
  protected void authorizeBeforeInputConversion(JsonNode arguments) {}

  protected abstract Object execute(I input);
}
