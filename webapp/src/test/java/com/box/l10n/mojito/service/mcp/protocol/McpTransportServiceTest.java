package com.box.l10n.mojito.service.mcp.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpServerService;
import com.box.l10n.mojito.service.mcp.McpToolCallResult;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolHandler;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class McpTransportServiceTest {

  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
  private McpTransportService service;

  @Before
  public void setUp() {
    McpToolHandler handler =
        new McpToolHandler() {
          @Override
          public McpToolDescriptor descriptor() {
            return new McpToolDescriptor(
                "bad_translation.find_translation",
                "Find bad-translation candidates",
                "Finds candidates",
                true,
                true,
                List.of(
                    new McpToolParameter("stringId", "String id", true),
                    new McpToolParameter("observedLocale", "Observed locale", true)));
          }

          @Override
          public McpToolCallResult call(JsonNode arguments) {
            ObjectNode structuredContent = objectMapper.createObjectNode();
            structuredContent.put("matchCount", 1);
            return McpToolCallResult.success("resolved", structuredContent);
          }
        };

    McpServerService mcpServerService = new McpServerService(new McpToolRegistry(List.of(handler)));
    service = new McpTransportService(mcpServerService, objectMapper, "0.111-SNAPSHOT");
  }

  @Test
  public void initializeNegotiatesSupportedProtocolVersion() {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 1);
    request.put("method", "initialize");

    ObjectNode params = objectMapper.createObjectNode();
    params.put("protocolVersion", "2025-03-26");
    params.set("capabilities", objectMapper.createObjectNode());
    params.set("clientInfo", objectMapper.createObjectNode().put("name", "test-client"));
    request.set("params", params);

    McpTransportService.TransportResult result = service.handlePost(request, null);

    assertThat(result.httpStatus()).isEqualTo(org.springframework.http.HttpStatus.OK);
    assertThat(result.protocolVersion()).contains("2025-03-26");
    assertThat(result.body().path("result").path("serverInfo").path("name").asText())
        .isEqualTo("Mojito MCP");
    assertThat(result.body().path("result").path("capabilities").has("tools")).isTrue();
  }

  @Test
  public void toolsListReturnsToolMetadataAndJsonSchema() {
    ObjectNode request = request("tools/list", 2);

    McpTransportService.TransportResult result = service.handlePost(request, "2025-11-25");

    JsonNode tool = result.body().path("result").path("tools").get(0);
    assertThat(tool.path("name").asText()).isEqualTo("bad_translation.find_translation");
    assertThat(tool.path("inputSchema").path("properties").has("observedLocale")).isTrue();
    assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
  }

  @Test
  public void toolsCallReturnsToolResultInsideMcpCallToolShape() {
    ObjectNode request = request("tools/call", 3);
    ObjectNode params = objectMapper.createObjectNode();
    params.put("name", "bad_translation.find_translation");
    params.set(
        "arguments",
        objectMapper.createObjectNode().put("stringId", "A").put("observedLocale", "hr-HR"));
    request.set("params", params);

    McpTransportService.TransportResult result = service.handlePost(request, "2025-11-25");

    assertThat(result.body().path("result").path("content").get(0).path("type").asText())
        .isEqualTo("text");
    assertThat(result.body().path("result").path("structuredContent").path("matchCount").asInt())
        .isEqualTo(1);
  }

  @Test
  public void initializedNotificationReturnsAccepted() {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("method", "notifications/initialized");

    McpTransportService.TransportResult result = service.handlePost(request, "2025-11-25");

    assertThat(result.httpStatus()).isEqualTo(org.springframework.http.HttpStatus.ACCEPTED);
    assertThat(result.body()).isNull();
  }

  @Test
  public void unsupportedProtocolVersionReturnsBadRequest() {
    ObjectNode request = request("tools/list", 2);

    McpTransportService.TransportResult result = service.handlePost(request, "9999-01-01");

    assertThat(result.httpStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    assertThat(result.body()).isNull();
  }

  private ObjectNode request(String method, int id) {
    ObjectNode request = objectMapper.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", id);
    request.put("method", method);
    return request;
  }
}
