package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.*;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Bounded transport contracts shared by REST, MCP, and the review-project handoff. */
public final class AgentReviewContracts {
  private AgentReviewContracts() {}

  public static final int MAX_BATCH_SIZE = 50;
  public static final int MAX_ARTIFACT_BYTES = 2 * 1024 * 1024;
  public static final int MAX_GROUPS = 200;
  public static final int MAX_SCOPE_ITEMS = 10000;
  public static final int MAX_TEXT_LENGTH = 64000;
  public static final int MAX_EVIDENCE_LENGTH = 64000;

  public record Group(
      String key,
      Long repositoryId,
      Long localeId,
      String featureGroup,
      List<Long> tmTextUnitIds,
      String inputFingerprint) {}

  public record CreateRunRequest(
      String requestKey,
      String reviewType,
      Long teamId,
      String methodVersion,
      String configurationVersion,
      List<Group> groups,
      String inputManifestJson,
      Integer dueDateOffsetDays,
      Integer maxWordCountPerProject,
      Boolean assignTranslator) {}

  public record ClaimRequest(String owner, Long expectedGeneration, Integer leaseSeconds) {}

  public record Claim(String owner, long generation) {}

  public enum GroupStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    MISSING_INPUT
  }

  public record CheckpointRequest(
      Claim claim,
      long expectedRevision,
      String groupKey,
      GroupStatus status,
      int reviewedItemCount,
      String artifactSha256,
      String note) {}

  public record GroupCheckpoint(
      GroupStatus status, int reviewedItemCount, String artifactSha256, String note) {}

  public record Checkpoint(Map<String, GroupCheckpoint> groups) {}

  public record FinishRequest(Claim claim, long expectedRevision, boolean cancel) {}

  public record ArtifactRequest(Claim claim, String contentType, String contentBase64) {}

  public record Artifact(String sha256, String contentType, String contentBase64, int byteCount) {}

  public record SubmitProposalRequest(
      Claim claim,
      String submissionKey,
      String groupKey,
      Long tmTextUnitId,
      String source,
      String sourceComment,
      Long baselineVariantId,
      String baselineTarget,
      String baselineStatus,
      Boolean baselineIncludedInLocalizedFile,
      String proposedTarget,
      Category category,
      Readiness readiness,
      String rationale,
      String evidenceJson,
      String producerIdentity,
      String verifierIdentity,
      String verificationRationale,
      String integrityDiagnostics,
      Long previousProposalId,
      Long respondsToFeedbackId) {}

  public record SubmissionResult(
      int index,
      String submissionKey,
      Long proposalId,
      String findingId,
      Integer proposalRevision,
      String errorCode,
      String errorMessage) {}

  public record ResponseRequest(
      Claim claim,
      String requestKey,
      Long feedbackId,
      FeedbackAction action,
      String actorIdentity,
      String explanation,
      String evidenceJson) {}

  public record HumanFeedbackRequest(
      String requestKey,
      Long proposalId,
      long expectedVersion,
      FeedbackAction action,
      OriginalAssessment originalAssessment,
      SuggestionAssessment suggestionAssessment,
      String explanation,
      boolean followUpRequested,
      String finalTarget,
      Long appliedVariantId,
      String contextFingerprint) {}

  public record RunView(
      Long id,
      String reviewType,
      Long teamId,
      List<Long> repositoryIds,
      List<Long> localeIds,
      String methodVersion,
      String configurationVersion,
      String inputFingerprint,
      String manifestSha256,
      RunStatus status,
      long revision,
      String claimOwner,
      long claimGeneration,
      ZonedDateTime leaseExpiresAt,
      int plannedGroupCount,
      int completedGroupCount,
      int failedGroupCount,
      int reviewedItemCount,
      String checkpointSha256,
      Checkpoint checkpoint,
      int dueDateOffsetDays,
      int maxWordCountPerProject,
      boolean assignTranslator,
      ZonedDateTime createdDate,
      ZonedDateTime completedAt) {}

  /** Listing metadata only; inspect the run to load its complete checkpoint. */
  public record RunSummary(
      Long id,
      String reviewType,
      Long teamId,
      List<Long> repositoryIds,
      List<Long> localeIds,
      String methodVersion,
      String configurationVersion,
      String inputFingerprint,
      String manifestSha256,
      RunStatus status,
      long revision,
      String claimOwner,
      long claimGeneration,
      ZonedDateTime leaseExpiresAt,
      int plannedGroupCount,
      int completedGroupCount,
      int failedGroupCount,
      int reviewedItemCount,
      String checkpointSha256,
      int dueDateOffsetDays,
      int maxWordCountPerProject,
      boolean assignTranslator,
      ZonedDateTime createdDate,
      ZonedDateTime completedAt) {}

  public record RunPage(List<RunSummary> runs, Long nextBeforeId) {}

  public record RunManifest(List<Group> groups, String inputManifestJson) {}
}
