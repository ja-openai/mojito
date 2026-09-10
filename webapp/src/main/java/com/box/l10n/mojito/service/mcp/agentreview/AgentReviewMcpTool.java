package com.box.l10n.mojito.service.mcp.agentreview;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolExecutionException;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/** Agent run operations share PM/admin access; human review uses the project-scoped API. */
abstract class AgentReviewMcpTool<I> extends TypedMcpToolHandler<I> {

  AgentReviewMcpTool(ObjectMapper objectMapper, Class<I> inputType, McpToolDescriptor descriptor) {
    super(objectMapper, inputType, descriptor);
  }

  @Override
  protected final void authorizeBeforeInputConversion(JsonNode arguments) {
    requireCoordinatorRole();
  }

  @Override
  protected final Object execute(I input) {
    requireCoordinatorRole();
    try {
      return executeAuthorized(input);
    } catch (ResponseStatusException exception) {
      int status = exception.getStatusCode().value();
      String code =
          switch (status) {
            case 400 -> "AGENT_REVIEW_INVALID_ARGUMENT";
            case 403 -> "AGENT_REVIEW_FORBIDDEN";
            case 404 -> "AGENT_REVIEW_NOT_FOUND";
            case 409 -> "AGENT_REVIEW_CONFLICT";
            default -> "AGENT_REVIEW_REQUEST_FAILED";
          };
      throw new McpToolExecutionException(
          code,
          exception.getReason() == null ? "Agent review request failed" : exception.getReason(),
          Map.of("status", status));
    }
  }

  protected abstract Object executeAuthorized(I input);

  private static void requireCoordinatorRole() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean authorized =
        authentication != null
            && authentication.isAuthenticated()
            && authentication.getAuthorities().stream()
                .anyMatch(
                    authority ->
                        "ROLE_PM".equals(authority.getAuthority())
                            || "ROLE_ADMIN".equals(authority.getAuthority()));
    if (!authorized) {
      throw new AccessDeniedException("PM or admin role required");
    }
  }
}
