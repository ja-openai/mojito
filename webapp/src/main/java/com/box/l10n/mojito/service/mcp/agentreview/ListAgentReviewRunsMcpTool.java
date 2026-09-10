package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ListAgentReviewRunsMcpTool
    extends AgentReviewMcpTool<ListAgentReviewRunsMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.list_runs",
          "List shared translation review runs",
          "Find accessible review runs before creating or resuming work, including completed runs with reusable coverage. Returns metadata, coverage counts, and artifact references; use inspect_run for each run's full checkpoint. Requires PM or admin. Results use descending run IDs; pass nextBeforeId as beforeId until null, even when a page has no accessible runs. Inspect scope/input/method/configuration versions before reusing coverage.",
          true,
          true,
          List.of(
              new McpToolParameter(
                  "teamId",
                  "Owning team; use agent_review.list_teams to discover managed teams.",
                  true,
                  positiveId()),
              new McpToolParameter(
                  "beforeId",
                  "Exclusive descending run-ID cursor from nextBeforeId; omit for the first page.",
                  false,
                  positiveId()),
              new McpToolParameter(
                  "limit", "Maximum runs, 1-200; default 50.", false, integer(1, 200))));

  private final AgentReviewService service;

  public ListAgentReviewRunsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long teamId, Integer limit, Long beforeId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    Long beforeId = input.beforeId() == null ? null : requireId(input.beforeId(), "beforeId");
    return service.listRunsPage(
        requireId(input.teamId(), "teamId"), limit(input.limit()), beforeId);
  }
}
