package com.box.l10n.mojito.service.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;

public class McpServerServiceTest {

  @Test
  public void callToolReturnsValidationMessageAsToolError() {
    McpServerService service =
        new McpServerService(
            new McpToolRegistry(
                List.of(failingTool(new IllegalArgumentException("limit required")))));

    McpToolCallResult result = service.callTool("glossary.review_inflection_profiles", arguments());

    assertThat(result.error()).isTrue();
    assertThat(result.message()).isEqualTo("limit required");
  }

  @Test
  public void callToolReturnsStableMessageForBlankValidationError() {
    McpServerService service =
        new McpServerService(
            new McpToolRegistry(List.of(failingTool(new IllegalArgumentException()))));

    McpToolCallResult result = service.callTool("glossary.review_inflection_profiles", arguments());

    assertThat(result.error()).isTrue();
    assertThat(result.message())
        .isEqualTo("MCP tool call failed: glossary.review_inflection_profiles");
  }

  @Test
  public void callToolReturnsStableMessageForBlankAccessDeniedError() {
    McpServerService service =
        new McpServerService(
            new McpToolRegistry(List.of(failingTool(new AccessDeniedException("")))));

    McpToolCallResult result = service.callTool("glossary.review_inflection_profiles", arguments());

    assertThat(result.error()).isTrue();
    assertThat(result.message())
        .isEqualTo("MCP tool access denied: glossary.review_inflection_profiles");
  }

  private McpToolHandler failingTool(RuntimeException exception) {
    return new McpToolHandler() {
      @Override
      public McpToolDescriptor descriptor() {
        return new McpToolDescriptor(
            "glossary.review_inflection_profiles",
            "Review inflection profiles",
            "Review glossary term inflection profiles",
            true,
            true,
            List.of());
      }

      @Override
      public McpToolCallResult call(JsonNode arguments) {
        throw exception;
      }
    };
  }

  private JsonNode arguments() {
    return JsonNodeFactory.instance.objectNode();
  }
}
