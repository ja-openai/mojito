package com.box.l10n.mojito.service.review;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.box.l10n.mojito.rest.review.ReviewProjectTextUnitDecisionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class ReviewProjectSaveTraceTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(ReviewProjectSaveTrace.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private static final String REVISION = "v2:2:3:4:5:null:null:null:null";

  @Before
  public void start() {
    logs.start();
    logger.addAppender(logs);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
  }

  @After
  public void stop() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    RequestContextHolder.resetRequestAttributes();
    logger.detachAppender(logs);
    logs.stop();
  }

  private void complete(int status) {
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
      synchronization.afterCompletion(status);
  }

  @Test
  public void onlyReportsCommitAfterTransactionCompletes() {
    var attempt = ReviewProjectSaveTrace.start(null, 2L, REVISION);
    attempt.bind(10L, 1L, 3L, 4L, 5L);
    attempt.finish("success");
    assertTrue(logs.list.isEmpty());
    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertEquals(1, logs.list.size());
    String message = logs.list.getFirst().getFormattedMessage();
    assertTrue(message.contains("transaction=committed"));
    assertTrue(message.contains("clientClaims=legacy_unknown"));
    assertTrue(message.contains("actorId=10"));
    assertTrue(message.contains("beforeVariantId=5"));
  }

  @Test
  public void commitFailureCannotBeReportedAsCommitted() {
    var attempt = ReviewProjectSaveTrace.start(null, 2L, REVISION);
    attempt.finish("success");
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    assertTrue(logs.list.getFirst().getFormattedMessage().contains("transaction=rolled_back"));
    assertFalse(logs.list.getFirst().getFormattedMessage().contains("transaction=committed"));
  }

  @Test
  public void missingTransactionNeverClaimsACommit() {
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    var attempt = ReviewProjectSaveTrace.start(null, 2L, REVISION);
    attempt.finish("success");
    assertEquals(1, logs.list.size());
    assertTrue(logs.list.getFirst().getFormattedMessage().contains("transaction=unknown"));
    assertFalse(logs.list.getFirst().getFormattedMessage().contains("transaction=committed"));
  }

  @Test
  public void loggingFailureDoesNotEscapeTransactionCompletion() {
    @SuppressWarnings("unchecked")
    Appender<ILoggingEvent> failing = mock(Appender.class);
    doThrow(new IllegalStateException("Logging unavailable")).when(failing).doAppend(any());
    logger.addAppender(failing);
    try {
      var attempt = ReviewProjectSaveTrace.start(null, 2L, REVISION);
      attempt.finish("success");
      complete(TransactionSynchronization.STATUS_COMMITTED);
      verify(failing).doAppend(any());
    } finally {
      logger.detachAppender(failing);
    }
  }

  @Test
  public void boundsEvenSyntacticallyValidClientStrings() {
    String oversized = "1".repeat(10_000);
    var owner = new ReviewProjectClientContext.Owner(1L, 2L, 3L, "v2:2:" + oversized);
    var context =
        new ReviewProjectClientContext(
            1, oversized, oversized, 1L, oversized + ".js", "review_save", owner, null, null);
    var attempt = ReviewProjectSaveTrace.start(context, 2L, REVISION);
    attempt.bind(10L, 1L, 3L, 4L, 5L);
    attempt.finish("success");
    complete(TransactionSynchronization.STATUS_COMMITTED);
    String message = logs.list.getFirst().getFormattedMessage();
    assertFalse(message.contains(oversized));
    assertTrue(message.contains("bundle=invalid"));
    assertTrue(message.contains("draftRevision=invalid"));
    assertTrue(message.length() < 2_000);
  }

  @Test
  public void logsOnlyWhitelistedBoundedClaims() {
    String secret = "translation or credential\nforged=value";
    var owner = new ReviewProjectClientContext.Owner(1L, 99L, 3L, secret);
    var context =
        new ReviewProjectClientContext(
            1,
            secret,
            secret,
            -1L,
            secret,
            secret,
            owner,
            new ReviewProjectClientContext.TargetOrigin(secret, owner, 4L, 5L, 6L, secret),
            secret);
    var attempt = ReviewProjectSaveTrace.start(context, 2L, REVISION);
    attempt.bind(10L, 1L, 3L, 4L, 5L);
    attempt.finish("conflict");
    complete(TransactionSynchronization.STATUS_ROLLED_BACK);
    String message = logs.list.getFirst().getFormattedMessage();
    assertFalse(message.contains("translation or credential"));
    assertFalse(message.contains("forged=value"));
    assertTrue(message.contains("bundle=invalid"));
    assertTrue(message.contains("targetIdentity=row_mismatch"));
    assertTrue(message.contains("clientClaims=untrusted"));
  }

  @Test
  public void preservesOldSuggestionRevisionDuringExplicitRebase() {
    var owner = new ReviewProjectClientContext.Owner(1L, 2L, 3L, REVISION);
    var origin = new ReviewProjectClientContext.Owner(1L, 2L, 3L, "v2:2:3:4:1:null:null:null:null");
    var context =
        new ReviewProjectClientContext(
            1,
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            2L,
            "index-example123.js",
            "review_save",
            owner,
            new ReviewProjectClientContext.TargetOrigin(
                "ai_suggestion", origin, null, null, null, "33333333-3333-4333-8333-333333333333"),
            "use_mine");
    var attempt = ReviewProjectSaveTrace.start(context, 2L, REVISION);
    attempt.bind(10L, 1L, 3L, 4L, 5L);
    attempt.finish("success");
    complete(TransactionSynchronization.STATUS_COMMITTED);
    String message = logs.list.getFirst().getFormattedMessage();
    assertTrue(message.contains("targetRevisionRelation=explicit_rebase"));
    assertTrue(message.contains("draftIdentity=matches_request"));
    assertTrue(message.contains("targetRevision=v2:2:3:4:1:null:null:null:null"));
  }

  @Test
  public void absentContextPreservesLegacyRequestContract() throws Exception {
    var request =
        new ObjectMapper()
            .readValue(
                "{\"target\":\"example\",\"decisionState\":\"DECIDED\"}",
                ReviewProjectTextUnitDecisionRequest.class);
    assertNull(request.getClientContext());
    assertEquals("example", request.getTarget());
  }

  @Test
  public void identifiesMcpTransportWithoutLoggingArbitraryRequestPaths() {
    var request = new MockHttpServletRequest("POST", "/api/mcp");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    var attempt = ReviewProjectSaveTrace.start(null, 2L, REVISION);
    attempt.finish("success");
    complete(TransactionSynchronization.STATUS_COMMITTED);
    assertTrue(logs.list.getFirst().getFormattedMessage().contains("route=mcp"));
  }
}
