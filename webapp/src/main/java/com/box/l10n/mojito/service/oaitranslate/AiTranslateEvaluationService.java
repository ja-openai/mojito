package com.box.l10n.mojito.service.oaitranslate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTranslateEvaluationService {

  public static final int DEFAULT_LIMIT = 200;
  public static final int MAX_LIMIT = 1000;
  private static final long MAX_EDIT_DISTANCE_OPERATIONS = 2_000_000L;

  private final AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository;

  public AiTranslateEvaluationService(
      AiTranslateTextUnitAttemptRepository aiTranslateTextUnitAttemptRepository) {
    this.aiTranslateTextUnitAttemptRepository = aiTranslateTextUnitAttemptRepository;
  }

  public record EvaluationExample(
      Long attemptId,
      ZonedDateTime reviewedAt,
      Long reviewProjectId,
      Long repositoryId,
      String repositoryName,
      Long tmTextUnitId,
      String textUnitName,
      String source,
      String sourceDescription,
      String localeTag,
      String model,
      String promptFingerprint,
      String reasoningEffort,
      String textVerbosity,
      String aiTarget,
      String acceptedTarget,
      String decisionNotes,
      boolean exactAccepted,
      Double normalizedEditDistance) {}

  public record EvaluationSummary(
      int reviewedCount,
      int exactAcceptedCount,
      int editedCount,
      double exactAcceptanceRate,
      double averageNormalizedEditDistance) {}

  public record EvaluationCohort(
      String promptFingerprint,
      String model,
      String reasoningEffort,
      String textVerbosity,
      String localeTag,
      EvaluationSummary summary) {}

  public record EvaluationReport(
      EvaluationSummary summary,
      List<EvaluationCohort> cohorts,
      List<EvaluationExample> examples) {}

  @Transactional(readOnly = true)
  public EvaluationReport getReport(
      Long repositoryId, String localeTag, String model, Integer requestedLimit) {
    int limit = validateLimit(requestedLimit);
    String normalizedLocaleTag = normalizeFilter(localeTag);
    String normalizedModel = normalizeFilter(model);
    int queryLimit = Math.min(MAX_LIMIT, limit * 3);
    Map<Long, AiTranslateEvaluationRow> rowByDecision = new LinkedHashMap<>();
    aiTranslateTextUnitAttemptRepository
        .findEvaluationRows(
            repositoryId, normalizedLocaleTag, normalizedModel, PageRequest.of(0, queryLimit))
        .forEach(row -> rowByDecision.putIfAbsent(row.decisionId(), row));
    List<EvaluationExample> examples =
        rowByDecision.values().stream().limit(limit).map(this::toExample).toList();

    Map<CohortKey, List<EvaluationExample>> examplesByCohort = new LinkedHashMap<>();
    examples.forEach(
        example ->
            examplesByCohort
                .computeIfAbsent(CohortKey.from(example), ignored -> new ArrayList<>())
                .add(example));

    List<EvaluationCohort> cohorts =
        examplesByCohort.entrySet().stream()
            .map(
                entry ->
                    new EvaluationCohort(
                        entry.getKey().promptFingerprint(),
                        entry.getKey().model(),
                        entry.getKey().reasoningEffort(),
                        entry.getKey().textVerbosity(),
                        entry.getKey().localeTag(),
                        summarize(entry.getValue())))
            .toList();

    return new EvaluationReport(summarize(examples), cohorts, examples);
  }

  private EvaluationExample toExample(AiTranslateEvaluationRow row) {
    boolean exactAccepted = Objects.equals(row.aiTarget(), row.acceptedTarget());
    return new EvaluationExample(
        row.attemptId(),
        row.reviewedAt(),
        row.reviewProjectId(),
        row.repositoryId(),
        row.repositoryName(),
        row.tmTextUnitId(),
        row.textUnitName(),
        row.source(),
        row.sourceDescription(),
        row.localeTag(),
        row.model(),
        row.promptFingerprint(),
        row.reasoningEffort(),
        row.textVerbosity(),
        row.aiTarget(),
        row.acceptedTarget(),
        row.decisionNotes(),
        exactAccepted,
        normalizedEditDistance(row.aiTarget(), row.acceptedTarget()));
  }

  private EvaluationSummary summarize(List<EvaluationExample> examples) {
    int reviewedCount = examples.size();
    int exactAcceptedCount =
        (int) examples.stream().filter(EvaluationExample::exactAccepted).count();
    int editedCount = reviewedCount - exactAcceptedCount;
    double exactAcceptanceRate =
        reviewedCount == 0 ? 0.0 : (double) exactAcceptedCount / reviewedCount;
    double averageNormalizedEditDistance =
        examples.stream()
            .map(EvaluationExample::normalizedEditDistance)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    return new EvaluationSummary(
        reviewedCount,
        exactAcceptedCount,
        editedCount,
        exactAcceptanceRate,
        averageNormalizedEditDistance);
  }

  private int validateLimit(Integer requestedLimit) {
    if (requestedLimit == null) {
      return DEFAULT_LIMIT;
    }
    if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between 1 and " + MAX_LIMIT + ", got: " + requestedLimit);
    }
    return requestedLimit;
  }

  private String normalizeFilter(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  static Double normalizedEditDistance(String first, String second) {
    if (first == null || second == null) {
      return null;
    }
    if (first.equals(second)) {
      return 0.0;
    }
    int maxLength = Math.max(first.length(), second.length());
    if (maxLength == 0) {
      return 0.0;
    }
    if ((long) first.length() * second.length() > MAX_EDIT_DISTANCE_OPERATIONS) {
      return null;
    }

    String shorter = first.length() <= second.length() ? first : second;
    String longer = first.length() <= second.length() ? second : first;
    int[] previous = new int[shorter.length() + 1];
    int[] current = new int[shorter.length() + 1];
    for (int index = 0; index <= shorter.length(); index++) {
      previous[index] = index;
    }

    for (int longerIndex = 1; longerIndex <= longer.length(); longerIndex++) {
      current[0] = longerIndex;
      for (int shorterIndex = 1; shorterIndex <= shorter.length(); shorterIndex++) {
        int substitutionCost =
            longer.charAt(longerIndex - 1) == shorter.charAt(shorterIndex - 1) ? 0 : 1;
        current[shorterIndex] =
            Math.min(
                Math.min(current[shorterIndex - 1] + 1, previous[shorterIndex] + 1),
                previous[shorterIndex - 1] + substitutionCost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }

    return (double) previous[shorter.length()] / maxLength;
  }

  private record CohortKey(
      String promptFingerprint,
      String model,
      String reasoningEffort,
      String textVerbosity,
      String localeTag) {
    private static CohortKey from(EvaluationExample example) {
      return new CohortKey(
          example.promptFingerprint(),
          example.model(),
          example.reasoningEffort(),
          example.textVerbosity(),
          example.localeTag());
    }
  }
}
