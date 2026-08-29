package com.box.l10n.mojito.service.mcp.translation;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.mcp.McpToolDescriptor;
import com.box.l10n.mojito.service.mcp.McpToolExecutionException;
import com.box.l10n.mojito.service.mcp.McpToolParameter;
import com.box.l10n.mojito.service.mcp.TypedMcpToolHandler;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetHashIntegrityException;
import com.box.l10n.mojito.service.translation.RepeatedCurrentTargetService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;

/** Admin-only read access to exact repeated current target candidates. */
@Component
public class FindRepeatedCurrentTargetsMcpTool
    extends TypedMcpToolHandler<FindRepeatedCurrentTargetsMcpTool.Input> {

  private static final McpToolDescriptor DESCRIPTOR =
      new McpToolDescriptor(
          "translation.find_repeated_current_targets",
          "Find repeated current translation targets",
          "Find current non-source translations where the exact same-locale target is attached to different exact source text. Scans the whole database by default or both sides of one Review Project. This is a resource-intensive admin audit that builds a temporary lookup for each keyset page. Restart validation covers candidate-membership state while mutable metadata and lineage are bounded page-time context. Repeated targets are candidates for semantic review, never proof of a defect. Read-only; never changes translations.",
          true,
          true,
          List.of(
              new McpToolParameter(
                  "reviewProjectId",
                  "Optional Review Project scope. Both matching current rows must belong to this project.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "afterCurrentPointerId",
                  "Optional exclusive keyset cursor. Defaults to 0.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "highWaterCurrentPointerId",
                  "Pinned maximum current-pointer ID returned by the first page. Omit on the first page; required when resuming with a non-zero cursor.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "highWaterReviewProjectTextUnitId",
                  "Pinned maximum Review Project text-unit ID returned by the first scoped page. Omit on the first page; required when resuming a Review Project scan; invalid for a whole-database scan.",
                  false,
                  Long.class),
              new McpToolParameter(
                  "scanToken",
                  "Candidate-snapshot validation token returned by the first page. Omit on the first page and pass it unchanged on every resumed page. If status is RESTART_REQUIRED, discard prior pages and restart without this token or any cursor/high-water marks. Metadata and Review Project lineage are bounded page-time context and must be reread before remediation.",
                  false,
                  String.class),
              new McpToolParameter(
                  "pageSize",
                  "Candidate members per page. Defaults to 500; allowed range 1-2000. Continue until complete=true, unless RESTART_REQUIRED instructs you to discard the scan and restart.",
                  false,
                  Integer.class)));

  private final RepeatedCurrentTargetService service;

  public FindRepeatedCurrentTargetsMcpTool(
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      RepeatedCurrentTargetService service) {
    super(objectMapper, Input.class, DESCRIPTOR);
    this.service = Objects.requireNonNull(service);
  }

  public record Input(
      Long reviewProjectId,
      Long afterCurrentPointerId,
      Long highWaterCurrentPointerId,
      Long highWaterReviewProjectTextUnitId,
      String scanToken,
      Integer pageSize) {}

  @Override
  protected Object execute(Input input) {
    if (!isCurrentAuthenticationAdmin()) {
      throw new AccessDeniedException("Admin role required");
    }
    if (input == null) {
      throw new IllegalArgumentException("input is required");
    }
    try {
      return service.scan(
          input.reviewProjectId(),
          input.afterCurrentPointerId(),
          input.highWaterCurrentPointerId(),
          input.highWaterReviewProjectTextUnitId(),
          input.scanToken(),
          input.pageSize());
    } catch (RepeatedCurrentTargetHashIntegrityException exception) {
      throw new McpToolExecutionException(
          "STALE_CURRENT_TARGET_HASHES",
          exception.getMessage(),
          Map.of(
              "invalidCurrentTargetHashCount",
              exception.getInvalidCurrentTargetHashCount(),
              "restartable",
              false,
              "action",
              "Repair or recompute tm_text_unit_variant.content_md5 for the reported current targets, then start a new scan."));
    } catch (QueryTimeoutException | TransactionTimedOutException exception) {
      throw new McpToolExecutionException(
          "QUERY_TIMEOUT",
          "The repeated-current-target audit exceeded its execution limit.",
          Map.of(
              "retryable",
              true,
              "action",
              "Retry when database load is lower or use a Review Project scope."));
    }
  }

  private static boolean isCurrentAuthenticationAdmin() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }
}
