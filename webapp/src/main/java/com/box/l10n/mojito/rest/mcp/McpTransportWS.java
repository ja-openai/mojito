package com.box.l10n.mojito.rest.mcp;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.protocol.McpTransportService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpTransportWS {

  // Preserves the 20 MiB decoded image-upload contract after base64 and JSON overhead.
  private static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;

  private final McpTransportService mcpTransportService;
  private final ObjectMapper objectMapper;
  private final int maxRequestBytes;

  public McpTransportWS(
      McpTransportService mcpTransportService,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      @Value("${l10n.mcp.max-request-bytes:" + MAX_REQUEST_BYTES + "}") int maxRequestBytes) {
    this.mcpTransportService = Objects.requireNonNull(mcpTransportService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    if (maxRequestBytes <= 0 || maxRequestBytes > MAX_REQUEST_BYTES) {
      throw new IllegalArgumentException(
          "l10n.mcp.max-request-bytes must be between 1 and " + MAX_REQUEST_BYTES);
    }
    this.maxRequestBytes = maxRequestBytes;
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> postMessage(
      @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
      HttpServletRequest request) {
    if (!mcpTransportService.isOriginAllowed(request)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .contentType(MediaType.APPLICATION_JSON)
          .body(mcpTransportService.forbiddenOriginError());
    }

    if (request.getContentLengthLong() > maxRequestBytes) {
      return requestTooLarge();
    }

    byte[] body;
    try {
      // Always enforce the streaming bound too: Content-Length can be absent or untrusted.
      body = request.getInputStream().readNBytes(maxRequestBytes + 1);
    } catch (IOException exception) {
      return parseError();
    }
    if (body.length > maxRequestBytes) {
      return requestTooLarge();
    }

    JsonNode jsonNode;
    try {
      jsonNode = objectMapper.readTree(body);
    } catch (IOException | RuntimeException exception) {
      return parseError();
    }

    McpTransportService.TransportResult transportResult =
        mcpTransportService.handlePost(jsonNode, protocolVersion);

    ResponseEntity.BodyBuilder responseBuilder =
        ResponseEntity.status(transportResult.httpStatus());

    transportResult
        .protocolVersion()
        .ifPresent(version -> responseBuilder.header("MCP-Protocol-Version", version));

    if (transportResult.body() == null) {
      return responseBuilder.build();
    }

    return responseBuilder.contentType(MediaType.APPLICATION_JSON).body(transportResult.body());
  }

  private ResponseEntity<JsonNode> requestTooLarge() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .contentType(MediaType.APPLICATION_JSON)
        .body(mcpTransportService.requestTooLargeError());
  }

  private ResponseEntity<JsonNode> parseError() {
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(mcpTransportService.parseError());
  }

  @GetMapping
  public ResponseEntity<Void> getStream(HttpServletRequest request) {
    if (!mcpTransportService.isOriginAllowed(request)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .header(HttpHeaders.ALLOW, "POST")
        .build();
  }
}
