package com.box.l10n.mojito.service.review;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Content-free diagnostics, not proof that a client's description of its input is truthful. */
final class ReviewProjectSaveTrace {
  private static final Logger logger = LoggerFactory.getLogger(ReviewProjectSaveTrace.class);
  private static final String SERVER_BUILD = serverBuild();
  private static final String UUID = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
  private static final Set<String> OPERATIONS =
      Set.of(
          "review_save",
          "review_accept",
          "review_status_change",
          "review_state_change",
          "agent_outcome");
  private static final Set<String> ORIGINS =
      Set.of(
          "server_snapshot",
          "staged_suggestion",
          "agent_proposal",
          "editor",
          "ai_suggestion",
          "unknown");

  private ReviewProjectSaveTrace() {}

  static Attempt start(ReviewProjectClientContext context, Long rowId, String expectedRevision) {
    Attempt attempt = new Attempt(context, rowId, expectedRevision);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      attempt.transactional = true;
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              attempt.emit(status);
            }
          });
    }
    return attempt;
  }

  static final class Attempt {
    private final ReviewProjectClientContext context;
    private final String expectedRevision;
    private final Map<String, Object> fields = new LinkedHashMap<>();
    private boolean transactional;
    private String result = "incomplete";

    private Attempt(ReviewProjectClientContext context, Long rowId, String expectedRevision) {
      this.context = context;
      this.expectedRevision = expectedRevision;
      fields.put("serverBuild", SERVER_BUILD);
      fields.put("serverOperation", "review_project_save_decision");
      fields.put("route", requestRoute());
      fields.put("rowId", id(rowId));
      fields.put("clientClaims", context == null ? "legacy_unknown" : "untrusted");
      fields.put("expectedRevision", revision(expectedRevision));
      if (context == null) return;
      fields.put("schema", Objects.equals(context.schemaVersion(), 1) ? "v1" : "unknown");
      fields.put("page", safe(context.pageSessionId(), 36, UUID));
      fields.put("operation", safe(context.operationId(), 36, UUID));
      fields.put("sequence", id(context.requestSequence()));
      fields.put("bundle", safe(context.loadedBundleId(), 100, "[A-Za-z0-9_-]{1,90}\\.js"));
      fields.put("operationOrigin", choice(context.operationOrigin(), OPERATIONS));
      fields.put("recovery", choice(context.recovery(), Set.of("use_mine", "use_current")));
      putOwner("draft", context.owner());
      if (context.targetOrigin() != null) {
        var origin = context.targetOrigin();
        fields.put("targetOrigin", choice(origin.kind(), ORIGINS));
        putOwner("target", origin.owner());
        fields.put("suggestionId", id(origin.suggestionId()));
        fields.put("proposalId", id(origin.proposalId()));
        fields.put("proposalRevision", id(origin.proposalRevision()));
        fields.put("aiRequest", safe(origin.aiRequestId(), 36, UUID));
      }
    }

    void bind(Long actorId, Long projectId, Long tmId, Long localeId, Long beforeVariantId) {
      fields.put("actorId", id(actorId));
      fields.put("projectId", id(projectId));
      fields.put("tmId", id(tmId));
      fields.put("localeId", id(localeId));
      fields.put("beforeVariantId", id(beforeVariantId));
      if (context == null) return;
      fields.put("draftIdentity", identity(context.owner(), projectId, tmId));
      if (context.targetOrigin() != null) {
        var owner = context.targetOrigin().owner();
        fields.put("targetIdentity", identity(owner, projectId, tmId));
        fields.put(
            "targetRevisionRelation",
            owner == null
                ? "unknown"
                : Objects.equals(owner.reviewStateRevision(), expectedRevision)
                    ? "matches_request"
                    : "use_mine".equals(context.recovery())
                        ? "explicit_rebase"
                        : "differs_from_request");
      }
    }

    private String identity(ReviewProjectClientContext.Owner owner, Long projectId, Long tmId) {
      if (owner == null) return "unknown";
      if (!Objects.equals(owner.projectId(), projectId)
          || !Objects.equals(owner.textUnitId(), fields.get("rowId"))
          || !Objects.equals(owner.tmTextUnitId(), tmId)) return "row_mismatch";
      return Objects.equals(owner.reviewStateRevision(), expectedRevision)
          ? "matches_request"
          : "revision_differs_from_request";
    }

    GetProjectDetailView.ReviewProjectTextUnit completed(
        GetProjectDetailView.ReviewProjectTextUnit row) {
      fields.put(
          "afterVariantId",
          row.currentTmTextUnitVariant() == null ? null : id(row.currentTmTextUnitVariant().id()));
      return row;
    }

    void finish(String result) {
      this.result = result;
      if (!transactional) emit(TransactionSynchronization.STATUS_UNKNOWN);
    }

    private void putOwner(String prefix, ReviewProjectClientContext.Owner owner) {
      if (owner == null) return;
      fields.put(prefix + "Project", id(owner.projectId()));
      fields.put(prefix + "Row", id(owner.textUnitId()));
      fields.put(prefix + "Tm", id(owner.tmTextUnitId()));
      fields.put(prefix + "Revision", revision(owner.reviewStateRevision()));
    }

    private void emit(int completion) {
      fields.put(
          "transaction",
          completion == TransactionSynchronization.STATUS_COMMITTED
              ? "committed"
              : completion == TransactionSynchronization.STATUS_ROLLED_BACK
                  ? "rolled_back"
                  : "unknown");
      fields.put("methodResult", result);
      // A logging backend failure must not turn a committed translation into a failed HTTP save.
      try {
        logger.info("Review project save attribution: {}", fields);
      } catch (RuntimeException ignored) {
        // Diagnostics are deliberately non-blocking.
      }
    }
  }

  private static Long id(Long value) {
    return value != null && value >= 0 ? value : null;
  }

  private static String choice(String value, Set<String> choices) {
    return value == null ? "unknown" : choices.contains(value) ? value : "invalid";
  }

  private static String revision(String value) {
    return safe(value, 300, "v[0-9]+:(?:[0-9]+|null)(?::(?:[0-9]+|null)){1,12}");
  }

  private static String safe(String value, int max, String pattern) {
    return value == null
        ? "unknown"
        : value.length() <= max && value.matches(pattern) ? value : "invalid";
  }

  private static String serverBuild() {
    try (var stream = ReviewProjectSaveTrace.class.getResourceAsStream("/git.properties")) {
      if (stream == null) return "unknown";
      Properties properties = new Properties();
      properties.load(stream);
      return safe(properties.getProperty("git.commit.id"), 40, "[0-9a-fA-F]{40}");
    } catch (IOException | RuntimeException exception) {
      return "unknown";
    }
  }

  private static String requestRoute() {
    if (!(RequestContextHolder.getRequestAttributes()
        instanceof ServletRequestAttributes attributes)) return "non_http_or_unavailable";
    String path = attributes.getRequest().getRequestURI();
    if ("/api/mcp".equals(path)) return "mcp";
    if (path != null
        && path.length() < 160
        && path.matches("/api/review-project-text-units/[0-9]+/decision"))
      return "review_project_decision_rest";
    return "other_http"; // Never log an arbitrary URI, query, header, or request payload.
  }
}
