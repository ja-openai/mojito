package com.box.l10n.mojito.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.Test;

public class McpToolRegistryTest {

  @Test
  public void duplicateToolNamesAreRejected() {
    McpToolHandler first = tool("bad_translation.find_translation");
    McpToolHandler second = tool("bad_translation.find_translation");

    assertThatThrownBy(() -> new McpToolRegistry(java.util.List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate MCP tool name");
  }

  @Test
  public void registryReturnsToolsInDeclarationOrder() {
    McpToolRegistry registry =
        new McpToolRegistry(
            java.util.List.of(
                tool("bad_translation.find_translation"), tool("bad_translation.preview_triage")));

    assertThat(registry.listTools())
        .extracting(McpToolDescriptor::name)
        .containsExactly("bad_translation.find_translation", "bad_translation.preview_triage");
  }

  private McpToolHandler tool(String name) {
    return new McpToolHandler() {
      @Override
      public McpToolDescriptor descriptor() {
        return new McpToolDescriptor(name, name, "test tool", true, true, java.util.List.of());
      }

      @Override
      public McpToolCallResult call(com.fasterxml.jackson.databind.JsonNode arguments) {
        return McpToolCallResult.success("ok", JsonNodeFactory.instance.objectNode());
      }
    };
  }
}
