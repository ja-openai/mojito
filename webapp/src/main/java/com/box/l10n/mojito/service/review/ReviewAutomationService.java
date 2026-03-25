package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import java.text.ParseException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.quartz.CronExpression;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReviewAutomationService {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;
  public static final int DEFAULT_SCHEDULE_PREVIEW_COUNT = 5;
  public static final int MAX_SCHEDULE_PREVIEW_COUNT = 10;
  public static final int DEFAULT_MAX_WORD_COUNT_PER_PROJECT = 2000;
  public static final String DEFAULT_TIME_ZONE = "UTC";
  public static final int DEFAULT_DUE_DATE_OFFSET_DAYS = 1;

  private final ReviewAutomationRepository reviewAutomationRepository;
  private final ReviewFeatureRepository reviewFeatureRepository;
  private final ReviewAutomationRunRepository reviewAutomationRunRepository;
  private final TeamRepository teamRepository;
  private final UserService userService;
  private final ObjectProvider<ReviewAutomationCronSchedulerService>
      reviewAutomationCronSchedulerServiceProvider;

  public ReviewAutomationService(
      ReviewAutomationRepository reviewAutomationRepository,
      ReviewFeatureRepository reviewFeatureRepository,
      ReviewAutomationRunRepository reviewAutomationRunRepository,
      TeamRepository teamRepository,
      UserService userService,
      ObjectProvider<ReviewAutomationCronSchedulerService>
          reviewAutomationCronSchedulerServiceProvider) {
    this.reviewAutomationRepository = reviewAutomationRepository;
    this.reviewFeatureRepository = reviewFeatureRepository;
    this.reviewAutomationRunRepository = reviewAutomationRunRepository;
    this.teamRepository = teamRepository;
    this.userService = userService;
    this.reviewAutomationCronSchedulerServiceProvider =
        reviewAutomationCronSchedulerServiceProvider;
  }

  @Transactional(readOnly = true)
  public SearchReviewAutomationsView searchReviewAutomations(
      String searchQuery, Boolean enabled, Integer limit) {
    requireAdmin();
    int resolvedLimit = normalizeLimit(limit);
    Page<ReviewAutomationSummaryRow> page =
        reviewAutomationRepository.searchSummaryRows(
            normalizeSearchQuery(searchQuery), enabled, PageRequest.of(0, resolvedLimit));
    List<Long> automationIds =
        page.getContent().stream().map(ReviewAutomationSummaryRow::id).toList();
    return new SearchReviewAutomationsView(
        toSummaryViews(
            page.getContent(),
            loadFeaturesByAutomationId(page.getContent()),
            loadTriggerStatusByAutomationId(automationIds)),
        page.getTotalElements());
  }

  @Transactional(readOnly = true)
  public List<ReviewAutomationOption> getReviewAutomationOptions() {
    requireAdmin();
    return reviewAutomationRepository.findAllOptionRows().stream()
        .map(
            row ->
                new ReviewAutomationOption(
                    row.id(), row.name(), Boolean.TRUE.equals(row.enabled())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ReviewAutomationBatchExportRow> getReviewAutomationBatchExportRows() {
    requireAdmin();
    List<ReviewAutomationOptionRow> optionRows = reviewAutomationRepository.findAllOptionRows();
    if (optionRows.isEmpty()) {
      return List.of();
    }

    List<Long> automationIds = optionRows.stream().map(ReviewAutomationOptionRow::id).toList();
    Map<Long, ReviewAutomation> automationsById =
        reviewAutomationRepository.findAllById(automationIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ReviewAutomation::getId, automation -> automation));

    Map<Long, List<String>> featureNamesByAutomationId = new LinkedHashMap<>();
    for (ReviewAutomationFeatureRow row :
        reviewAutomationRepository.findFeatureRowsByAutomationIds(automationIds)) {
      featureNamesByAutomationId
          .computeIfAbsent(row.reviewAutomationId(), ignored -> new ArrayList<>())
          .add(row.name());
    }

    return optionRows.stream()
        .map(
            row -> {
              ReviewAutomation automation = automationsById.get(row.id());
              return new ReviewAutomationBatchExportRow(
                  row.id(),
                  row.name(),
                  Boolean.TRUE.equals(row.enabled()),
                  automation != null ? automation.getCronExpression() : null,
                  automation != null
                      ? normalizeTimeZone(automation.getTimeZone())
                      : DEFAULT_TIME_ZONE,
                  automation != null && automation.getTeam() != null
                      ? automation.getTeam().getName()
                      : null,
                  normalizeDueDateOffsetDays(
                      automation != null ? automation.getDueDateOffsetDays() : null),
                  normalizeMaxWordCountPerProject(
                      automation != null ? automation.getMaxWordCountPerProject() : null),
                  featureNamesByAutomationId.getOrDefault(row.id(), List.of()));
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public ReviewAutomationDetail getReviewAutomation(Long automationId) {
    requireAdmin();
    ReviewAutomation reviewAutomation =
        reviewAutomationRepository
            .findByIdWithFeatures(automationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review automation not found: " + automationId));
    List<ReviewAutomationDetail.FeatureRef> features =
        reviewAutomation.getFeatures().stream()
            .sorted(
                java.util.Comparator.comparing(
                        ReviewFeature::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ReviewFeature::getId))
            .map(
                feature ->
                    new ReviewAutomationDetail.FeatureRef(feature.getId(), feature.getName()))
            .toList();
    return new ReviewAutomationDetail(
        reviewAutomation.getId(),
        reviewAutomation.getCreatedDate(),
        reviewAutomation.getLastModifiedDate(),
        reviewAutomation.getName(),
        Boolean.TRUE.equals(reviewAutomation.getEnabled()),
        reviewAutomation.getCronExpression(),
        normalizeTimeZone(reviewAutomation.getTimeZone()),
        toTeamRef(reviewAutomation.getTeam()),
        normalizeDueDateOffsetDays(reviewAutomation.getDueDateOffsetDays()),
        normalizeMaxWordCountPerProject(reviewAutomation.getMaxWordCountPerProject()),
        loadTriggerStatusByAutomationId(List.of(reviewAutomation.getId()))
            .getOrDefault(reviewAutomation.getId(), defaultTriggerStatusView()),
        features);
  }

  @Transactional(readOnly = true)
  public ReviewAutomationCronSchedulerService.TriggerHealth repairTrigger(Long automationId) {
    requireAdmin();
    ReviewAutomationCronSchedulerService cronSchedulerService =
        reviewAutomationCronSchedulerServiceProvider.getIfAvailable();
    if (cronSchedulerService == null) {
      throw new IllegalStateException("Review automation scheduler is not available");
    }
    return cronSchedulerService.repairTrigger(automationId);
  }

  @Transactional
  public ReviewAutomationDetail createReviewAutomation(
      String name,
      Boolean enabled,
      String cronExpression,
      String timeZone,
      Long teamId,
      Integer dueDateOffsetDays,
      Integer maxWordCountPerProject,
      List<Long> featureIds) {
    requireAdmin();
    String normalizedName = normalizeName(name);
    ensureNameAvailable(normalizedName, null);
    List<Long> normalizedFeatureIds = normalizeFeatureIds(featureIds);
    ensureFeaturesAvailableToEnabledAutomation(
        enabled == null ? Boolean.TRUE : enabled, normalizedFeatureIds, null);

    ReviewAutomation reviewAutomation = new ReviewAutomation();
    reviewAutomation.setName(normalizedName);
    reviewAutomation.setEnabled(enabled == null ? Boolean.TRUE : enabled);
    reviewAutomation.setCronExpression(normalizeCronExpression(cronExpression));
    reviewAutomation.setTimeZone(normalizeTimeZone(timeZone));
    reviewAutomation.setTeam(resolveTeam(teamId));
    reviewAutomation.setDueDateOffsetDays(normalizeDueDateOffsetDays(dueDateOffsetDays));
    reviewAutomation.setMaxWordCountPerProject(
        normalizeMaxWordCountPerProject(maxWordCountPerProject));
    reviewAutomation.setFeatures(resolveFeatures(normalizedFeatureIds));
    ReviewAutomation saved = reviewAutomationRepository.save(reviewAutomation);
    syncSchedulerAfterCommit();
    return getReviewAutomation(saved.getId());
  }

  @Transactional
  public ReviewAutomationDetail updateReviewAutomation(
      Long automationId,
      String name,
      Boolean enabled,
      String cronExpression,
      String timeZone,
      Long teamId,
      Integer dueDateOffsetDays,
      Integer maxWordCountPerProject,
      List<Long> featureIds) {
    requireAdmin();
    ReviewAutomation reviewAutomation =
        reviewAutomationRepository
            .findByIdWithFeatures(automationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review automation not found: " + automationId));

    String normalizedName = normalizeName(name);
    ensureNameAvailable(normalizedName, reviewAutomation.getId());
    List<Long> normalizedFeatureIds = normalizeFeatureIds(featureIds);
    ensureFeaturesAvailableToEnabledAutomation(
        enabled == null ? Boolean.TRUE : enabled, normalizedFeatureIds, reviewAutomation.getId());

    reviewAutomation.setName(normalizedName);
    reviewAutomation.setEnabled(enabled == null ? Boolean.TRUE : enabled);
    reviewAutomation.setCronExpression(normalizeCronExpression(cronExpression));
    reviewAutomation.setTimeZone(normalizeTimeZone(timeZone));
    reviewAutomation.setTeam(resolveTeam(teamId));
    reviewAutomation.setDueDateOffsetDays(normalizeDueDateOffsetDays(dueDateOffsetDays));
    reviewAutomation.setMaxWordCountPerProject(
        normalizeMaxWordCountPerProject(maxWordCountPerProject));
    reviewAutomation.setFeatures(resolveFeatures(normalizedFeatureIds));
    reviewAutomationRepository.save(reviewAutomation);
    syncSchedulerAfterCommit();
    return getReviewAutomation(reviewAutomation.getId());
  }

  @Transactional
  public void deleteReviewAutomation(Long automationId) {
    requireAdmin();
    ReviewAutomation reviewAutomation =
        reviewAutomationRepository
            .findById(automationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review automation not found: " + automationId));
    reviewAutomationRepository.delete(reviewAutomation);
    syncSchedulerAfterCommit();
  }

  @Transactional
  public BatchUpsertResult batchUpsert(List<BatchUpsertRow> rows) {
    requireAdmin();
    if (rows == null || rows.isEmpty()) {
      return new BatchUpsertResult(0, 0);
    }

    Set<String> seenNames = new LinkedHashSet<>();
    Set<Long> seenIds = new LinkedHashSet<>();
    int createdCount = 0;
    int updatedCount = 0;

    for (BatchUpsertRow row : rows) {
      if (row == null) {
        continue;
      }
      String normalizedName = normalizeName(row.name());
      String normalizedKey = normalizedName.toLowerCase();
      if (!seenNames.add(normalizedKey)) {
        throw new IllegalArgumentException(
            "Duplicate review automation name in batch: " + normalizedName);
      }
      if (row.id() != null && !seenIds.add(row.id())) {
        throw new IllegalArgumentException("Duplicate review automation id in batch: " + row.id());
      }

      ReviewAutomation reviewAutomation;
      if (row.id() != null) {
        reviewAutomation =
            reviewAutomationRepository
                .findByIdWithFeatures(row.id())
                .orElseThrow(
                    () -> new IllegalArgumentException("Review automation not found: " + row.id()));
        ensureNameAvailable(normalizedName, reviewAutomation.getId());
        updatedCount++;
      } else {
        reviewAutomation =
            reviewAutomationRepository.findByNameIgnoreCase(normalizedName).orElse(null);
        if (reviewAutomation == null) {
          reviewAutomation = new ReviewAutomation();
          createdCount++;
        } else {
          updatedCount++;
        }
        ensureNameAvailable(normalizedName, reviewAutomation.getId());
      }

      List<Long> normalizedFeatureIds = normalizeFeatureIds(row.featureIds());
      boolean normalizedEnabled = row.enabled() == null ? Boolean.TRUE : row.enabled();
      ensureFeaturesAvailableToEnabledAutomation(
          normalizedEnabled, normalizedFeatureIds, reviewAutomation.getId());

      reviewAutomation.setName(normalizedName);
      reviewAutomation.setEnabled(normalizedEnabled);
      reviewAutomation.setCronExpression(normalizeCronExpression(row.cronExpression()));
      reviewAutomation.setTimeZone(normalizeTimeZone(row.timeZone()));
      reviewAutomation.setTeam(resolveTeam(row.teamId()));
      reviewAutomation.setDueDateOffsetDays(normalizeDueDateOffsetDays(row.dueDateOffsetDays()));
      reviewAutomation.setMaxWordCountPerProject(
          normalizeMaxWordCountPerProject(row.maxWordCountPerProject()));
      reviewAutomation.setFeatures(resolveFeatures(normalizedFeatureIds));
      reviewAutomationRepository.save(reviewAutomation);
    }

    syncSchedulerAfterCommit();
    return new BatchUpsertResult(createdCount, updatedCount);
  }

  @Transactional(readOnly = true)
  public List<ZonedDateTime> previewSchedule(
      String cronExpression, String timeZone, Integer previewCount) {
    requireAdmin();
    String normalizedCronExpression = normalizeCronExpression(cronExpression);
    String normalizedTimeZone = normalizeTimeZone(timeZone);
    int resolvedPreviewCount = normalizeSchedulePreviewCount(previewCount);
    ZoneId zoneId = ZoneId.of(normalizedTimeZone);

    CronExpression quartzCronExpression;
    try {
      quartzCronExpression = new CronExpression(normalizedCronExpression);
    } catch (ParseException ex) {
      throw new IllegalArgumentException("Invalid cron expression", ex);
    }
    quartzCronExpression.setTimeZone(java.util.TimeZone.getTimeZone(zoneId));

    List<ZonedDateTime> nextRuns = new ArrayList<>();
    Date cursor = Date.from(ZonedDateTime.now(zoneId).toInstant());
    for (int index = 0; index < resolvedPreviewCount; index++) {
      Date nextValidTime = quartzCronExpression.getNextValidTimeAfter(cursor);
      if (nextValidTime == null) {
        break;
      }
      nextRuns.add(ZonedDateTime.ofInstant(nextValidTime.toInstant(), zoneId));
      cursor = nextValidTime;
    }

    return nextRuns;
  }

  private void syncSchedulerAfterCommit() {
    if (reviewAutomationCronSchedulerServiceProvider.getIfAvailable() == null) {
      return;
    }

    Runnable syncRunnable =
        () -> reviewAutomationCronSchedulerServiceProvider.getObject().syncAll();
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      syncRunnable.run();
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            syncRunnable.run();
          }
        });
  }

  private Map<Long, List<SearchReviewAutomationsView.FeatureSummary>> loadFeaturesByAutomationId(
      List<ReviewAutomationSummaryRow> rows) {
    Map<Long, List<SearchReviewAutomationsView.FeatureSummary>> featuresByAutomationId =
        new LinkedHashMap<>();
    if (rows == null || rows.isEmpty()) {
      return featuresByAutomationId;
    }

    List<Long> automationIds =
        rows.stream().map(ReviewAutomationSummaryRow::id).filter(Objects::nonNull).toList();
    for (ReviewAutomationFeatureRow row :
        reviewAutomationRepository.findFeatureRowsByAutomationIds(automationIds)) {
      featuresByAutomationId
          .computeIfAbsent(row.reviewAutomationId(), ignored -> new ArrayList<>())
          .add(new SearchReviewAutomationsView.FeatureSummary(row.reviewFeatureId(), row.name()));
    }
    return featuresByAutomationId;
  }

  private List<SearchReviewAutomationsView.ReviewAutomationSummary> toSummaryViews(
      List<ReviewAutomationSummaryRow> rows,
      Map<Long, List<SearchReviewAutomationsView.FeatureSummary>> featuresByAutomationId,
      Map<Long, ReviewAutomationTriggerStatusView> triggerStatusByAutomationId) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(
            row ->
                new SearchReviewAutomationsView.ReviewAutomationSummary(
                    row.id(),
                    row.createdDate(),
                    row.lastModifiedDate(),
                    row.name(),
                    Boolean.TRUE.equals(row.enabled()),
                    row.cronExpression(),
                    normalizeTimeZone(row.timeZone()),
                    toTeamRef(row.teamId(), row.teamName()),
                    normalizeDueDateOffsetDays(row.dueDateOffsetDays()),
                    normalizeMaxWordCountPerProject(row.maxWordCountPerProject()),
                    triggerStatusByAutomationId.getOrDefault(row.id(), defaultTriggerStatusView()),
                    row.featureCount(),
                    featuresByAutomationId.getOrDefault(row.id(), List.of())))
        .toList();
  }

  private Map<Long, ReviewAutomationTriggerStatusView> loadTriggerStatusByAutomationId(
      List<Long> automationIds) {
    if (automationIds == null || automationIds.isEmpty()) {
      return Map.of();
    }

    Map<Long, ZonedDateTime> lastRunAtByAutomationId =
        reviewAutomationRunRepository.findLatestRunTimestampsByAutomationIds(automationIds).stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ReviewAutomationRunTimestampRow::automationId,
                    ReviewAutomationRunTimestampRow::timestamp));
    Map<Long, ZonedDateTime> lastSuccessfulRunAtByAutomationId =
        reviewAutomationRunRepository
            .findLatestSuccessfulRunTimestampsByAutomationIds(
                automationIds,
                List.of(
                    com.box.l10n.mojito.entity.review.ReviewAutomationRun.Status.COMPLETED,
                    com.box.l10n.mojito.entity.review.ReviewAutomationRun.Status
                        .COMPLETED_WITH_ERRORS))
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ReviewAutomationRunTimestampRow::automationId,
                    ReviewAutomationRunTimestampRow::timestamp));

    ReviewAutomationCronSchedulerService cronSchedulerService =
        reviewAutomationCronSchedulerServiceProvider.getIfAvailable();
    Map<Long, ReviewAutomationCronSchedulerService.TriggerHealth> triggerHealthByAutomationId =
        cronSchedulerService == null
            ? Map.of()
            : cronSchedulerService.getTriggerHealths(automationIds);

    Map<Long, ReviewAutomationTriggerStatusView> result = new LinkedHashMap<>();
    for (Long automationId : automationIds) {
      ReviewAutomationCronSchedulerService.TriggerHealth triggerHealth =
          triggerHealthByAutomationId.get(automationId);
      result.put(
          automationId,
          new ReviewAutomationTriggerStatusView(
              triggerHealth == null ? "MISSING" : triggerHealth.status().name(),
              triggerHealth == null ? "NONE" : triggerHealth.quartzState(),
              triggerHealth == null ? null : triggerHealth.nextRunAt(),
              triggerHealth == null ? null : triggerHealth.previousRunAt(),
              lastRunAtByAutomationId.get(automationId),
              lastSuccessfulRunAtByAutomationId.get(automationId),
              triggerHealth == null || triggerHealth.repairRecommended()));
    }
    return result;
  }

  private ReviewAutomationTriggerStatusView defaultTriggerStatusView() {
    return new ReviewAutomationTriggerStatusView("MISSING", "NONE", null, null, null, null, true);
  }

  private ReviewAutomationDetail.TeamRef toTeamRef(Team team) {
    return team == null ? null : new ReviewAutomationDetail.TeamRef(team.getId(), team.getName());
  }

  private SearchReviewAutomationsView.TeamRef toTeamRef(Long teamId, String teamName) {
    return teamId == null ? null : new SearchReviewAutomationsView.TeamRef(teamId, teamName);
  }

  private Set<ReviewFeature> resolveFeatures(List<Long> featureIds) {
    if (featureIds == null || featureIds.isEmpty()) {
      return new LinkedHashSet<>();
    }

    List<ReviewFeature> features = reviewFeatureRepository.findByIdInOrderByNameAsc(featureIds);
    if (features.size() != featureIds.size()) {
      Set<Long> foundIds =
          features.stream().map(ReviewFeature::getId).collect(java.util.stream.Collectors.toSet());
      List<Long> missingIds = featureIds.stream().filter(id -> !foundIds.contains(id)).toList();
      throw new IllegalArgumentException("Unknown review features: " + missingIds);
    }
    return new LinkedHashSet<>(features);
  }

  private List<Long> normalizeFeatureIds(List<Long> featureIds) {
    return (featureIds == null ? List.<Long>of() : featureIds)
        .stream().filter(id -> id != null && id > 0).distinct().toList();
  }

  private void ensureFeaturesAvailableToEnabledAutomation(
      boolean enabled, List<Long> featureIds, Long currentAutomationId) {
    if (!enabled || featureIds == null || featureIds.isEmpty()) {
      return;
    }

    List<ReviewAutomationFeatureAssignmentRow> assignments =
        reviewAutomationRepository.findEnabledFeatureAssignments(featureIds, currentAutomationId);
    if (assignments.isEmpty()) {
      return;
    }

    String conflicts =
        assignments.stream()
            .map(
                assignment ->
                    assignment.reviewFeatureName() + " -> " + assignment.reviewAutomationName())
            .distinct()
            .collect(java.util.stream.Collectors.joining("; "));
    throw new IllegalArgumentException(
        "Review features already belong to another enabled automation: " + conflicts);
  }

  private void ensureNameAvailable(String normalizedName, Long currentId) {
    reviewAutomationRepository
        .findByNameIgnoreCase(normalizedName)
        .filter(existing -> !Objects.equals(existing.getId(), currentId))
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException(
                  "Review automation already exists: " + normalizedName);
            });
  }

  private int normalizeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  private int normalizeSchedulePreviewCount(Integer previewCount) {
    if (previewCount == null) {
      return DEFAULT_SCHEDULE_PREVIEW_COUNT;
    }
    return Math.max(1, Math.min(MAX_SCHEDULE_PREVIEW_COUNT, previewCount));
  }

  private String normalizeSearchQuery(String searchQuery) {
    if (searchQuery == null) {
      return null;
    }
    String trimmed = searchQuery.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String normalizeName(String name) {
    String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("Review automation name is required");
    }
    if (normalized.length() > ReviewAutomation.NAME_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Review automation name must be at most "
              + ReviewAutomation.NAME_MAX_LENGTH
              + " characters");
    }
    return normalized;
  }

  private String normalizeCronExpression(String cronExpression) {
    String trimmed = cronExpression == null ? "" : cronExpression.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Cron expression is required");
    }
    if (trimmed.length() > ReviewAutomation.CRON_EXPRESSION_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Cron expression must be at most "
              + ReviewAutomation.CRON_EXPRESSION_MAX_LENGTH
              + " characters");
    }
    if (!CronExpression.isValidExpression(trimmed)) {
      throw new IllegalArgumentException("Invalid cron expression");
    }
    return trimmed;
  }

  private String normalizeTimeZone(String timeZone) {
    String trimmed = timeZone == null ? "" : timeZone.trim();
    if (trimmed.isEmpty()) {
      return DEFAULT_TIME_ZONE;
    }
    if (trimmed.length() > ReviewAutomation.TIME_ZONE_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Time zone must be at most " + ReviewAutomation.TIME_ZONE_MAX_LENGTH + " characters");
    }
    try {
      return ZoneId.of(trimmed).getId();
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid time zone");
    }
  }

  private Team resolveTeam(Long teamId) {
    if (teamId == null) {
      throw new IllegalArgumentException("Team is required");
    }
    return teamRepository
        .findById(teamId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown team: " + teamId));
  }

  private int normalizeDueDateOffsetDays(Integer dueDateOffsetDays) {
    if (dueDateOffsetDays == null) {
      return DEFAULT_DUE_DATE_OFFSET_DAYS;
    }
    if (dueDateOffsetDays < 0) {
      throw new IllegalArgumentException("Due date offset days must be zero or positive");
    }
    return dueDateOffsetDays;
  }

  private int normalizeMaxWordCountPerProject(Integer maxWordCountPerProject) {
    if (maxWordCountPerProject == null) {
      return DEFAULT_MAX_WORD_COUNT_PER_PROJECT;
    }
    if (maxWordCountPerProject < 1) {
      throw new IllegalArgumentException("Max word count per project must be positive");
    }
    return maxWordCountPerProject;
  }

  private void requireAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
  }

  public record ReviewAutomationDetail(
      Long id,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      String name,
      boolean enabled,
      String cronExpression,
      String timeZone,
      TeamRef team,
      int dueDateOffsetDays,
      int maxWordCountPerProject,
      ReviewAutomationTriggerStatusView trigger,
      List<FeatureRef> features) {
    public record TeamRef(Long id, String name) {}

    public record FeatureRef(Long id, String name) {}
  }

  public record ReviewAutomationOption(Long id, String name, boolean enabled) {}

  public record ReviewAutomationBatchExportRow(
      Long id,
      String name,
      boolean enabled,
      String cronExpression,
      String timeZone,
      String teamName,
      int dueDateOffsetDays,
      int maxWordCountPerProject,
      List<String> featureNames) {}

  public record BatchUpsertRow(
      Long id,
      String name,
      Boolean enabled,
      String cronExpression,
      String timeZone,
      Long teamId,
      Integer dueDateOffsetDays,
      Integer maxWordCountPerProject,
      List<Long> featureIds) {}

  public record BatchUpsertResult(int createdCount, int updatedCount) {}
}
