package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.CreateRunRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class CreateAgentReviewRunMcpTool extends AgentReviewMcpTool<CreateRunRequest> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.create_run",
          "Create shared translation review run",
          "Persist immutable locale/feature scope and input manifest for a shared agent review run. Requires PM or admin with team/repository access. Same requestKey and payload reuse the run; changed payload conflicts. Does not start Codex, alter current translations, or approve proposals.",
          false,
          false,
          List.of(
              new McpToolParameter(
                  "requestKey",
                  "Stable client retry key; keep unchanged when retrying this creation.",
                  true,
                  string(128)),
              new McpToolParameter(
                  "reviewType",
                  "Configured workflow type. Use TRANSLATION_QUALITY for standard translation review.",
                  true,
                  string(64)),
              new McpToolParameter(
                  "teamId", "Team owning review and human assignments.", true, positiveId()),
              new McpToolParameter(
                  "methodVersion",
                  "Version of review rubric, prompts, models, and verifier method.",
                  true,
                  string(128)),
              new McpToolParameter(
                  "configurationVersion",
                  "Version of scope preparation and reviewer configuration.",
                  true,
                  string(128)),
              new McpToolParameter(
                  "groups",
                  "Non-overlapping repository/locale/feature groups with explicit text-unit scope. At most 10,000 total scoped rows.",
                  true,
                  groups()),
              new McpToolParameter(
                  "inputManifestJson",
                  "JSON containing frozen inputs, context provenance, and portable artifact descriptions. Stored durably with the scope.",
                  true,
                  string(AgentReviewContracts.MAX_ARTIFACT_BYTES)),
              new McpToolParameter(
                  "dueDateOffsetDays",
                  "Days from project creation to due date; defaults to 7.",
                  false,
                  integer(1, 365)),
              new McpToolParameter(
                  "maxWordCountPerProject",
                  "Source-word limit for automatically created projects; defaults to 1500.",
                  false,
                  integer(1, 100000)),
              new McpToolParameter(
                  "assignTranslator",
                  "Use normal team assignment when routing; defaults to true. Unassigned projects remain visible to PMs.",
                  false,
                  Boolean.class)));

  private final AgentReviewService service;

  public CreateAgentReviewRunMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, CreateRunRequest.class, DESCRIPTOR);
    this.service = service;
  }

  @Override
  protected Object executeAuthorized(CreateRunRequest input) {
    return service.createRun(input);
  }
}
