package com.box.l10n.mojito.service.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class ReviewFeatureServiceTransactionTest {

  @Test
  public void searchReviewFeaturesCommitsReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewFeatureService service = service(transactionManager);

    SearchReviewFeaturesView result = service.searchReviewFeatures("query", true, 10);

    assertSame(service.searchView, result);
    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createReviewFeatureCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewFeatureService service = service(transactionManager);

    ReviewFeatureService.ReviewFeatureDetail result =
        service.createReviewFeature("Feature", true, List.of(1L));

    assertSame(service.detail, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateReviewFeatureRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewFeatureService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.updateReviewFeature(1L, "Feature", true, List.of());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updateReviewFeature to rethrow the failure");
  }

  @Test
  public void deleteReviewFeatureCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewFeatureService service = service(transactionManager);

    service.deleteReviewFeature(1L);

    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void batchUpsertRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestReviewFeatureService service = service(transactionManager);
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

  private TestReviewFeatureService service(PlatformTransactionManager transactionManager) {
    return new TestReviewFeatureService(transactionManager);
  }

  private static class TestReviewFeatureService extends ReviewFeatureService {
    SearchReviewFeaturesView searchView = new SearchReviewFeaturesView(List.of(), 0);
    ReviewFeatureDetail detail =
        new ReviewFeatureDetail(1L, null, null, "Feature", true, List.of());
    RuntimeException failure;

    TestReviewFeatureService(PlatformTransactionManager transactionManager) {
      super(
          mock(ReviewFeatureRepository.class),
          mock(ReviewAutomationRepository.class),
          mock(RepositoryRepository.class),
          mock(UserService.class),
          transactionManager);
    }

    @Override
    SearchReviewFeaturesView searchReviewFeaturesNoTx(
        String searchQuery, Boolean enabled, Integer limit) {
      if (failure != null) {
        throw failure;
      }
      return searchView;
    }

    @Override
    ReviewFeatureDetail createReviewFeatureNoTx(
        String name, Boolean enabled, List<Long> repositoryIds) {
      if (failure != null) {
        throw failure;
      }
      return detail;
    }

    @Override
    ReviewFeatureDetail updateReviewFeatureNoTx(
        Long featureId, String name, Boolean enabled, List<Long> repositoryIds) {
      if (failure != null) {
        throw failure;
      }
      return detail;
    }

    @Override
    void deleteReviewFeatureNoTx(Long featureId) {
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    BatchUpsertResult batchUpsertNoTx(List<BatchUpsertRow> rows) {
      if (failure != null) {
        throw failure;
      }
      return new BatchUpsertResult(0, 0);
    }
  }
}
