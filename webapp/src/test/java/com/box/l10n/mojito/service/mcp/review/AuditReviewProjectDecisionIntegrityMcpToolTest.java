package com.box.l10n.mojito.service.mcp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditService;
import java.time.Instant;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

public class AuditReviewProjectDecisionIntegrityMcpToolTest {

  private final ReviewProjectDecisionIntegrityAuditService auditService =
      Mockito.mock(ReviewProjectDecisionIntegrityAuditService.class);
  private final AuditReviewProjectDecisionIntegrityMcpTool tool =
      new AuditReviewProjectDecisionIntegrityMcpTool(
          ObjectMapper.withNoFailOnUnknownProperties(), auditService);

  @Before
  public void setUp() {
    SecurityContextHolder.clearContext();
  }

  @After
  public void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  public void descriptorDeclaresReadOnlyDryRunAuditTool() {
    assertThat(tool.descriptor().name()).isEqualTo("review_project.audit_decision_integrity");
    assertThat(tool.descriptor().readOnly()).isTrue();
    assertThat(tool.descriptor().dryRunByDefault()).isTrue();
  }

  @Test
  public void executeAsAdminParsesUtcBoundsAndUsesDefaultLimits() {
    authenticateAs("ROLE_ADMIN");

    tool.execute(
        new AuditReviewProjectDecisionIntegrityMcpTool.Input(
            "2026-08-19T20:14:43Z", "2026-08-20T22:14:43Z", null, null));

    verify(auditService)
        .audit(
            Instant.parse("2026-08-19T20:14:43Z"), Instant.parse("2026-08-20T22:14:43Z"), 50, 50);
  }

  @Test
  public void executeRequiresAdminBeforeRunningAudit() {
    authenticateAs("ROLE_TRANSLATOR");

    assertThatThrownBy(
            () ->
                tool.execute(
                    new AuditReviewProjectDecisionIntegrityMcpTool.Input(
                        "2026-08-19T20:14:43Z", "2026-08-20T22:14:43Z", null, null)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Admin role required");

    verifyNoInteractions(auditService);
  }

  @Test
  public void executeRejectsNonUtcBounds() {
    authenticateAs("ROLE_ADMIN");

    assertThatThrownBy(
            () ->
                tool.execute(
                    new AuditReviewProjectDecisionIntegrityMcpTool.Input(
                        "2026-08-19T13:14:43-07:00", "2026-08-20T22:14:43Z", null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("fromInclusive must use an explicit UTC offset");

    verifyNoInteractions(auditService);
  }

  private static void authenticateAs(String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("operator", "ignored", role));
  }
}
