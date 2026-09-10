package com.box.l10n.mojito.service.agentreview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TeamUserRole;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Artifact;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.team.TeamUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

public class AgentReviewEvidenceServiceTest {
  private static final String HASH = "a".repeat(64);
  private final ReviewProjectService reviewProjects =
      mock(ReviewProjectService.class, CALLS_REAL_METHODS);
  private final ReviewProjectRepository projects = mock(ReviewProjectRepository.class);
  private final ReviewProjectTextUnitRepository rows = mock(ReviewProjectTextUnitRepository.class);
  private final AgentReviewProposalRepository proposals = mock(AgentReviewProposalRepository.class);
  private final AgentReviewService reviews = mock(AgentReviewService.class);
  private final UserService userService = mock(UserService.class);
  private final UserRepository users = mock(UserRepository.class);
  private final TeamService teams = mock(TeamService.class);
  private final TeamUserRepository teamUsers = mock(TeamUserRepository.class);
  private final ReviewProject project = new ReviewProject();
  private final ReviewProjectTextUnit row = new ReviewProjectTextUnit();
  private final AgentReviewProposal proposal = new AgentReviewProposal();
  private final Artifact artifact = new Artifact(HASH, "image/png", "cG5n", 3);
  private AgentReviewEvidenceService service;

  @Before
  public void setup() {
    // Exercise the existing project policy with an assigned translator, without granting run
    // access.
    ReflectionTestUtils.setField(reviewProjects, "userService", userService);
    ReflectionTestUtils.setField(reviewProjects, "userRepository", users);
    ReflectionTestUtils.setField(reviewProjects, "teamService", teams);
    ReflectionTestUtils.setField(reviewProjects, "teamUserRepository", teamUsers);
    User translator = new User();
    translator.setId(5L);
    translator.setCanTranslateAllLocales(false);
    when(teams.getCurrentUserIdOrThrow()).thenReturn(5L);
    when(userService.isCurrentUserTranslator()).thenReturn(true);
    when(users.findById(5L)).thenReturn(Optional.of(translator));
    when(teamUsers.findByUserIdAndRole(5L, TeamUserRole.TRANSLATOR)).thenReturn(List.of());
    when(reviews.getRun(30L)).thenThrow(new AccessDeniedException("Manager access required"));

    Locale locale = new Locale();
    locale.setId(6L);
    project.setId(10L);
    project.setAgentReviewRunId(30L);
    project.setAssignedTranslatorUser(translator);
    project.setLocale(locale);
    when(projects.findById(10L)).thenReturn(Optional.of(project));
    TMTextUnit unit = new TMTextUnit();
    unit.setId(7L);
    row.setId(11L);
    row.setReviewProject(project);
    row.setTmTextUnit(unit);
    when(rows.findById(11L)).thenReturn(Optional.of(row));
    proposal.setId(20L);
    proposal.setRunId(30L);
    proposal.setReviewProjectId(10L);
    proposal.setReviewProjectTextUnitId(11L);
    proposal.setTmTextUnitId(7L);
    proposal.setLocaleId(6L);
    proposal.setEvidenceJson("[{\"label\":\"Screenshot\",\"artifactSha256\":\"" + HASH + "\"}]");
    when(proposals.findById(20L)).thenReturn(Optional.of(proposal));
    when(reviews.readStoredArtifact(30L, HASH)).thenReturn(artifact);
    service =
        new AgentReviewEvidenceService(
            reviewProjects, projects, rows, proposals, reviews, new ObjectMapper());
  }

  @Test
  public void assignedTranslatorCanReadAnExplicitProposalArtifactWithoutRunAccess() {
    assertSame(artifact, service.read(10L, 20L, HASH));
    verify(reviewProjects).assertCurrentUserCanReadProject(project);
    verify(reviews).readStoredArtifact(30L, HASH);
  }

  @Test
  public void wrappedEvidenceUsesTheProposalRunEvenWhenAnItemNamesAnotherRun() {
    proposal.setEvidenceJson(
        "{\"items\":[\"context\",{\"artifactSha256\":\"" + HASH + "\",\"runId\":999}]}");
    assertSame(artifact, service.read(10L, 20L, HASH));
    verify(reviews).readStoredArtifact(30L, HASH);
  }

  @Test
  public void anUnassignedOutsiderCannotReadOrDiscoverProposalEvidence() {
    User otherTranslator = new User();
    otherTranslator.setId(99L);
    project.setAssignedTranslatorUser(otherTranslator);
    assertThrows(AccessDeniedException.class, () -> service.read(10L, 20L, HASH));
    verifyNoInteractions(proposals, rows, reviews);
  }

  @Test
  public void anUnreferencedRunArtifactCannotBeReadEvenByTheAssignedTranslator() {
    assertNotFound("b".repeat(64));
    verifyNoInteractions(reviews);
  }

  @Test
  public void anArtifactReferenceFromAnotherProjectDoesNotGrantAccess() {
    proposal.setReviewProjectId(99L);
    assertNotFound(HASH);
    verifyNoInteractions(rows, reviews);
  }

  @Test
  public void aDeletedOrRemappedProjectRowDoesNotGrantAccess() {
    when(rows.findById(11L)).thenReturn(Optional.empty());
    assertNotFound(HASH);
    when(rows.findById(11L)).thenReturn(Optional.of(row));
    TMTextUnit anotherUnit = new TMTextUnit();
    anotherUnit.setId(99L);
    row.setTmTextUnit(anotherUnit);
    assertNotFound(HASH);
    verifyNoInteractions(reviews);
  }

  @Test
  public void hashesInFreeTextOrNestedObjectsDoNotGrantArtifactAccess() {
    for (String evidence :
        List.of(
            "[\"" + HASH + "\"]",
            "[{\"label\":\"" + HASH + "\"}]",
            "[{\"nested\":{\"artifactSha256\":\"" + HASH + "\"}}]",
            "{\"artifactSha256\":\"" + HASH + "\"}",
            "{\"items\":null}",
            "null",
            "not JSON")) {
      proposal.setEvidenceJson(evidence);
      assertNotFound(HASH);
    }
    verifyNoInteractions(reviews);
  }

  @Test
  public void storageFailuresRemainVisibleInsteadOfReturningEmptyEvidence() {
    ResponseStatusException unavailable =
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Storage unavailable");
    when(reviews.readStoredArtifact(30L, HASH)).thenThrow(unavailable);
    assertSame(
        unavailable,
        assertThrows(ResponseStatusException.class, () -> service.read(10L, 20L, HASH)));
  }

  private void assertNotFound(String sha256) {
    assertEquals(
        HttpStatus.NOT_FOUND,
        assertThrows(ResponseStatusException.class, () -> service.read(10L, 20L, sha256))
            .getStatusCode());
  }
}
