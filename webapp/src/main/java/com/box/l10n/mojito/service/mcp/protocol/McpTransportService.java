package com.box.l10n.mojito.service.mcp.protocol;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpServerService;
import com.box.l10n.mojito.service.mcp.McpToolCallResult;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class McpTransportService {

  static final String JSON_RPC_VERSION = "2.0";
  static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
      List.of("2025-03-26", "2025-06-18", "2025-11-25");

  private final McpServerService mcpServerService;
  private final ObjectMapper objectMapper;
  private final String serverVersion;

  public record TransportResult(
      HttpStatus httpStatus, JsonNode body, Optional<String> protocolVersion) {}

  public McpTransportService(
      McpServerService mcpServerService,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      @Value("${info.build.version:dev}") String serverVersion) {
    this.mcpServerService = Objects.requireNonNull(mcpServerService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.serverVersion = Objects.requireNonNull(serverVersion);
  }

  public TransportResult handlePost(JsonNode message, String protocolVersionHeader) {
    if (message == null || message.isNull() || !message.isObject()) {
      return jsonError(HttpStatus.BAD_REQUEST, null, -32600, "Invalid Request");
    }

    ObjectNode messageObject = (ObjectNode) message;
    JsonNode id = messageObject.get("id");
    JsonNode methodNode = messageObject.get("method");

    if (!JSON_RPC_VERSION.equals(messageObject.path("jsonrpc").asText(null))) {
      return jsonError(HttpStatus.BAD_REQUEST, id, -32600, "Invalid Request");
    }

    if (methodNode != null && methodNode.isTextual()) {
      String method = methodNode.asText();
      if (id == null || id.isNull()) {
        return handleNotification(messageObject);
      }

      if (!"initialize".equals(method)) {
        TransportResult invalidVersion = validateProtocolVersion(protocolVersionHeader);
        if (invalidVersion != null) {
          return invalidVersion;
        }
      }

      return handleRequest(messageObject, protocolVersionHeader);
    }

    if (id != null && (messageObject.has("result") || messageObject.has("error"))) {
      return new TransportResult(HttpStatus.ACCEPTED, null, Optional.empty());
    }

    return jsonError(HttpStatus.BAD_REQUEST, id, -32600, "Invalid Request");
  }

  public boolean isOriginAllowed(HttpServletRequest request) {
    String origin = request.getHeader("Origin");
    if (origin == null || origin.isBlank()) {
      return true;
    }

    try {
      URI originUri = new URI(origin);
      String expectedScheme =
          firstNonBlank(request.getHeader("X-Forwarded-Proto")).orElse(request.getScheme());
      String expectedHost =
          firstNonBlank(request.getHeader("X-Forwarded-Host"))
              .orElseGet(
                  () ->
                      request.getServerPort() > 0
                              && request.getServerPort() != 80
                              && request.getServerPort() != 443
                          ? request.getServerName() + ":" + request.getServerPort()
                          : request.getServerName());

      String originHost =
          originUri.getPort() == -1
              ? originUri.getHost()
              : originUri.getHost() + ":" + originUri.getPort();

      return expectedScheme.equalsIgnoreCase(originUri.getScheme())
          && expectedHost.equalsIgnoreCase(originHost);
    } catch (URISyntaxException | IllegalArgumentException exception) {
      return false;
    }
  }

  public JsonNode forbiddenOriginError() {
    return errorResponse(null, -32600, "Forbidden Origin");
  }

  public JsonNode parseError() {
    return errorResponse(null, -32700, "Parse error");
  }

  private TransportResult handleNotification(ObjectNode messageObject) {
    String method = messageObject.path("method").asText();
    if ("notifications/initialized".equals(method) || method.startsWith("notifications/")) {
      return new TransportResult(HttpStatus.ACCEPTED, null, Optional.empty());
    }

    return jsonError(HttpStatus.BAD_REQUEST, null, -32601, "Method not found");
  }

  private TransportResult handleRequest(ObjectNode messageObject, String protocolVersionHeader) {
    JsonNode id = messageObject.get("id");
    String method = messageObject.path("method").asText();
    JsonNode params = messageObject.get("params");

    return switch (method) {
      case "initialize" -> handleInitialize(id, params);
      case "ping" ->
          jsonResult(HttpStatus.OK, id, objectMapper.createObjectNode(), protocolVersionHeader);
      case "tools/list" -> handleToolsList(id, protocolVersionHeader);
      case "tools/call" -> handleToolsCall(id, params, protocolVersionHeader);
      default -> jsonError(HttpStatus.OK, id, -32601, "Method not found");
    };
  }

  private TransportResult handleInitialize(JsonNode id, JsonNode params) {
    if (params == null || !params.isObject()) {
      return jsonError(HttpStatus.OK, id, -32602, "Invalid params");
    }

    String requestedProtocolVersion = params.path("protocolVersion").asText(null);
    String negotiatedProtocolVersion = negotiateProtocolVersion(requestedProtocolVersion);

    ObjectNode result = objectMapper.createObjectNode();
    result.put("protocolVersion", negotiatedProtocolVersion);
    ObjectNode capabilities = objectMapper.createObjectNode();
    capabilities.set("tools", objectMapper.createObjectNode());
    result.set("capabilities", capabilities);

    ObjectNode serverInfo = objectMapper.createObjectNode();
    serverInfo.put("name", "Mojito MCP");
    serverInfo.put("version", serverVersion);
    result.set("serverInfo", serverInfo);

    return jsonResult(HttpStatus.OK, id, result, negotiatedProtocolVersion);
  }

  private TransportResult handleToolsList(JsonNode id, String protocolVersionHeader) {
    ArrayNode tools = objectMapper.createArrayNode();
    for (McpToolDescriptor descriptor : mcpServerService.listTools()) {
      ObjectNode toolNode = objectMapper.createObjectNode();
      toolNode.put("name", descriptor.name());
      toolNode.put("title", descriptor.title());
      toolNode.put("description", descriptor.description());
      toolNode.set("inputSchema", toInputSchema(descriptor.parameters()));

      ObjectNode annotations = objectMapper.createObjectNode();
      annotations.put("title", descriptor.title());
      annotations.put("readOnlyHint", descriptor.readOnly());
      annotations.put("destructiveHint", !descriptor.readOnly());
      annotations.put("idempotentHint", descriptor.readOnly() || descriptor.dryRunByDefault());
      annotations.put("openWorldHint", false);
      toolNode.set("annotations", annotations);

      ObjectNode execution = objectMapper.createObjectNode();
      execution.put("taskSupport", "forbidden");
      toolNode.set("execution", execution);
      tools.add(toolNode);
    }

    ObjectNode result = objectMapper.createObjectNode();
    result.set("tools", tools);
    return jsonResult(HttpStatus.OK, id, result, protocolVersionHeader);
  }

  private TransportResult handleToolsCall(
      JsonNode id, JsonNode params, String protocolVersionHeader) {
    if (params == null || !params.isObject()) {
      return jsonError(HttpStatus.OK, id, -32602, "Invalid params");
    }

    String toolName = params.path("name").asText(null);
    JsonNode arguments =
        params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
    if (toolName == null || toolName.isBlank()) {
      return jsonError(HttpStatus.OK, id, -32602, "Invalid params");
    }

    McpToolCallResult toolCallResult = mcpServerService.callTool(toolName, arguments);
    ObjectNode result = objectMapper.createObjectNode();

    ArrayNode content = objectMapper.createArrayNode();
    ObjectNode textContent = objectMapper.createObjectNode();
    textContent.put("type", "text");
    textContent.put("text", toolResultText(toolCallResult));
    content.add(textContent);
    result.set("content", content);

    if (toolCallResult.structuredContent() != null
        && toolCallResult.structuredContent().isObject()) {
      result.set("structuredContent", toolCallResult.structuredContent());
    }

    if (toolCallResult.error()) {
      result.put("isError", true);
    }

    return jsonResult(HttpStatus.OK, id, result, protocolVersionHeader);
  }

  private ObjectNode toInputSchema(List<McpToolParameter> parameters) {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    schema.put("type", "object");

    ObjectNode properties = objectMapper.createObjectNode();
    Set<String> required = new LinkedHashSet<>();

    for (McpToolParameter parameter : parameters) {
      ObjectNode property = toParameterSchema(parameter);
      properties.set(parameter.name(), property);

      if (parameter.required()) {
        required.add(parameter.name());
      }
    }

    schema.set("properties", properties);
    schema.put("additionalProperties", false);
    if (!required.isEmpty()) {
      ArrayNode requiredNode = objectMapper.createArrayNode();
      required.forEach(requiredNode::add);
      schema.set("required", requiredNode);
    }

    return schema;
  }

  private ObjectNode toParameterSchema(McpToolParameter parameter) {
    if (parameter.jsonSchema() != null) {
      ObjectNode property = objectMapper.valueToTree(parameter.jsonSchema());
      if (!property.has("description")) {
        property.put("description", parameter.description());
      }
      return property;
    }

    ObjectNode property = objectMapper.createObjectNode();
    property.put("type", parameter.jsonType());
    property.put("description", parameter.description());
    return property;
  }

  private String toolResultText(McpToolCallResult toolCallResult) {
    if (toolCallResult.message() != null && !toolCallResult.message().isBlank()) {
      return toolCallResult.message();
    }

    if (toolCallResult.structuredContent() != null
        && !toolCallResult.structuredContent().isNull()) {
      return objectMapper.writeValueAsStringUnchecked(toolCallResult.structuredContent());
    }

    return toolCallResult.error() ? "Tool call failed" : "Tool call completed";
  }

  private String negotiateProtocolVersion(String requestedProtocolVersion) {
    if (requestedProtocolVersion != null
        && SUPPORTED_PROTOCOL_VERSIONS.contains(requestedProtocolVersion)) {
      return requestedProtocolVersion;
    }

    return SUPPORTED_PROTOCOL_VERSIONS.getLast();
  }

  private TransportResult validateProtocolVersion(String protocolVersionHeader) {
    if (protocolVersionHeader == null || protocolVersionHeader.isBlank()) {
      return null;
    }

    if (!SUPPORTED_PROTOCOL_VERSIONS.contains(protocolVersionHeader)) {
      return new TransportResult(HttpStatus.BAD_REQUEST, null, Optional.empty());
    }

    return null;
  }

  private TransportResult jsonResult(
      HttpStatus httpStatus, JsonNode id, JsonNode result, String protocolVersion) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", JSON_RPC_VERSION);
    response.set("id", id == null ? objectMapper.nullNode() : id);
    response.set("result", result);
    return new TransportResult(httpStatus, response, firstNonBlank(protocolVersion));
  }

  private TransportResult jsonError(HttpStatus httpStatus, JsonNode id, int code, String message) {
    return new TransportResult(httpStatus, errorResponse(id, code, message), Optional.empty());
  }

  private JsonNode errorResponse(JsonNode id, int code, String message) {
    ObjectNode response = objectMapper.createObjectNode();
    response.put("jsonrpc", JSON_RPC_VERSION);
    response.set("id", id == null ? objectMapper.nullNode() : id);

    ObjectNode error = objectMapper.createObjectNode();
    error.put("code", code);
    error.put("message", message);
    response.set("error", error);
    return response;
  }

  private Optional<String> firstNonBlank(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value);
  }
}
