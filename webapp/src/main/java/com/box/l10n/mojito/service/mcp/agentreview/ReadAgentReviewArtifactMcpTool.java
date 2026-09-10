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
public class ReadAgentReviewArtifactMcpTool
    extends AgentReviewMcpTool<ReadAgentReviewArtifactMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.read_artifact",
          "Read durable review artifact",
          "Restore a run-scoped immutable artifact using its SHA-256 reference. Returns contentBase64, contentType, byteCount, and sha256. Requires PM/admin with run access; never reads an arbitrary filesystem path or URL.",
          true,
          true,
          List.of(
              runId(),
              new McpToolParameter(
                  "sha256",
                  "Artifact digest from the run manifest, checkpoint, or an upload result.",
                  true,
                  sha256())));

  private final AgentReviewService service;

  public ReadAgentReviewArtifactMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId, String sha256) {}

  @Override
  protected Object executeAuthorized(Input input) {
    return service.readArtifact(requireId(input.runId(), "runId"), input.sha256());
  }
}
