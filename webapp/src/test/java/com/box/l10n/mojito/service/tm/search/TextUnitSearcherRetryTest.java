package com.box.l10n.mojito.service.tm.search;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class TextUnitSearcherRetryTest {

  @Test
  public void searchRetriesTheTransactionalBody() {
    RetryTestTextUnitSearcher textUnitSearcher = new RetryTestTextUnitSearcher();

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(new TextUnitSearcherParameters());

    assertEquals(3, textUnitSearcher.searchAttempts);
    assertEquals(List.of(500L, 1000L), textUnitSearcher.backoffs);
    assertEquals(1, textUnitDTOs.size());
    verify(textUnitSearcher.transactionManager, times(1)).commit(textUnitSearcher.transaction);
    verify(textUnitSearcher.transactionManager, times(2)).rollback(textUnitSearcher.transaction);
  }

  @Test
  public void countRetriesTheTransactionalBody() {
    RetryTestTextUnitSearcher textUnitSearcher = new RetryTestTextUnitSearcher();

    TextUnitAndWordCount count =
        textUnitSearcher.countTextUnitAndWordCount(new TextUnitSearcherParameters());

    assertEquals(3, textUnitSearcher.countAttempts);
    assertEquals(List.of(500L, 1000L), textUnitSearcher.backoffs);
    assertEquals(7, count.getTextUnitCount());
    assertEquals(11, count.getTextUnitWordCount());
    verify(textUnitSearcher.transactionManager, times(1)).commit(textUnitSearcher.transaction);
    verify(textUnitSearcher.transactionManager, times(2)).rollback(textUnitSearcher.transaction);
  }

  private static class RetryTestTextUnitSearcher extends TextUnitSearcher {
    int searchAttempts;
    int countAttempts;
    List<Long> backoffs = new java.util.ArrayList<>();
    PlatformTransactionManager transactionManager;
    TransactionStatus transaction;

    RetryTestTextUnitSearcher() {
      this(mock(PlatformTransactionManager.class), mock(TransactionStatus.class));
    }

    RetryTestTextUnitSearcher(
        PlatformTransactionManager transactionManager, TransactionStatus transaction) {
      super(null, transactionManager);
      this.transactionManager = transactionManager;
      this.transaction = transaction;
      when(transactionManager.getTransaction(any())).thenReturn(transaction);
    }

    @Override
    List<TextUnitDTO> searchInTransaction(TextUnitSearcherParameters searchParameters) {
      searchAttempts++;
      if (searchAttempts < 3) {
        throw new IllegalStateException("retry search");
      }
      return List.of(new TextUnitDTO());
    }

    @Override
    TextUnitAndWordCount countTextUnitAndWordCountInTransaction(
        TextUnitSearcherParameters searchParameters) {
      countAttempts++;
      if (countAttempts < 3) {
        throw new IllegalStateException("retry count");
      }
      return new TextUnitAndWordCount(7, 11);
    }

    @Override
    protected void sleepBeforeRetry(long backoffMillis) {
      backoffs.add(backoffMillis);
    }
  }
}
