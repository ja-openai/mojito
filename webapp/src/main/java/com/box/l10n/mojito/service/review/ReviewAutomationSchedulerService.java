package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewAutomation;
import com.box.l10n.mojito.entity.review.ReviewAutomation.IncidentScope;
import com.box.l10n.mojito.entity.review.ReviewAutomation.ReviewSource;
import com.box.l10n.mojito.entity.review.ReviewAutomationRun;
import com.box.l10n.mojito.entity.review.ReviewFeature;
import com.box.l10n.mojito.entity.review.ReviewProjectType;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.UserDetailsImpl;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService;
import com.box.l10n.mojito.service.security.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class ReviewAutomationSchedulerService {

  private static final Logger logger =
      LoggerFactory.getLogger(ReviewAutomationSchedulerService.class);

  private static final String METRIC_PREFIX = "ReviewAutomation";
  static final int MAX_INCIDENT_BATCHES_PER_RUN = 10;
  private static final long INCIDENT_RUN_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);
  private static final DateTimeFormatter REQUEST_NAME_DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final ReviewAutomationRepository reviewAutomationRepository;
  private final ReviewProjectService reviewProjectService;
  private final IncidentReviewBatchService incidentReviewBatchService;
  private final ReviewAutomationRunService reviewAutomationRunService;
  private final UserService userService;
  private final MeterRegistry meterRegistry;

  public ReviewAutomationSchedulerService(
      ReviewAutomationRepository reviewAutomationRepository,
      ReviewProjectService reviewProjectService,
      IncidentReviewBatchService incidentReviewBatchService,
      ReviewAutomationRunService reviewAutomationRunService,
      UserService userService,
      MeterRegistry meterRegistry) {
    this.reviewAutomationRepository = reviewAutomationRepository;
    this.reviewProjectService = reviewProjectService;
    this.incidentReviewBatchService = incidentReviewBatchService;
    this.reviewAutomationRunService = reviewAutomationRunService;
    this.userService = userService;
    this.meterRegistry = meterRegistry;
  }

  public RunResult runAutomationFromCron(Long automationId) {
    User systemUser = getSystemUser();
    SecurityContext previousContext = SecurityContextHolder.getContext();
    SecurityContext systemContext = SecurityContextHolder.createEmptyContext();
    UserDetailsImpl principal = new UserDetailsImpl(systemUser);
    systemContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(principal, "", principal.getAuthorities()));
    try {
      SecurityContextHolder.setContext(systemContext);
      return runAutomation(
          automationId, ReviewAutomationRun.TriggerSource.CRON, systemUser.getId(), true);
    } finally {
      SecurityContextHolder.setContext(previousContext);
    }
  }

  public RunResult runAutomationNow(Long automationId, Long requestedByUserId) {
    if (requestedByUserId == null) {
      throw new AccessDeniedException("No authenticated user");
    }
    return runAutomation(
        automationId, ReviewAutomationRun.TriggerSource.MANUAL, requestedByUserId, false);
  }

  public RunResult runAutomation(
      Long automationId,
      ReviewAutomationRun.TriggerSource triggerSource,
      Long requestedByUserId,
      boolean requireEnabled) {
    logger.info(
        "Review automation run starting: automationId={}, source={}", automationId, triggerSource);
    incrementCounter("runs", Tags.of("result", "started"));

    ReviewAutomation automation =
        reviewAutomationRepository
            .findByIdWithFeatures(automationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Review automation not found: " + automationId));

    if (requireEnabled && !Boolean.TRUE.equals(automation.getEnabled())) {
      incrementCounter("runs", Tags.of("result", "skipped_disabled"));
      logger.info(
          "Review automation run skipped: automationId={}, source={}, reason=disabled",
          automationId,
          triggerSource);
      return new RunResult(automation.getId(), automation.getName(), 0, 0, 0, 0, 0, 0, null);
    }

    ZonedDateTime startedAt = ZonedDateTime.now(resolveZoneId(automation));
    boolean allIncidents =
        automation.getReviewSource() == ReviewSource.INCIDENTS
            && automation.getIncidentScope() == IncidentScope.ALL;
    List<ReviewFeature> features =
        allIncidents
            ? List.of()
            : automation.getFeatures().stream()
                .sorted(
                    Comparator.comparing(ReviewFeature::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ReviewFeature::getId))
                .toList();

    ReviewAutomationRun run =
        reviewAutomationRunService.createRunningRun(
            automation, triggerSource, requestedByUserId, features.size(), startedAt);

    int createdProjectRequestCount = 0;
    int createdProjectCount = 0;
    int createdLocaleCount = 0;
    int skippedLocaleCount = 0;
    int erroredLocaleCount = 0;
    try {
      if (automation.getReviewSource() == ReviewSource.INCIDENTS) {
        if (allIncidents || !features.isEmpty()) {
          IncidentReviewBatchService.Request request =
              new IncidentReviewBatchService.Request(
                  List.of(),
                  features.stream().map(ReviewFeature::getId).toList(),
                  List.of(),
                  automation.getExcludedLocaleTags(),
                  automation.getIncidentReviewType(),
                  automation.getTeam().getId(),
                  buildIncidentRequestName(automation, startedAt),
                  startedAt.plusDays(automation.getDueDateOffsetDays()),
                  automation.getMaxWordCountPerProject(),
                  automation.getAssignTranslator(),
                  ReviewProjectType.NORMAL,
                  buildRequestNotes(automation, triggerSource),
                  List.of(),
                  allIncidents);
          long batchStarted = System.nanoTime();
          Set<String> createdLocales = new HashSet<>();
          for (int batch = 0; batch < MAX_INCIDENT_BATCHES_PER_RUN; batch++) {
            // Each call commits its projects and cursor together. A later failure or the run
            // budget leaves earlier progress intact for the next scheduled/manual execution.
            IncidentReviewBatchService.Result result =
                incidentReviewBatchService.create(request, requestedByUserId);
            createdProjectRequestCount += result.requestIds().size();
            createdProjectCount += result.projectCount();
            createdLocales.addAll(result.localeTags());
            createdLocaleCount = createdLocales.size();
            if (!result.hasMore()) break;
            if (batch + 1 == MAX_INCIDENT_BATCHES_PER_RUN
                || System.nanoTime() - batchStarted >= INCIDENT_RUN_BUDGET_NANOS) {
              logger.info(
                  "Incident review automation saved continuation: automationId={}, runId={}, batches={}",
                  automationId,
                  run.getId(),
                  batch + 1);
              incrementCounter("incident_continuations", Tags.empty());
              break;
            }
          }
        }
      } else {
        for (ReviewFeature feature : features) {
          CreateReviewProjectRequestResult result =
              reviewProjectService.createAutomatedReviewProjectRequest(
                  new CreateAutomatedReviewProjectRequestCommand(
                      feature.getId(),
                      buildRequestName(feature, startedAt),
                      buildRequestNotes(automation, triggerSource),
                      startedAt.plusDays(automation.getDueDateOffsetDays()),
                      automation.getTeam().getId(),
                      automation.getMaxWordCountPerProject(),
                      automation.getAssignTranslator(),
                      requestedByUserId,
                      automation.getExcludedLocaleTags()));

          if (result.requestId() != null) {
            createdProjectRequestCount++;
          }
          createdProjectCount += result.projectIds().size();
          createdLocaleCount += result.createdLocaleCount();
          skippedLocaleCount += result.skippedLocaleCount();
          erroredLocaleCount += result.erroredLocaleCount();
        }
      }

      reviewAutomationRunService.markCompleted(
          run.getId(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount);
      logger.info(
          "Review automation run completed: automationId={}, source={}, runId={}, requests={}, projects={}, createdLocales={}, skippedLocales={}, erroredLocales={}",
          automation.getId(),
          triggerSource,
          run.getId(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount);
      incrementCounter(
          "runs",
          Tags.of("result", erroredLocaleCount > 0 ? "completed_with_errors" : "completed"));
      return new RunResult(
          automation.getId(),
          automation.getName(),
          features.size(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount,
          run.getId());
    } catch (RuntimeException e) {
      logger.error(
          "Review automation run failed: automationId={}, source={}, error={}",
          automationId,
          triggerSource,
          e.getMessage(),
          e);
      reviewAutomationRunService.markFailed(
          run.getId(),
          createdProjectRequestCount,
          createdProjectCount,
          createdLocaleCount,
          skippedLocaleCount,
          erroredLocaleCount,
          e.getMessage());
      incrementCounter("runs", Tags.of("result", "failed"));
      throw e;
    }
  }

  private User getSystemUser() {
    User systemUser = userService.findSystemUser();
    if (systemUser == null || systemUser.getId() == null) {
      throw new IllegalStateException("System user not found");
    }
    return systemUser;
  }

  private ZoneId resolveZoneId(ReviewAutomation automation) {
    return ZoneId.of(automation.getTimeZone());
  }

  private String buildIncidentRequestName(ReviewAutomation automation, ZonedDateTime startedAt) {
    String suffix = " review - " + REQUEST_NAME_DATE_FORMAT.format(startedAt);
    String name = automation.getName();
    int end = Math.min(name.length(), ReviewAutomation.NAME_MAX_LENGTH - suffix.length());
    if (end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) {
      end--;
    }
    return name.substring(0, end).stripTrailing() + suffix;
  }

  private String buildRequestName(ReviewFeature feature, ZonedDateTime startedAt) {
    return feature.getName().trim() + " review - " + REQUEST_NAME_DATE_FORMAT.format(startedAt);
  }

  private String buildRequestNotes(
      ReviewAutomation automation, ReviewAutomationRun.TriggerSource triggerSource) {
    return "Created by review automation "
        + automation.getName()
        + " ("
        + triggerSource.name().toLowerCase()
        + ")";
  }

  private void incrementCounter(String metricSuffix, Tags tags) {
    meterRegistry.counter("%s.%s".formatted(METRIC_PREFIX, metricSuffix), tags).increment();
  }

  public record RunResult(
      Long automationId,
      String automationName,
      int featureCount,
      int createdProjectRequestCount,
      int createdProjectCount,
      int createdLocaleCount,
      int skippedLocaleCount,
      int erroredLocaleCount,
      Long runId) {}
}
