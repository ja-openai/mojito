package com.box.l10n.mojito.service.agentreview;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Exact receipts belong to the review's team and type, never to every tenant. */
@Service
public class AgentReviewStateService {
  private final AgentReviewFeedbackRepository feedback;

  public AgentReviewStateService(AgentReviewFeedbackRepository feedback) {
    this.feedback = feedback;
  }

  public boolean isReviewed(Long teamId, String reviewType, String fingerprint) {
    return fingerprint != null
        && reviewed(teamId, reviewType, List.of(fingerprint)).contains(fingerprint);
  }

  public Set<String> reviewed(Long teamId, String reviewType, Collection<String> fingerprints) {
    if (teamId == null || fingerprints == null || fingerprints.isEmpty()) return Set.of();
    List<String> keys =
        fingerprints.stream().filter(java.util.Objects::nonNull).distinct().toList();
    Set<String> result = new LinkedHashSet<>();
    for (int start = 0; start < keys.size(); start += 500) {
      result.addAll(
          feedback.findReviewedStates(
              teamId,
              AgentReviewStateFingerprint.reviewType(reviewType),
              keys.subList(start, Math.min(start + 500, keys.size()))));
    }
    return result;
  }
}
