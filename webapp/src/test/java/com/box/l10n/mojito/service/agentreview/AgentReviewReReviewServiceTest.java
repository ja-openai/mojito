package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.agentreview.Disposition;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.junit.Test;

public class AgentReviewReReviewServiceTest {
  @Test
  public void decisionChangedWhileWaitingForTranslationLockRejectsTheOldRequest() {
    var proposals = mock(AgentReviewProposalRepository.class);
    var rows = mock(ReviewProjectTextUnitRepository.class);
    var projects = mock(ReviewProjectService.class);
    var entityManager = mock(EntityManager.class);
    var teams = mock(TeamService.class);
    when(teams.getCurrentUserIdOrThrow()).thenReturn(5L);
    var proposal = new AgentReviewProposal();
    proposal.setId(7L);
    proposal.setRunId(1L);
    proposal.setReviewProjectId(8L);
    proposal.setReviewProjectTextUnitId(9L);
    proposal.setTmTextUnitId(10L);
    proposal.setLocaleId(11L);
    proposal.setVersion(2L);
    proposal.setDisposition(Disposition.FOLLOW_UP);
    var row = new ReviewProjectTextUnit();
    row.setReviewProject(new ReviewProject());
    row.setTmTextUnit(new TMTextUnit());
    when(proposals.findById(7L)).thenReturn(Optional.of(proposal));
    when(proposals.findForUpdateById(7L)).thenReturn(Optional.of(proposal));
    when(rows.findById(9L)).thenReturn(Optional.of(row));
    // JPA can return the same managed instance after waiting for its lock. Refresh reveals
    // the intervening decision that the caller's request did not observe.
    doAnswer(
            invocation -> {
              proposal.setVersion(3L);
              proposal.setDisposition(Disposition.RESOLVED);
              return null;
            })
        .when(entityManager)
        .refresh(proposal, LockModeType.PESSIMISTIC_WRITE);
    var service =
        new AgentReviewReReviewService(
            proposals,
            mock(AgentReviewFeedbackRepository.class),
            rows,
            projects,
            mock(TMTextUnitCurrentVariantRepository.class),
            mock(TranslationIncidentRepository.class),
            mock(UserService.class),
            teams,
            entityManager,
            new ObjectMapper());
    var request =
        new AgentReviewReReviewService.Request(
            "retry-key", 2L, null, "Source", null, null, null, null);
    assertThatThrownBy(() -> service.reviewAgain(8L, 7L, request))
        .hasMessageContaining("finding changed");
    verify(projects, never()).createHumanReReviewProject(any(), any());
    verify(proposals, never()).saveAndFlush(any());
  }
}
