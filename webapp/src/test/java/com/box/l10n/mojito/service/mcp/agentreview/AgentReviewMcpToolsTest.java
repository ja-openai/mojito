package com.box.l10n.mojito.service.mcp.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.agentreview.AgentReviewFeedback;
import com.box.l10n.mojito.entity.agentreview.Category;
import com.box.l10n.mojito.entity.agentreview.FeedbackAction;
import com.box.l10n.mojito.entity.agentreview.Readiness;
import com.box.l10n.mojito.entity.agentreview.RunStatus;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Artifact;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.ArtifactRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Checkpoint;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.CheckpointRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Claim;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.GroupCheckpoint;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.GroupStatus;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.ResponseRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.RunPage;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.RunSummary;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.RunView;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.SubmissionResult;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.SubmitProposalRequest;
import com.box.l10n.mojito.service.agentreview.AgentReviewProjectService;
import com.box.l10n.mojito.service.agentreview.AgentReviewService;
import com.box.l10n.mojito.service.mcp.McpServerService;
import com.box.l10n.mojito.service.mcp.McpToolCallResult;
import com.box.l10n.mojito.service.mcp.McpToolHandler;
import com.box.l10n.mojito.service.mcp.McpToolRegistry;
import com.box.l10n.mojito.service.mcp.protocol.McpTransportService;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public class AgentReviewMcpToolsTest {
  private final ObjectMapper mapper = ObjectMapper.withNoFailOnUnknownProperties();
  private final AgentReviewService service = mock(AgentReviewService.class);
  private final AgentReviewProjectService projectService = mock(AgentReviewProjectService.class);
  private final TeamService teamService = mock(TeamService.class);
  private final Claim claim = new Claim("coordinator", 3);

  @Before
  public void authenticatePm() {
    authenticate("ROLE_PM");
  }

  @After
  public void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void everyAgentToolRejectsTranslatorBeforeConvertingMalformedArguments() {
    authenticate("ROLE_TRANSLATOR");
    McpServerService server = new McpServerService(new McpToolRegistry(tools()));

    for (McpToolHandler tool : tools()) {
      McpToolCallResult result =
          server.callTool(tool.descriptor().name(), mapper.getNodeFactory().textNode("invalid"));
      assertThat(result.error()).as(tool.descriptor().name()).isTrue();
      assertThat(result.message())
          .as(tool.descriptor().name())
          .isEqualTo("PM or admin role required");
    }
    verifyNoInteractions(service, projectService, teamService);
  }

  @Test
  public void toolsListExposesBoundedProposalContractAndNoHumanApprovalTool() {
    McpTransportService transport =
        new McpTransportService(new McpServerService(new McpToolRegistry(tools())), mapper, "test");
    var message =
        mapper.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list");
    var result = transport.handlePost(message, "2025-11-25");
    var descriptors = result.body().path("result").path("tools");
    assertThat(descriptors.size()).isEqualTo(16);
    var proposalTool =
        java.util.stream.StreamSupport.stream(descriptors.spliterator(), false)
            .filter(node -> "agent_review.submit_proposals".equals(node.path("name").asText()))
            .findFirst()
            .orElseThrow();
    var proposals = proposalTool.at("/inputSchema/properties/proposals");
    assertThat(proposals.path("maxItems").asInt()).isEqualTo(50);
    assertThat(proposals.at("/items/properties/source/maxLength").asInt()).isEqualTo(64000);
    assertThat(proposals.at("/items/properties/baselineTarget/anyOf/1/type").asText())
        .isEqualTo("null");
    assertThat(proposals.at("/items/properties/claim/properties/generation/type").asText())
        .isEqualTo("integer");
    var runsTool =
        java.util.stream.StreamSupport.stream(descriptors.spliterator(), false)
            .filter(node -> "agent_review.list_runs".equals(node.path("name").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(runsTool.at("/inputSchema/properties/beforeId/minimum").asInt()).isEqualTo(1);
    assertThat(descriptors.toString())
        .doesNotContain("agent_review.accept", "agent_review.apply", "agent_review.reject");
  }

  @Test
  public void mixedBatchOutcomesPreserveExactBaselineAndStaySeparateFromRouting() {
    SubmitProposalRequest first = proposal("first", "  L’original\n", "La suggestion");
    SubmitProposalRequest second = proposal("second", "", null);
    List<SubmissionResult> results =
        List.of(
            new SubmissionResult(0, "first", 23L, "finding-a", 1, null, null),
            new SubmissionResult(
                1, "second", null, null, null, "409 CONFLICT", "Baseline changed"));
    when(service.submitProposals(17, List.of(first, second))).thenReturn(results);
    var tool = new SubmitAgentReviewProposalsMcpTool(mapper, service);

    var result =
        tool.call(
            mapper.valueToTree(
                new SubmitAgentReviewProposalsMcpTool.Input(17L, List.of(first, second))));

    assertThat(result.error()).isFalse();
    assertThat(result.structuredContent().at("/results/0/proposalId").asLong()).isEqualTo(23L);
    assertThat(result.structuredContent().at("/results/1/errorCode").asText())
        .isEqualTo("409 CONFLICT");
    verify(service).submitProposals(17, List.of(first, second));
    verifyNoInteractions(projectService);
  }

  @Test
  public void checkpointReturnsSavedCoverageAlongsideRecoverableRoutingFailure() {
    CheckpointRequest checkpoint =
        new CheckpointRequest(
            claim, 8, "fr/settings", GroupStatus.COMPLETED, 2, "a".repeat(64), null);
    RunView saved =
        runView(
            9,
            new Checkpoint(
                Map.of(
                    "fr/settings",
                    new GroupCheckpoint(GroupStatus.COMPLETED, 2, "a".repeat(64), null))));
    when(service.checkpoint(17, checkpoint)).thenReturn(saved);
    when(projectService.routeRun(17))
        .thenReturn(
            new AgentReviewProjectService.RouteResult(
                17L, List.of(), 0, 0, List.of("Routing could not complete; retry")));
    var tool = new CheckpointAgentReviewRunMcpTool(mapper, service, projectService);

    var result =
        tool.call(mapper.valueToTree(new CheckpointAgentReviewRunMcpTool.Input(17L, checkpoint)));

    assertThat(result.error()).isFalse();
    assertThat(result.structuredContent().at("/run/revision").asLong()).isEqualTo(9L);
    assertThat(
            result
                .structuredContent()
                .at("/run/checkpoint/groups/fr~1settings/reviewedItemCount")
                .asInt())
        .isEqualTo(2);
    assertThat(result.structuredContent().at("/routing/errors/0").asText()).contains("retry");
    InOrder order = inOrder(service, projectService);
    order.verify(service).checkpoint(17, checkpoint);
    order.verify(projectService).routeRun(17);
  }

  @Test
  public void rejectedCheckpointDoesNotRouteFindings() {
    when(service.checkpoint(anyLong(), any()))
        .thenThrow(new IllegalArgumentException("Stale claim"));
    var tool = new CheckpointAgentReviewRunMcpTool(mapper, service, projectService);

    assertThatThrownBy(
            () ->
                tool.call(mapper.valueToTree(new CheckpointAgentReviewRunMcpTool.Input(17L, null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Stale claim");
    verifyNoInteractions(projectService);
  }

  @Test
  public void uploadReturnsReferenceWithoutEchoingLargeArtifactContent() {
    String encoded = Base64.getEncoder().encodeToString(new byte[4096]);
    ArtifactRequest artifact = new ArtifactRequest(claim, "application/json", encoded);
    when(service.uploadArtifact(17, artifact))
        .thenReturn(new Artifact("a".repeat(64), "application/json", encoded, 4096));
    var tool = new UploadAgentReviewArtifactMcpTool(mapper, service);

    var result =
        tool.call(mapper.valueToTree(new UploadAgentReviewArtifactMcpTool.Input(17L, artifact)));

    assertThat(result.structuredContent().path("sha256").asText()).isEqualTo("a".repeat(64));
    assertThat(result.structuredContent().path("byteCount").asInt()).isEqualTo(4096);
    assertThat(result.structuredContent().has("contentBase64")).isFalse();
  }

  @Test
  public void pmTeamDiscoveryUsesOnlyManagedTeamsAndSupportsStablePagination() {
    when(teamService.findCurrentUserTeams())
        .thenReturn(List.of(team(19, "Second"), team(3, "First")));
    var tool = new ListAgentReviewTeamsMcpTool(mapper, teamService);

    var result = tool.call(mapper.valueToTree(new ListAgentReviewTeamsMcpTool.Input(3L, 1)));

    assertThat(result.structuredContent().at("/teams/0/id").asLong()).isEqualTo(19L);
    assertThat(result.structuredContent().path("nextAfterId").asLong()).isEqualTo(19L);
    verify(teamService).findCurrentUserTeams();
    verify(teamService, never()).findAll(anyBoolean());
  }

  @Test
  public void adminTeamDiscoveryUsesEnabledTeams() {
    authenticate("ROLE_ADMIN");
    when(teamService.isCurrentUserAdmin()).thenReturn(true);
    when(teamService.findAll(false)).thenReturn(List.of(team(3, "Team")));

    var result =
        new ListAgentReviewTeamsMcpTool(mapper, teamService).call(mapper.createObjectNode());

    assertThat(result.structuredContent().at("/teams/0/name").asText()).isEqualTo("Team");
    verify(teamService).findAll(false);
  }

  @Test
  public void runDiscoveryPreservesCursorWhenScannedPageHasNoAccessibleRuns() {
    var summaryJson = mapper.valueToTree(runView(0, new Checkpoint(Map.of())));
    ((com.fasterxml.jackson.databind.node.ObjectNode) summaryJson).remove("checkpoint");
    RunSummary summary = mapper.convertValue(summaryJson, RunSummary.class);
    when(service.listRunsPage(2L, 2, 50L)).thenReturn(new RunPage(List.of(), 48L));
    when(service.listRunsPage(2L, 2, 48L)).thenReturn(new RunPage(List.of(summary), null));
    var tool = new ListAgentReviewRunsMcpTool(mapper, service);

    var emptyPage = tool.call(mapper.valueToTree(new ListAgentReviewRunsMcpTool.Input(2L, 2, 50L)));
    var nextPage =
        tool.call(
            mapper.valueToTree(
                new ListAgentReviewRunsMcpTool.Input(
                    2L, 2, emptyPage.structuredContent().path("nextBeforeId").asLong())));

    assertThat(emptyPage.structuredContent().path("runs").isEmpty()).isTrue();
    assertThat(emptyPage.structuredContent().path("nextBeforeId").asLong()).isEqualTo(48L);
    assertThat(nextPage.structuredContent().at("/runs/0/id").asLong()).isEqualTo(17L);
    assertThat(nextPage.structuredContent().at("/runs/0/checkpointSha256").asText())
        .isEqualTo("a".repeat(64));
    assertThat(nextPage.structuredContent().at("/runs/0").has("checkpoint")).isFalse();
    assertThat(nextPage.structuredContent().path("nextBeforeId").isNull()).isTrue();
    verifyNoInteractions(projectService);
  }

  @Test
  public void runDiscoveryDefaultsToFirstPageAndRejectsInvalidCursor() {
    when(service.listRunsPage(2L, 50, null)).thenReturn(new RunPage(List.of(), null));
    var tool = new ListAgentReviewRunsMcpTool(mapper, service);

    tool.call(mapper.createObjectNode().put("teamId", 2));
    verify(service).listRunsPage(2L, 50, null);
    assertThatThrownBy(
            () -> tool.call(mapper.valueToTree(new ListAgentReviewRunsMcpTool.Input(2L, 50, 0L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beforeId");
  }

  @Test
  public void pendingFeedbackIsReadOnlyAndKeepsThePaginationCursor() {
    AgentReviewFeedback feedback = new AgentReviewFeedback();
    feedback.setId(21L);
    feedback.setProposalId(11L);
    feedback.setAction(FeedbackAction.KEEP_CURRENT);
    feedback.setFollowUpRequested(true);
    when(service.pendingFeedback(17, 20, 1)).thenReturn(List.of(feedback));

    var result =
        new PendingAgentReviewFeedbackMcpTool(mapper, service)
            .call(mapper.valueToTree(new PendingAgentReviewFeedbackMcpTool.Input(17L, 20L, 1)));

    assertThat(result.structuredContent().at("/feedback/0/proposalId").asLong()).isEqualTo(11L);
    assertThat(result.structuredContent().path("nextAfterId").asLong()).isEqualTo(21L);
    verify(service).pendingFeedback(17, 20, 1);
    verifyNoInteractions(projectService);
  }

  @Test
  public void challengePreservesFeedbackIdentityAndCannotRouteOrApply() {
    ResponseRequest response =
        new ResponseRequest(
            claim,
            "reply-31",
            31L,
            FeedbackAction.CHALLENGE,
            "verifier",
            "The glossary scopes this term to a different feature.",
            "{\"glossaryId\":3}");
    AgentReviewFeedback saved = new AgentReviewFeedback();
    saved.setId(32L);
    saved.setRespondsToFeedbackId(31L);
    saved.setAction(FeedbackAction.CHALLENGE);
    when(service.respondToFeedback(17, response)).thenReturn(saved);

    var result =
        new RespondToAgentReviewFeedbackMcpTool(mapper, service)
            .call(mapper.valueToTree(new RespondToAgentReviewFeedbackMcpTool.Input(17L, response)));

    assertThat(result.structuredContent().path("respondsToFeedbackId").asLong()).isEqualTo(31L);
    verify(service).respondToFeedback(17, response);
    verifyNoInteractions(projectService);
  }

  @Test
  public void invalidPaginationFailsBeforeRunLookup() {
    var tool = new ListAgentReviewProposalsMcpTool(mapper, service);

    assertThatThrownBy(
            () ->
                tool.call(
                    mapper.valueToTree(new ListAgentReviewProposalsMcpTool.Input(17L, -1L, 50))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("afterId");
    assertThatThrownBy(
            () ->
                tool.call(
                    mapper.valueToTree(new ListAgentReviewProposalsMcpTool.Input(17L, 0L, 201))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
    verifyNoInteractions(service);
  }

  private List<McpToolHandler> tools() {
    return List.of(
        new ListAgentReviewTeamsMcpTool(mapper, teamService),
        new CreateAgentReviewRunMcpTool(mapper, service),
        new ListAgentReviewRunsMcpTool(mapper, service),
        new InspectAgentReviewRunMcpTool(mapper, service),
        new ClaimAgentReviewRunMcpTool(mapper, service, projectService),
        new CheckpointAgentReviewRunMcpTool(mapper, service, projectService),
        new UploadAgentReviewArtifactMcpTool(mapper, service),
        new ReadAgentReviewArtifactMcpTool(mapper, service),
        new SubmitAgentReviewProposalsMcpTool(mapper, service),
        new FinishAgentReviewRunMcpTool(mapper, service, projectService),
        new RouteAgentReviewRunMcpTool(mapper, projectService),
        new ListAgentReviewProposalsMcpTool(mapper, service),
        new AgentReviewProposalHistoryMcpTool(mapper, service),
        new PendingAgentReviewFeedbackMcpTool(mapper, service),
        new RespondToAgentReviewFeedbackMcpTool(mapper, service),
        new AgentReviewFeedbackHistoryMcpTool(mapper, service));
  }

  private SubmitProposalRequest proposal(String key, String old, String replacement) {
    return new SubmitProposalRequest(
        claim,
        key,
        "fr/settings",
        7L,
        "  Source\n",
        null,
        9L,
        old,
        "APPROVED",
        true,
        replacement,
        Category.OBVIOUS_ERROR,
        Readiness.READY,
        "The target reverses the action.",
        "{\"screen\":\"settings\"}",
        "locale-worker",
        "verifier",
        "Checked against the action's runtime context.",
        null,
        null,
        null);
  }

  private RunView runView(long revision, Checkpoint checkpoint) {
    return new RunView(
        17L,
        "TRANSLATION_QUALITY",
        2L,
        List.of(1L),
        List.of(4L),
        "rubric-1",
        "config-1",
        "b".repeat(64),
        "c".repeat(64),
        RunStatus.RUNNING,
        revision,
        "coordinator",
        3,
        null,
        1,
        1,
        0,
        2,
        "a".repeat(64),
        checkpoint,
        7,
        1500,
        true,
        null,
        null);
  }

  private Team team(long id, String name) {
    Team team = new Team();
    team.setId(id);
    team.setName(name);
    return team;
  }

  private static void authenticate(String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("operator", "ignored", role));
  }
}
