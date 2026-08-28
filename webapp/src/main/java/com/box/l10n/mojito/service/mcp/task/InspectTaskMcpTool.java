package com.box.l10n.mojito.service.mcp.task;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInspectionService;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class InspectTaskMcpTool extends TypedMcpToolHandler<InspectTaskMcpTool.Input> {

  private static final Set<String> ALLOWED_ROLES =
      Set.of("ROLE_TRANSLATOR", "ROLE_PM", "ROLE_ADMIN");

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "task.inspect",
          "Inspect Mojito task",
          "Look up a Mojito pollable task by id and return its status, repository context, failure details, timestamps, and related API links. Requires a translator, PM, or admin role.",
          true,
          true,
          List.of(new McpToolParameter("taskId", "Pollable task id to inspect.", true)));

  private final PollableTaskInspectionService pollableTaskInspectionService;

  public InspectTaskMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      PollableTaskInspectionService pollableTaskInspectionService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.pollableTaskInspectionService = pollableTaskInspectionService;
  }

  public record Input(Long taskId) {}

  @Override
  protected Object execute(Input input) {
    if (!hasCurrentAuthenticationAllowedRole()) {
      throw new AccessDeniedException("Translator, PM, or admin role required");
    }
    if (input.taskId() == null) {
      throw new IllegalArgumentException("taskId is required");
    }

    return pollableTaskInspectionService.inspectTask(input.taskId());
  }

  private boolean hasCurrentAuthenticationAllowedRole() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> ALLOWED_ROLES.contains(authority.getAuthority()));
  }
}
