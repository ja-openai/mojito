package com.box.l10n.mojito.rest.textunit;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.review.feedback.ReviewerFeedback;
import com.box.l10n.mojito.service.review.feedback.WorkbenchReviewFeedbackService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.test.ThreadBoundTransactionAdvice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class TextUnitWSFeedbackTest {
  private ThreadBoundTransactionAdvice transactionAdvice;
  private final TextUnitWS controller = new TextUnitWS();

  @Before
  public void setup() {
    var transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    transactionAdvice = new ThreadBoundTransactionAdvice(transactions);
    controller.userService = mock(UserService.class);
    when(controller.userService.isCurrentUserAdminOrPm()).thenReturn(true);
    controller.tmService = mock(TMService.class);
    controller.reviewFeedback = mock(WorkbenchReviewFeedbackService.class);
    var variant = new TMTextUnitVariant();
    variant.setId(9L);
    var current = new TMTextUnitCurrentVariant();
    current.setId(8L);
    current.setTmTextUnitVariant(variant);
    when(controller.tmService.addTMTextUnitCurrentVariant(
            anyLong(), anyLong(), anyString(), any(), any(), anyBoolean()))
        .thenReturn(current);
  }

  @After
  public void restoreTransactions() {
    transactionAdvice.close();
  }

  private TextUnitSaveRequest request() {
    var request = new TextUnitSaveRequest();
    request.setTmTextUnitId(1L);
    request.setLocaleId(2L);
    request.setTarget("Cafe\u0301");
    request.setStatus(TMTextUnitVariant.Status.APPROVED);
    request.setIncludedInLocalizedFile(true);
    return request;
  }

  @Test
  public void legacySaveRetainsNormalizationAndDoesNotCapture() {
    var saved = controller.saveTextUnit(request());
    assertEquals("Café", saved.getTarget());
    assertEquals(Long.valueOf(9L), saved.getTmTextUnitVariantId());
    verify(controller.userService).checkUserCanEditLocale(2L);
    verifyNoInteractions(controller.reviewFeedback);
  }

  @Test
  public void optedInSavePreservesRawTargetUntilGuardedCaptureAndUsesNormalWriter() {
    var request = request();
    request.setReviewedVariantId(3L);
    request.setFeedbackOperationId(UUID.randomUUID().toString());
    request.setReviewFeedback(
        new ReviewerFeedback(ReviewerFeedback.Reason.GRAMMAR, "Reason", false, false));
    when(controller.reviewFeedback.save(
            eq(request),
            eq(3L),
            eq(request.getFeedbackOperationId()),
            eq(request.getReviewFeedback()),
            any()))
        .thenAnswer(
            invocation -> {
              assertEquals("Cafe\u0301", request.getTarget());
              Supplier<TextUnitDTO> write = invocation.getArgument(4);
              return write.get();
            });
    assertEquals("Café", controller.saveTextUnit(request).getTarget());
    verify(controller.userService).checkUserCanEditLocale(2L);
    verify(controller.tmService)
        .addTMTextUnitCurrentVariant(1L, 2L, "Café", null, TMTextUnitVariant.Status.APPROVED, true);
  }

  @Test
  public void incompleteFeedbackMetadataCannotFallThroughToLegacySave() throws Exception {
    var request =
        new ObjectMapper()
            .readValue(
                "{\"tmTextUnitId\":1,\"localeId\":2,\"target\":\"Bonjour\",\"reviewFeedback\":{\"note\":\"Reason\"}}",
                TextUnitSaveRequest.class);
    when(controller.reviewFeedback.save(
            eq(request), isNull(), isNull(), eq(request.getReviewFeedback()), any()))
        .thenThrow(new IllegalArgumentException("Missing identity"));
    assertThrows(IllegalArgumentException.class, () -> controller.saveTextUnit(request));
    verifyNoInteractions(controller.tmService);
  }

  @Test
  public void acceptanceAndEvidenceShareOneTransactionWithFreshRetryReads() throws Exception {
    var acceptance =
        TextUnitWS.class
            .getMethod("saveTextUnit", TextUnitSaveRequest.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    assertEquals(
        org.springframework.transaction.annotation.Isolation.READ_COMMITTED,
        acceptance.isolation());
    var evidence =
        WorkbenchReviewFeedbackService.class
            .getMethod(
                "save",
                TextUnitDTO.class,
                Long.class,
                String.class,
                ReviewerFeedback.class,
                Supplier.class)
            .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
    assertEquals(
        org.springframework.transaction.annotation.Propagation.MANDATORY, evidence.propagation());
  }
}
