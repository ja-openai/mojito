package com.box.l10n.mojito.service.mcp.review;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Admin-only MCP entry point for the read-only Review Project decision-integrity audit. */
@Component
public class AuditReviewProjectDecisionIntegrityMcpTool
    extends TypedMcpToolHandler<AuditReviewProjectDecisionIntegrityMcpTool.Input> {

  private static final int DEFAULT_DETAIL_LIMIT = 50;
  private static final Map<String, Object> UTC_INSTANT_SCHEMA =
      Map.of("type", "string", "format", "date-time");
  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "review_project.audit_decision_integrity",
          "Audit Review Project decision integrity",
          "Run a bounded, read-only audit of persisted Review Project decisions. Reports rapid exact-target carryover candidates, deterministic placeholder/ICU/markup risks, and source-equals-target items that need human review. Requires an admin and explicit UTC bounds; never changes translations.",
          true,
          true,
          List.of(
              new McpToolParameter(
                  "fromInclusive",
                  "Inclusive UTC start instant, for example 2026-08-19T20:00:00Z.",
                  true,
                  UTC_INSTANT_SCHEMA),
              new McpToolParameter(
                  "toExclusive",
                  "Exclusive UTC end instant, for example 2026-08-20T22:00:00Z. The window may not exceed 48 hours.",
                  true,
                  UTC_INSTANT_SCHEMA),
              new McpToolParameter(
                  "carryoverDetailLimit",
                  "Optional number of carryover runs to return. Defaults to 50; allowed range 0-50. Totals are never capped.",
                  false,
                  Integer.class),
              new McpToolParameter(
                  "structuralDetailLimit",
                  "Optional number of broader-review findings to return. Defaults to 50; allowed range 0-50. Totals are never capped.",
                  false,
                  Integer.class)));

  private final ReviewProjectDecisionIntegrityAuditService auditService;

  public AuditReviewProjectDecisionIntegrityMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      ReviewProjectDecisionIntegrityAuditService auditService) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.auditService = Objects.requireNonNull(auditService);
  }

  public record Input(
      String fromInclusive,
      String toExclusive,
      Integer carryoverDetailLimit,
      Integer structuralDetailLimit) {}

  @Override
  protected Object execute(Input input) {
    if (!isCurrentAuthenticationAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }

    Instant fromInclusive = parseUtcInstant(input.fromInclusive(), "fromInclusive");
    Instant toExclusive = parseUtcInstant(input.toExclusive(), "toExclusive");
    int carryoverDetailLimit = normalizeLimit(input.carryoverDetailLimit());
    int structuralDetailLimit = normalizeLimit(input.structuralDetailLimit());
    return auditService.audit(
        fromInclusive, toExclusive, carryoverDetailLimit, structuralDetailLimit);
  }

  private static Instant parseUtcInstant(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    try {
      OffsetDateTime parsed =
          OffsetDateTime.parse(value.trim(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      if (!ZoneOffset.UTC.equals(parsed.getOffset())) {
        throw new IllegalArgumentException(fieldName + " must use an explicit UTC offset");
      }
      return parsed.toInstant();
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException(fieldName + " must be an ISO-8601 UTC instant", exception);
    }
  }

  private static int normalizeLimit(Integer value) {
    return value == null ? DEFAULT_DETAIL_LIMIT : value;
  }

  private static boolean isCurrentAuthenticationAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }
}
