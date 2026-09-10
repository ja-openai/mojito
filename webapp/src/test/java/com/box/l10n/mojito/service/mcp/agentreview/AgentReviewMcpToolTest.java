package com.box.l10n.mojito.service.mcp.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpServerService;
import com.box.l10n.mojito.service.mcp.McpToolCallResult;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.After;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public class AgentReviewMcpToolTest {

  private final ObjectMapper objectMapper = ObjectMapper.withNoFailOnUnknownProperties();

  @SuppressWarnings("unchecked")
  private final Function<Input, Object> operation = mock(Function.class);

  private final AgentReviewMcpTool<Input> tool =
      new AgentReviewMcpTool<>(
          objectMapper,
          Input.class,
          new McpToolDescriptor("test.review_run", "Test", "Test", false, false, List.of())) {
        @Override
        protected Object executeAuthorized(Input input) {
          return operation.apply(input);
        }
      };

  public record Input(Long runId) {}

  @After
  public void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void translatorCannotLearnValidationDetailsFromMalformedRunPayload() {
    authenticate("ROLE_TRANSLATOR");
    McpServerService server = new McpServerService(new McpToolRegistry(List.of(tool)));

    McpToolCallResult result =
        server.callTool("test.review_run", objectMapper.createObjectNode().put("runId", "bad"));

    assertThat(result.error()).isTrue();
    assertThat(result.message()).isEqualTo("PM or admin role required");
    verifyNoInteractions(operation);
  }

  @Test
  public void missingAuthenticationIsRejectedBeforeInputConversion() {
    SecurityContextHolder.clearContext();

    assertThatThrownBy(() -> tool.call(null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("PM or admin role required");
    verifyNoInteractions(operation);
  }

  @Test
  public void unauthenticatedAdminAuthorityDoesNotGrantAccess() {
    TestingAuthenticationToken authentication =
        new TestingAuthenticationToken("operator", "ignored", "ROLE_ADMIN");
    authentication.setAuthenticated(false);
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThatThrownBy(() -> tool.call(objectMapper.valueToTree(new Input(1L))))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(operation);
  }

  @Test
  public void pmCanUseRunOperationsAndReceivesStructuredResults() {
    authenticate("ROLE_PM");
    when(operation.apply(new Input(3L))).thenReturn(Map.of("runId", 3L));

    McpToolCallResult result = tool.call(objectMapper.valueToTree(new Input(3L)));

    assertThat(result.error()).isFalse();
    assertThat(result.structuredContent().path("runId").asLong()).isEqualTo(3L);
    verify(operation).apply(new Input(3L));
  }

  @Test
  public void adminCanUseRunOperations() {
    authenticate("ROLE_ADMIN");
    when(operation.apply(new Input(3L))).thenReturn(Map.of("runId", 3L));

    assertThat(tool.call(objectMapper.valueToTree(new Input(3L))).error()).isFalse();
    verify(operation).apply(new Input(3L));
  }

  @Test
  public void expectedLeaseConflictRemainsAStructuredMcpFailure() {
    authenticate("ROLE_PM");
    when(operation.apply(new Input(3L)))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Coordinator lease expired"));

    McpToolCallResult result = tool.call(objectMapper.valueToTree(new Input(3L)));

    assertThat(result.error()).isTrue();
    assertThat(result.structuredContent().path("code").asText()).isEqualTo("AGENT_REVIEW_CONFLICT");
    assertThat(result.structuredContent().at("/details/status").asInt()).isEqualTo(409);
    assertThat(result.message()).isEqualTo("Coordinator lease expired");
  }

  private static void authenticate(String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("operator", "ignored", role));
  }
}
