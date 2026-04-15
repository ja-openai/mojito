package com.box.l10n.mojito.rest.mcp;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.protocol.McpTransportService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mcp")
public class McpTransportWS {

  private final McpTransportService mcpTransportService;
  private final ObjectMapper objectMapper;

  public McpTransportWS(
      McpTransportService mcpTransportService,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper) {
    this.mcpTransportService = Objects.requireNonNull(mcpTransportService);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> postMessage(
      @RequestBody(required = false) String body,
      @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
      HttpServletRequest request) {
    if (!mcpTransportService.isOriginAllowed(request)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN)
          .contentType(MediaType.APPLICATION_JSON)
          .body(mcpTransportService.forbiddenOriginError());
    }

    JsonNode jsonNode;
    try {
      jsonNode = objectMapper.readTreeUnchecked(body == null ? "" : body);
    } catch (RuntimeException exception) {
      return ResponseEntity.badRequest()
          .contentType(MediaType.APPLICATION_JSON)
          .body(mcpTransportService.parseError());
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
