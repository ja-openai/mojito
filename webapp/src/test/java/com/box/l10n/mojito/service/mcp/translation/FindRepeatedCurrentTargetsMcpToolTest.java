package com.box.l10n.mojito.service.mcp.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpServerService;
import com.box.l10n.mojito.service.mcp.McpToolCallResult;
import com.box.l10n.mojito.service.mcp.McpToolRegistry;
import com.box.l10n.mojito.service.mcp.protocol.McpTransportService;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetHashIntegrityException;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionTimedOutException;

public class FindRepeatedCurrentTargetsMcpToolTest {

  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final RepeatedCurrentTargetService service =
      Mockito.mock(RepeatedCurrentTargetService.class);
  private final FindRepeatedCurrentTargetsMcpTool tool =
      new FindRepeatedCurrentTargetsMcpTool(objectMapper, service);

  @Before
  public void setUp() {
    SecurityContextHolder.clearContext();
  }

  @After
  public void tearDown() {
    SecurityContextHolder.clearContext();
    Mockito.reset(service);
  }

  @Test
  public void descriptorDeclaresReadOnlyRestartableSnapshotScanWithoutTimeInput() {
    assertThat(tool.descriptor().name()).isEqualTo("translation.find_repeated_current_targets");
    assertThat(tool.descriptor().readOnly()).isTrue();
    assertThat(tool.descriptor().dryRunByDefault()).isTrue();
    assertThat(tool.descriptor().parameters())
        .extracting(parameter -> parameter.name())
        .containsExactly(
            "reviewProjectId",
            "afterCurrentPointerId",
            "highWaterCurrentPointerId",
            "highWaterReviewProjectTextUnitId",
            "scanToken",
            "pageSize")
        .doesNotContain("fromInclusive", "toExclusive", "reviewerId");
  }

  @Test
  public void adminCanRunWholeDatabaseOrReviewProjectScan() {
    authenticateAs("ROLE_ADMIN");

    tool.execute(new FindRepeatedCurrentTargetsMcpTool.Input(null, null, null, null, null, null));
    tool.execute(
        new FindRepeatedCurrentTargetsMcpTool.Input(70L, 500L, 999L, 8000L, "v1.token", 1000));

    verify(service).scan(null, null, null, null, null, null);
    verify(service).scan(70L, 500L, 999L, 8000L, "v1.token", 1000);
  }

  @Test
  public void nonAdminCannotRunScan() {
    authenticateAs("ROLE_TRANSLATOR");

    assertThatThrownBy(
            () ->
                tool.execute(
                    new FindRepeatedCurrentTargetsMcpTool.Input(
                        null, null, null, null, null, null)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Admin role required");
    verifyNoInteractions(service);
  }

  @Test
  public void staleHashFailureIsStructuredMcpErrorOverTransport() {
    authenticateAs("ROLE_ADMIN");
    when(service.scan(null, null, null, null, null, null))
        .thenThrow(new RepeatedCurrentTargetHashIntegrityException(2));
    McpServerService serverService = new McpServerService(new McpToolRegistry(List.of(tool)));
    McpTransportService transportService =
        new McpTransportService(serverService, objectMapper, "0.111-SNAPSHOT");

    ObjectNode request = objectMapper.createObjectNode();
    request.put("jsonrpc", "2.0");
    request.put("id", 1);
    request.put("method", "tools/call");
    request.set(
        "params",
        objectMapper
            .createObjectNode()
            .put("name", "translation.find_repeated_current_targets")
            .set("arguments", objectMapper.createObjectNode()));

    McpTransportService.TransportResult response =
        transportService.handlePost(request, "2025-11-25");

    assertThat(response.httpStatus()).isEqualTo(HttpStatus.OK);
    assertThat(response.body().path("result").path("isError").asBoolean()).isTrue();
    assertThat(response.body().path("result").path("structuredContent").path("code").asText())
        .isEqualTo("STALE_CURRENT_TARGET_HASHES");
    assertThat(
            response
                .body()
                .path("result")
                .path("structuredContent")
                .path("details")
                .path("invalidCurrentTargetHashCount")
                .asLong())
        .isEqualTo(2);
    assertThat(
            response
                .body()
                .path("result")
                .path("structuredContent")
                .path("details")
                .path("action")
                .asText())
        .contains("content_md5");
  }

  @Test
  public void typedCallReturnsStructuredOperationalErrorWithoutThrowing() {
    authenticateAs("ROLE_ADMIN");
    when(service.scan(null, null, null, null, null, null))
        .thenThrow(new RepeatedCurrentTargetHashIntegrityException(1));

    McpToolCallResult result = tool.call(objectMapper.createObjectNode());

    assertThat(result.error()).isTrue();
    assertThat(result.structuredContent().path("code").asText())
        .isEqualTo("STALE_CURRENT_TARGET_HASHES");
  }

  @Test
  public void timeoutIsReturnedAsRetryableStructuredError() {
    authenticateAs("ROLE_ADMIN");
    when(service.scan(null, null, null, null, null, null))
        .thenThrow(new TransactionTimedOutException("timed out"));

    McpToolCallResult result = tool.call(objectMapper.createObjectNode());

    assertThat(result.error()).isTrue();
    assertThat(result.structuredContent().path("code").asText()).isEqualTo("QUERY_TIMEOUT");
    assertThat(result.structuredContent().path("details").path("retryable").asBoolean()).isTrue();
  }

  private static void authenticateAs(String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("operator", "ignored", role));
  }
}
