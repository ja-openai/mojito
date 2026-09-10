package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewProjectService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class RouteAgentReviewRunMcpTool
    extends AgentReviewMcpTool<RouteAgentReviewRunMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.route_run",
          "Retry review project routing",
          "Idempotently route eligible verified findings of the configured review type to human Review Projects. Already routed/resolved findings stay excluded; optional/held findings remain staged. Use to retry a reported routing error after a saved checkpoint or completed run. Requires PM/admin with run access; no TM writes or agent approval.",
          false,
          false,
          List.of(runId()));

  private final AgentReviewProjectService service;

  public RouteAgentReviewRunMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewProjectService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    return service.routeRun(requireId(input.runId(), "runId"));
  }
}
