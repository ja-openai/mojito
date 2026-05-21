package com.box.l10n.mojito.service.tm.search;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class TextUnitSearcherRetryTest {

  @Test
  public void searchRetriesTheTransactionalBody() {
    RetryTestTextUnitSearcher textUnitSearcher = new RetryTestTextUnitSearcher();

    List<TextUnitDTO> textUnitDTOs = textUnitSearcher.search(new TextUnitSearcherParameters());

    assertEquals(3, textUnitSearcher.searchAttempts);
    assertEquals(List.of(500L, 1000L), textUnitSearcher.backoffs);
    assertEquals(1, textUnitDTOs.size());
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
  }

  private static class RetryTestTextUnitSearcher extends TextUnitSearcher {
    int searchAttempts;
    int countAttempts;
    List<Long> backoffs = new java.util.ArrayList<>();

    RetryTestTextUnitSearcher() {
      super(null);
    }

    @Override
    public List<TextUnitDTO> searchWithTransaction(TextUnitSearcherParameters searchParameters) {
      searchAttempts++;
      if (searchAttempts < 3) {
        throw new IllegalStateException("retry search");
      }
      return List.of(new TextUnitDTO());
    }

    @Override
    public TextUnitAndWordCount countTextUnitAndWordCountWithTransaction(
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
