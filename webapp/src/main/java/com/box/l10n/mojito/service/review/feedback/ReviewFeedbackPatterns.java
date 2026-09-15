package com.box.l10n.mojito.service.review.feedback;

import com.box.l10n.mojito.entity.review.ReviewFeedbackEvent;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded, asynchronous observation window. Candidate rules never modify prompts. */
@Service
public class ReviewFeedbackPatterns {
  public static final int WINDOW = 2000;
  private final ReviewFeedbackEventRepository events;
  private volatile Report report = new Report(null, 0, null, null, List.of());

  public ReviewFeedbackPatterns(ReviewFeedbackEventRepository events) {
    this.events = events;
  }

  public record Example(
      Long eventId, String source, String baseline, String accepted, String reason) {}

  public record PatternEvidence(
      String locale,
      Long projectId,
      String model,
      String promptVersion,
      String category,
      String transformHash,
      int observations,
      int distinctStrings,
      int reviewerCount,
      int opportunities,
      double correctionRate,
      int disputedStrings,
      String instanceSignificance,
      String patternSignificance,
      String candidateStatus,
      String transform,
      int distinctSources,
      List<Example> examples) {}

  public record Report(
      Instant computedAt,
      int windowSize,
      Long oldestEventId,
      Long newestEventId,
      List<PatternEvidence> patterns) {}

  public Report report() {
    return report;
  }

  @Scheduled(
      initialDelayString = "${l10n.review-feedback.initial-delay-ms:30000}",
      fixedDelayString = "${l10n.review-feedback.refresh-ms:60000}")
  public void refresh() {
    List<ReviewFeedbackEvent> window =
        events.findByAiBaselineTrueOrderByIdDesc(PageRequest.of(0, WINDOW));
    report = aggregate(window);
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private static com.fasterxml.jackson.databind.JsonNode payload(ReviewFeedbackEvent event) {
    try {
      return MAPPER.readTree(event.getPayload());
    } catch (java.io.IOException e) {
      return MAPPER.createObjectNode();
    }
  }

  private static Example example(ReviewFeedbackEvent event) {
    var p = payload(event);
    return new Example(
        event.getId(),
        snippet(p.path("source").asText()),
        snippet(p.at("/baseline/target").asText()),
        snippet(p.path("finalAcceptedRaw").asText()),
        p.at("/feedback/reason").asText(""));
  }

  private static String snippet(String text) {
    return text.length() > 500 ? text.substring(0, 500) + "…" : text;
  }

  public static Report aggregate(List<ReviewFeedbackEvent> events) {
    record Cohort(String locale, Long project, String model, String prompt) {}
    record Key(Cohort cohort, String category, String transform) {}
    record Version(Long stringId, String baselineHash) {}
    record ReviewerVersion(Cohort cohort, Version version, Long reviewer) {}
    Set<ReviewerVersion> seen = new HashSet<>();
    Map<Cohort, Set<Long>> opportunities = new LinkedHashMap<>();
    Map<Key, List<ReviewFeedbackEvent>> groups = new LinkedHashMap<>();
    Map<Cohort, Map<Version, Set<String>>> outcomes = new HashMap<>();
    for (var event : events) {
      var cohort =
          new Cohort(
              event.getLocale(), event.getProjectId(), event.getModel(), event.getPromptVersion());
      var version = new Version(event.getTextUnitId(), event.getBaselineHash());
      // Input is newest-first. A reviewer's later correction replaces their earlier vote in
      // this observation window; the immutable events themselves are never rewritten.
      if (!seen.add(new ReviewerVersion(cohort, version, event.getReviewerId()))) continue;
      opportunities.computeIfAbsent(cohort, k -> new HashSet<>()).add(event.getTextUnitId());
      outcomes
          .computeIfAbsent(cohort, k -> new HashMap<>())
          .computeIfAbsent(version, k -> new HashSet<>())
          .add(event.getFinalHash());
      if (!event.getCategory().equals("UNCHANGED"))
        groups
            .computeIfAbsent(
                new Key(cohort, event.getCategory(), event.getPatternKey()), k -> new ArrayList<>())
            .add(event);
    }
    List<PatternEvidence> patterns = new ArrayList<>();
    groups.forEach(
        (key, group) -> {
          Set<Long> strings = new HashSet<>(), reviewers = new HashSet<>();
          group.forEach(
              e -> {
                strings.add(e.getTextUnitId());
                reviewers.add(e.getReviewerId());
              });
          int denominator = opportunities.get(key.cohort()).size();
          int disputed =
              (int)
                  group.stream()
                      .map(e -> new Version(e.getTextUnitId(), e.getBaselineHash()))
                      .distinct()
                      .filter(version -> outcomes.get(key.cohort()).get(version).size() > 1)
                      .map(Version::stringId)
                      .distinct()
                      .count();
          double rate = (double) strings.size() / Math.max(1, denominator);
          boolean candidate =
              strings.size() >= 10 && reviewers.size() >= 3 && rate >= 0.2 && disputed == 0;
          var c = key.cohort();
          patterns.add(
              new PatternEvidence(
                  c.locale(),
                  c.project(),
                  c.model(),
                  c.prompt(),
                  key.category(),
                  key.transform(),
                  group.size(),
                  strings.size(),
                  reviewers.size(),
                  denominator,
                  rate,
                  disputed,
                  Set.of(
                              "QUOTE_STYLE",
                              "WHITESPACE",
                              "NORMALIZATION",
                              "CASING",
                              "PUNCTUATION",
                              "STYLE")
                          .contains(key.category())
                      ? "LOW"
                      : "NEEDS_REVIEW",
                  candidate ? "REPEATED" : "INSUFFICIENT_EVIDENCE",
                  candidate ? "READY_FOR_HUMAN_REVIEW" : "OBSERVE",
                  payload(group.get(0)).path("transform").asText(""),
                  (int) group.stream().map(ReviewFeedbackEvent::getSourceHash).distinct().count(),
                  group.stream()
                      .collect(
                          java.util.stream.Collectors.toMap(
                              ReviewFeedbackEvent::getTextUnitId,
                              e -> e,
                              (a, b) -> a,
                              LinkedHashMap::new))
                      .values()
                      .stream()
                      .limit(3)
                      .map(ReviewFeedbackPatterns::example)
                      .toList()));
        });
    patterns.sort(Comparator.comparingInt(PatternEvidence::distinctStrings).reversed());
    return new Report(
        Instant.now(),
        events.size(),
        events.stream()
            .map(ReviewFeedbackEvent::getId)
            .filter(Objects::nonNull)
            .min(Long::compare)
            .orElse(null),
        events.stream()
            .map(ReviewFeedbackEvent::getId)
            .filter(Objects::nonNull)
            .max(Long::compare)
            .orElse(null),
        patterns);
  }
}
