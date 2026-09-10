package com.box.l10n.mojito.service.mcp.agentreview;

import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.MAX_ARTIFACT_BYTES;
import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.MAX_BATCH_SIZE;
import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.MAX_EVIDENCE_LENGTH;
import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.MAX_GROUPS;
import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.MAX_SCOPE_ITEMS;
import static com.box.l10n.mojito.service.agentreview.AgentReviewContracts.MAX_TEXT_LENGTH;

import com.box.l10n.mojito.service.mcp.McpToolParameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit schemas for the nested records in the agent review API. */
final class AgentReviewMcpSchemas {
  private AgentReviewMcpSchemas() {}

  static McpToolParameter runId() {
    return new McpToolParameter("runId", "Shared Mojito review run id.", true, positiveId());
  }

  static Map<String, Object> positiveId() {
    return Map.of("type", "integer", "minimum", 1);
  }

  static Map<String, Object> integer(int minimum, int maximum) {
    return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
  }

  static Map<String, Object> string(int maximumLength) {
    return Map.of("type", "string", "maxLength", maximumLength);
  }

  static Map<String, Object> values(String... values) {
    return Map.of("type", "string", "enum", List.of(values));
  }

  static Map<String, Object> sha256() {
    return Map.of("type", "string", "pattern", "^[0-9a-f]{64}$");
  }

  static Map<String, Object> object(Map<String, Object> properties, String... requiredProperties) {
    return Map.of(
        "type",
        "object",
        "additionalProperties",
        false,
        "properties",
        properties,
        "required",
        List.of(requiredProperties));
  }

  static Map<String, Object> claim() {
    return object(
        Map.of("owner", string(128), "generation", Map.of("type", "integer", "minimum", 1)),
        "owner",
        "generation");
  }

  static Map<String, Object> groups() {
    return Map.of(
        "type",
        "array",
        "minItems",
        1,
        "maxItems",
        MAX_GROUPS,
        "items",
        object(
            Map.of(
                "key", string(128),
                "repositoryId", positiveId(),
                "localeId", positiveId(),
                "featureGroup", string(255),
                "tmTextUnitIds",
                    Map.of(
                        "type",
                        "array",
                        "minItems",
                        1,
                        "maxItems",
                        MAX_SCOPE_ITEMS,
                        "uniqueItems",
                        true,
                        "items",
                        positiveId()),
                "inputFingerprint", string(128)),
            "key",
            "repositoryId",
            "localeId",
            "featureGroup",
            "tmTextUnitIds",
            "inputFingerprint"));
  }

  static Map<String, Object> checkpoint() {
    return object(
        Map.of(
            "claim", claim(),
            "expectedRevision", Map.of("type", "integer", "minimum", 0),
            "groupKey", string(128),
            "status", values("IN_PROGRESS", "COMPLETED", "FAILED", "MISSING_INPUT"),
            "reviewedItemCount", integer(0, MAX_SCOPE_ITEMS),
            "artifactSha256", sha256(),
            "note", string(MAX_EVIDENCE_LENGTH)),
        "claim",
        "expectedRevision",
        "groupKey",
        "status",
        "reviewedItemCount",
        "artifactSha256");
  }

  static Map<String, Object> completion() {
    return object(
        Map.of(
            "claim", claim(),
            "expectedRevision", Map.of("type", "integer", "minimum", 0),
            "cancel", Map.of("type", "boolean")),
        "claim",
        "expectedRevision",
        "cancel");
  }

  static Map<String, Object> artifact() {
    return object(
        Map.of(
            "claim", claim(),
            "contentType", string(128),
            "contentBase64", string(4 * ((MAX_ARTIFACT_BYTES + 2) / 3))),
        "claim",
        "contentType",
        "contentBase64");
  }

  static Map<String, Object> proposals() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("claim", claim());
    properties.put("submissionKey", string(128));
    properties.put("groupKey", string(128));
    properties.put("tmTextUnitId", positiveId());
    properties.put("source", string(MAX_TEXT_LENGTH));
    properties.put("sourceComment", nullable(string(MAX_TEXT_LENGTH)));
    properties.put("baselineVariantId", nullable(positiveId()));
    properties.put("baselineTarget", nullable(string(MAX_TEXT_LENGTH)));
    properties.put("baselineStatus", nullable(string(32)));
    properties.put("baselineIncludedInLocalizedFile", nullable(Map.of("type", "boolean")));
    properties.put("proposedTarget", nullable(string(MAX_TEXT_LENGTH)));
    properties.put(
        "category", values("OBVIOUS_ERROR", "CONSISTENCY_ERROR", "OPTIONAL_IMPROVEMENT"));
    properties.put("readiness", values("READY", "HOLD", "OPTIONAL"));
    properties.put("rationale", string(MAX_EVIDENCE_LENGTH));
    properties.put("evidenceJson", nullable(string(MAX_EVIDENCE_LENGTH)));
    properties.put("producerIdentity", string(255));
    properties.put("verifierIdentity", nullable(string(255)));
    properties.put("verificationRationale", nullable(string(MAX_EVIDENCE_LENGTH)));
    properties.put("integrityDiagnostics", nullable(string(MAX_EVIDENCE_LENGTH)));
    properties.put("previousProposalId", nullable(positiveId()));
    properties.put("respondsToFeedbackId", nullable(positiveId()));
    return Map.of(
        "type",
        "array",
        "minItems",
        1,
        "maxItems",
        MAX_BATCH_SIZE,
        "items",
        object(
            properties,
            "claim",
            "submissionKey",
            "groupKey",
            "tmTextUnitId",
            "source",
            "category",
            "readiness",
            "rationale",
            "producerIdentity"));
  }

  static Map<String, Object> response() {
    return object(
        Map.of(
            "claim", claim(),
            "requestKey", string(128),
            "feedbackId", positiveId(),
            "action", values("CONTEXT_REQUEST", "CHALLENGE"),
            "actorIdentity", string(255),
            "explanation", string(MAX_EVIDENCE_LENGTH),
            "evidenceJson", nullable(string(MAX_EVIDENCE_LENGTH))),
        "claim",
        "requestKey",
        "feedbackId",
        "action",
        "actorIdentity",
        "explanation");
  }

  static Map<String, Object> nullable(Map<String, Object> schema) {
    return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
  }

  static long requireId(Long id, String name) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException(name + " must be a positive integer");
    }
    return id;
  }

  static int limit(Integer limit) {
    int effective = limit == null ? 50 : limit;
    if (effective < 1 || effective > 200) {
      throw new IllegalArgumentException("limit must be between 1 and 200");
    }
    return effective;
  }

  static McpToolParameter afterId() {
    return new McpToolParameter(
        "afterId",
        "Exclusive row-id cursor from the previous page; default 0.",
        false,
        Map.of("type", "integer", "minimum", 0));
  }

  static McpToolParameter pageLimit() {
    return new McpToolParameter("limit", "Page size, 1-200; default 50.", false, integer(1, 200));
  }

  static long cursor(Long afterId) {
    if (afterId != null && afterId < 0) {
      throw new IllegalArgumentException("afterId must be nonnegative");
    }
    return afterId == null ? 0 : afterId;
  }
}
