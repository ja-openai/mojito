package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Artifact;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.ArtifactRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class UploadAgentReviewArtifactMcpTool
    extends AgentReviewMcpTool<UploadAgentReviewArtifactMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.upload_artifact",
          "Save durable review artifact",
          "Store up to 2 MiB of decoded immutable run input, context, or checkpoint evidence. Returns a SHA-256 reference to the stored envelope, contentType, and byteCount. Requires PM/admin and the active coordinator claim. Save artifacts before committing checkpoint references; keep them portable across machines.",
          false,
          false,
          List.of(
              runId(),
              new McpToolParameter(
                  "artifact", "Base64 bytes, content type, and active claim.", true, artifact())));

  private final AgentReviewService service;

  public UploadAgentReviewArtifactMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      AgentReviewService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = service;
  }

  public record Input(Long runId, ArtifactRequest artifact) {}

  public record Result(String sha256, String contentType, int byteCount) {}

  @Override
  protected Object executeAuthorized(Input input) {
    Artifact artifact = service.uploadArtifact(requireId(input.runId(), "runId"), input.artifact());
    return new Result(artifact.sha256(), artifact.contentType(), artifact.byteCount());
  }
}
