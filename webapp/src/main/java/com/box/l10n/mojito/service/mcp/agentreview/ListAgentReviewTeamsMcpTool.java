package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.mcp.agentreview.AgentReviewMcpSchemas.*;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.team.TeamService;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ListAgentReviewTeamsMcpTool
    extends AgentReviewMcpTool<ListAgentReviewTeamsMcpTool.Input> {
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "agent_review.list_teams",
          "Find teams for translation review",
          "List enabled teams available for creating review runs: a PM's own managed teams, or all enabled teams for an admin. Returns only id/name. Use a requested team, or the only available team; ask which team when ambiguous. Does not modify team membership or assignment pools.",
          true,
          true,
          List.of(afterId(), pageLimit()));

  private final TeamService teamService;

  public ListAgentReviewTeamsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      TeamService teamService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.teamService = teamService;
  }

  public record Input(Long afterId, Integer limit) {}

  public record TeamOption(Long id, String name) {}

  public record Result(List<TeamOption> teams, Long nextAfterId) {}

  @Override
  protected Object executeAuthorized(Input input) {
    int limit = limit(input.limit());
    long afterId = cursor(input.afterId());
    List<Team> available =
        teamService.isCurrentUserAdmin()
            ? teamService.findAll(false)
            : teamService.findCurrentUserTeams();
    List<TeamOption> teams =
        available.stream()
            .filter(team -> team.getId() > afterId)
            .sorted(Comparator.comparing(Team::getId))
            .limit(limit)
            .map(team -> new TeamOption(team.getId(), team.getName()))
            .toList();
    return new Result(teams, teams.size() == limit ? teams.get(teams.size() - 1).id() : null);
  }
}
