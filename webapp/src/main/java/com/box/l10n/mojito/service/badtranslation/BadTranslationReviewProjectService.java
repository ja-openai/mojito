package com.box.l10n.mojito.service.badtranslation;

import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class BadTranslationReviewProjectService {

  public enum MatchConfidence {
    HIGH,
    MEDIUM,
    LOW
  }

  private final ReviewProjectTextUnitRepository reviewProjectTextUnitRepository;
  private final ServerConfig serverConfig;

  public BadTranslationReviewProjectService(
      ReviewProjectTextUnitRepository reviewProjectTextUnitRepository, ServerConfig serverConfig) {
    this.reviewProjectTextUnitRepository = Objects.requireNonNull(reviewProjectTextUnitRepository);
    this.serverConfig = Objects.requireNonNull(serverConfig);
  }

  public List<BadTranslationReviewProjectCandidate> findReviewProjectCandidates(
      BadTranslationLookupService.TranslationCandidate candidate,
      BadTranslationLookupService.LocaleRef locale) {
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(locale);

    return reviewProjectTextUnitRepository
        .findBadTranslationReviewProjectMatches(candidate.tmTextUnitId(), locale.id())
        .stream()
        .map(row -> toCandidate(row, candidate))
        .filter(reviewProjectCandidate -> reviewProjectCandidate.confidenceScore() > 0)
        .sorted(
            java.util.Comparator.comparingInt(BadTranslationReviewProjectCandidate::confidenceScore)
                .reversed()
                .thenComparing(
                    BadTranslationReviewProjectCandidate::decisionLastModifiedDate,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(
                    BadTranslationReviewProjectCandidate::reviewProjectCreatedDate,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(BadTranslationReviewProjectCandidate::reviewProjectId))
        .toList();
  }

  private BadTranslationReviewProjectCandidate toCandidate(
      BadTranslationReviewProjectMatchRow row,
      BadTranslationLookupService.TranslationCandidate translationCandidate) {
    int score = 0;
    List<String> reasons = new ArrayList<>();

    if (equalsId(row.reviewedVariantId(), translationCandidate.tmTextUnitVariantId())) {
      score += 90;
      reasons.add("Decision history reviewed the same translation variant");
    }

    if (equalsId(row.baselineVariantId(), translationCandidate.tmTextUnitVariantId())) {
      score += 75;
      reasons.add("Review project baseline variant matches the bad translation");
    }

    if (equalsId(row.currentVariantId(), translationCandidate.tmTextUnitVariantId())) {
      score += 65;
      reasons.add("Review project locale current variant matches the bad translation");
    }

    if (equalsId(row.decisionVariantId(), translationCandidate.tmTextUnitVariantId())) {
      score += 50;
      reasons.add("Review decision variant matches the bad translation");
    }

    if (matchesContent(row.baselineVariantContent(), translationCandidate.target())
        || matchesContent(row.currentVariantContent(), translationCandidate.target())
        || matchesContent(row.decisionVariantContent(), translationCandidate.target())) {
      score += 20;
      reasons.add("Translation text matches content seen in the review project");
    }

    if (row.decisionLastModifiedDate() != null) {
      score += 10;
      reasons.add("Review project has recorded decision activity");
    }

    if (score == 0) {
      return new BadTranslationReviewProjectCandidate(
          row.reviewProjectId(),
          row.reviewProjectStatus() == null ? null : row.reviewProjectStatus().name(),
          row.reviewProjectCreatedDate(),
          row.reviewProjectDueDate(),
          row.decisionLastModifiedDate(),
          buildReviewProjectLink(row.reviewProjectId()),
          row.reviewProjectRequestId(),
          row.reviewProjectRequestName(),
          buildReviewProjectRequestLink(row.reviewProjectRequestId()),
          row.teamId(),
          row.teamName(),
          person(
              row.reviewProjectRequestCreatedByUserId(),
              row.reviewProjectRequestCreatedByUsername()),
          person(row.assignedPmUserId(), row.assignedPmUsername()),
          person(row.assignedTranslatorUserId(), row.assignedTranslatorUsername()),
          person(row.decisionLastModifiedByUserId(), row.decisionLastModifiedByUsername()),
          MatchConfidence.LOW.name(),
          0,
          List.of());
    }

    return new BadTranslationReviewProjectCandidate(
        row.reviewProjectId(),
        row.reviewProjectStatus() == null ? null : row.reviewProjectStatus().name(),
        row.reviewProjectCreatedDate(),
        row.reviewProjectDueDate(),
        row.decisionLastModifiedDate(),
        buildReviewProjectLink(row.reviewProjectId()),
        row.reviewProjectRequestId(),
        row.reviewProjectRequestName(),
        buildReviewProjectRequestLink(row.reviewProjectRequestId()),
        row.teamId(),
        row.teamName(),
        person(
            row.reviewProjectRequestCreatedByUserId(), row.reviewProjectRequestCreatedByUsername()),
        person(row.assignedPmUserId(), row.assignedPmUsername()),
        person(row.assignedTranslatorUserId(), row.assignedTranslatorUsername()),
        person(row.decisionLastModifiedByUserId(), row.decisionLastModifiedByUsername()),
        score >= 75
            ? MatchConfidence.HIGH.name()
            : score >= 35 ? MatchConfidence.MEDIUM.name() : MatchConfidence.LOW.name(),
        score,
        List.copyOf(reasons));
  }

  private BadTranslationPersonRef person(Long userId, String username) {
    return new BadTranslationPersonRef(userId, username, null, null);
  }

  private boolean equalsId(Long left, Long right) {
    return left != null && right != null && left.equals(right);
  }

  private boolean matchesContent(String candidateContent, String translationTarget) {
    return candidateContent != null
        && translationTarget != null
        && candidateContent.trim().equals(translationTarget.trim());
  }

  private String buildReviewProjectLink(Long projectId) {
    String baseUrl = normalizeServerBaseUrl();
    if (projectId == null || baseUrl == null) {
      return null;
    }
    return baseUrl + "/review-projects/" + projectId;
  }

  private String buildReviewProjectRequestLink(Long requestId) {
    String baseUrl = normalizeServerBaseUrl();
    if (requestId == null || baseUrl == null) {
      return null;
    }
    return baseUrl + "/review-projects?requestId=" + requestId;
  }

  private String normalizeServerBaseUrl() {
    String configured = serverConfig.getUrl();
    if (configured == null || configured.isBlank()) {
      return null;
    }

    String trimmed = configured.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isBlank() ? null : trimmed;
  }
}
