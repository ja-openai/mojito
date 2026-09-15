package com.box.l10n.mojito.service.agentreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.agentreview.Disposition;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class AgentReviewIntakeIdentityTest {
  @Test
  public void identityNormalizesOnlyStableWhitespaceAndPreservesConcernScopeAndExactState() {
    String one = AgentReviewStateFingerprint.finding(1L, null, "state", null, " Wrong  meaning ");
    assertThat(one)
        .isEqualTo(
            AgentReviewStateFingerprint.finding(
                1L, "TRANSLATION_QUALITY", "state", null, "Wrong meaning"));
    assertThat(one)
        .isNotEqualTo(
            AgentReviewStateFingerprint.finding(1L, null, "state", null, "Spelling error"));
    assertThat(one)
        .isNotEqualTo(
            AgentReviewStateFingerprint.finding(2L, null, "state", null, "Wrong meaning"));
    assertThat(one)
        .isNotEqualTo(
            AgentReviewStateFingerprint.finding(1L, "OTHER", "state", null, "Wrong meaning"));
    assertThat(one)
        .isNotEqualTo(
            AgentReviewStateFingerprint.finding(1L, null, "changed", null, "Wrong meaning"));
    assertThat(AgentReviewStateFingerprint.finding(1L, null, "state", "rule-one", "first wording"))
        .isEqualTo(
            AgentReviewStateFingerprint.finding(
                1L, null, "state", "rule-one", "different wording"));
  }

  @Test
  public void decisionsReleaseActiveIdentityButRetainImmutableHistoryKey() {
    var proposal = new AgentReviewProposal();
    proposal.setDisposition(Disposition.OPEN);
    proposal.setIntakeFingerprint("key");
    proposal.setDisposition(Disposition.FOLLOW_UP);
    assertThat(proposal.getActiveIntakeFingerprint()).isEqualTo("key");
    proposal.setDisposition(Disposition.RESOLVED);
    assertThat(proposal.getActiveIntakeFingerprint()).isNull();
    assertThat(proposal.getIntakeFingerprint()).isEqualTo("key");
    var incident = new TranslationIncident();
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setIntakeFingerprint("key");
    incident.setStatus(TranslationIncidentStatus.CLOSED);
    assertThat(incident.getActiveIntakeFingerprint()).isNull();
    assertThat(incident.getIntakeFingerprint()).isEqualTo("key");
    incident.setStatus(TranslationIncidentStatus.OPEN);
    assertThat(incident.getActiveIntakeFingerprint()).isEqualTo("key");
  }

  @Test
  public void bulkReceiptLookupDeduplicatesAndChunksWithoutGlobalScope() {
    var repository = mock(AgentReviewFeedbackRepository.class);
    var service = new AgentReviewStateService(repository);
    assertThat(service.reviewed(null, null, List.of("state"))).isEmpty();
    verifyNoInteractions(repository);
    when(repository.findReviewedStates(eq(7L), eq("TRANSLATION_QUALITY"), any()))
        .thenReturn(Set.of("reviewed"));
    var states = java.util.stream.IntStream.range(0, 1001).mapToObj(i -> "state-" + i).toList();
    assertThat(service.reviewed(7L, null, states)).containsExactly("reviewed");
    verify(repository, times(3))
        .findReviewedStates(
            eq(7L), eq("TRANSLATION_QUALITY"), argThat(values -> values.size() <= 500));
  }
}
