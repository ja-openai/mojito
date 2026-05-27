package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.team.TeamRepository;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class ReviewAutomationServiceTransactionTest {

  @Test
  public void searchReviewAutomationsCommitsReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewAutomationService service = service(transactionManager);

    SearchReviewAutomationsView result = service.searchReviewAutomations("query", true, 10);

    assertSame(service.searchView, result);
    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createReviewAutomationCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewAutomationService service = service(transactionManager);

    ReviewAutomationService.ReviewAutomationDetail result =
        service.createReviewAutomation(
            "Automation", true, "0 0 12 * * ?", "UTC", 1L, 1, 2000, true, List.of(1L));

    assertSame(service.detail, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateReviewAutomationRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewAutomationService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.updateReviewAutomation(
          1L, "Automation", true, "0 0 12 * * ?", "UTC", 1L, 1, 2000, true, List.of());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updateReviewAutomation to rethrow the failure");
  }

  @Test
  public void deleteReviewAutomationCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewAutomationService service = service(transactionManager);

    service.deleteReviewAutomation(1L);

    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void batchUpsertRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewAutomationService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.batchUpsert(List.of());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected batchUpsert to rethrow the failure");
  }

  @Test
  public void previewScheduleCommitsReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewAutomationService service = service(transactionManager);

    List<java.time.ZonedDateTime> result = service.previewSchedule("0 0 12 * * ?", "UTC", 1);

    assertSame(service.preview, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  private TestReviewAutomationService service(PlatformTransactionManager transactionManager) {
    return new TestReviewAutomationService(transactionManager);
  }

  @SuppressWarnings("unchecked")
  private static class TestReviewAutomationService extends ReviewAutomationService {
    SearchReviewAutomationsView searchView = new SearchReviewAutomationsView(List.of(), 0);
    ReviewAutomationDetail detail =
        new ReviewAutomationDetail(
            1L,
            null,
            null,
            "Automation",
            true,
            "0 0 12 * * ?",
            "UTC",
            null,
            1,
            2000,
            true,
            new ReviewAutomationTriggerStatusView("MISSING", "NONE", null, null, null, null, true),
            List.of());
    List<java.time.ZonedDateTime> preview = List.of(java.time.ZonedDateTime.now());
    RuntimeException failure;

    TestReviewAutomationService(PlatformTransactionManager transactionManager) {
      super(
          mock(ReviewAutomationRepository.class),
          mock(ReviewFeatureRepository.class),
          mock(ReviewAutomationRunRepository.class),
          mock(TeamRepository.class),
          mock(UserService.class),
          mock(ObjectProvider.class),
          transactionManager);
    }

    @Override
    SearchReviewAutomationsView searchReviewAutomationsNoTx(
        String searchQuery, Boolean enabled, Integer limit) {
      if (failure != null) {
        throw failure;
      }
      return searchView;
    }

    @Override
    ReviewAutomationDetail createReviewAutomationNoTx(
        String name,
        Boolean enabled,
        String cronExpression,
        String timeZone,
        Long teamId,
        Integer dueDateOffsetDays,
        Integer maxWordCountPerProject,
        Boolean assignTranslator,
        List<Long> featureIds) {
      if (failure != null) {
        throw failure;
      }
      return detail;
    }

    @Override
    ReviewAutomationDetail updateReviewAutomationNoTx(
        Long automationId,
        String name,
        Boolean enabled,
        String cronExpression,
        String timeZone,
        Long teamId,
        Integer dueDateOffsetDays,
        Integer maxWordCountPerProject,
        Boolean assignTranslator,
        List<Long> featureIds) {
      if (failure != null) {
        throw failure;
      }
      return detail;
    }

    @Override
    void deleteReviewAutomationNoTx(Long automationId) {
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    BatchUpsertResult batchUpsertNoTx(List<BatchUpsertRow> rows, BatchUpsertMode mode) {
      if (failure != null) {
        throw failure;
      }
      return new BatchUpsertResult(0, 0, 0);
    }

    @Override
    List<java.time.ZonedDateTime> previewScheduleNoTx(
        String cronExpression, String timeZone, Integer previewCount) {
      if (failure != null) {
        throw failure;
      }
      return preview;
    }
  }
}
