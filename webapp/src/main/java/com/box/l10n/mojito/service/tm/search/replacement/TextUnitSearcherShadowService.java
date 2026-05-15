package com.box.l10n.mojito.service.tm.search.replacement;

import com.box.l10n.mojito.service.tm.search.TextUnitAndWordCount;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.replacement.hibernatecriteria.HibernateCriteriaTextUnitSearcher;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(value = "l10n.text-unit-searcher.shadow.enabled", havingValue = "true")
public class TextUnitSearcherShadowService {

  private static final Logger logger = LoggerFactory.getLogger(TextUnitSearcherShadowService.class);

  private final HibernateCriteriaTextUnitSearcher hibernateCriteriaTextUnitSearcher;
  private final boolean failOnMismatch;

  public TextUnitSearcherShadowService(
      HibernateCriteriaTextUnitSearcher hibernateCriteriaTextUnitSearcher,
      @Value("${l10n.text-unit-searcher.shadow.fail-on-mismatch:false}") boolean failOnMismatch) {
    this.hibernateCriteriaTextUnitSearcher = hibernateCriteriaTextUnitSearcher;
    this.failOnMismatch = failOnMismatch;
    logger.info(
        "TextUnitSearcher shadow comparison is enabled with Hibernate Criteria"
            + " (failOnMismatch={})",
        failOnMismatch);
  }

  public void compareSearch(
      TextUnitSearcherParameters parameters,
      List<TextUnitDTO> nativeResult,
      long nativeDurationMillis) {
    compareSearch(parameters, nativeResult, nativeDurationMillis, null);
  }

  public void compareSearch(
      TextUnitSearcherParameters parameters,
      List<TextUnitDTO> nativeResult,
      long nativeDurationMillis,
      Supplier<TimedResult<List<TextUnitDTO>>> nativeSearchSupplier) {
    long criteriaStartedNanos = System.nanoTime();
    try {
      boolean compareOrder = TextUnitSearchResultComparator.shouldCompareOrder(parameters);
      List<TextUnitDTO> criteriaResult = hibernateCriteriaTextUnitSearcher.search(parameters);
      TextUnitSearchResultComparator.Comparison comparison =
          TextUnitSearchResultComparator.compareSearch(nativeResult, criteriaResult, compareOrder);
      long criteriaDurationMillis = elapsedMillis(criteriaStartedNanos);

      if (comparison.matches()) {
        logSearchComparison(
            comparison,
            nativeResult,
            criteriaResult,
            compareOrder,
            nativeDurationMillis,
            criteriaDurationMillis);
        return;
      }

      String mismatchMessage =
          confirmSearchMismatch(
              parameters, comparison.message(), compareOrder, nativeSearchSupplier);
      if (mismatchMessage != null) {
        logSearchComparison(
            comparison,
            nativeResult,
            criteriaResult,
            compareOrder,
            nativeDurationMillis,
            criteriaDurationMillis);
        handleMismatch("search", mismatchMessage, nativeDurationMillis, criteriaDurationMillis);
      }
    } catch (TextUnitSearcherShadowException e) {
      throw e;
    } catch (RuntimeException e) {
      handleException("search", e, nativeDurationMillis, elapsedMillis(criteriaStartedNanos));
    }
  }

  public void compareCount(
      TextUnitSearcherParameters parameters,
      TextUnitAndWordCount nativeResult,
      long nativeDurationMillis) {
    compareCount(parameters, nativeResult, nativeDurationMillis, null);
  }

  public void compareCount(
      TextUnitSearcherParameters parameters,
      TextUnitAndWordCount nativeResult,
      long nativeDurationMillis,
      Supplier<TimedResult<TextUnitAndWordCount>> nativeCountSupplier) {
    long criteriaStartedNanos = System.nanoTime();
    try {
      TextUnitAndWordCount criteriaResult =
          hibernateCriteriaTextUnitSearcher.countTextUnitAndWordCount(parameters);
      TextUnitSearchResultComparator.Comparison comparison =
          TextUnitSearchResultComparator.compareCount(nativeResult, criteriaResult);
      long criteriaDurationMillis = elapsedMillis(criteriaStartedNanos);

      if (comparison.matches()) {
        logCountComparison(
            comparison, nativeResult, criteriaResult, nativeDurationMillis, criteriaDurationMillis);
        return;
      }

      String mismatchMessage =
          confirmCountMismatch(parameters, comparison.message(), nativeCountSupplier);
      if (mismatchMessage != null) {
        logCountComparison(
            comparison, nativeResult, criteriaResult, nativeDurationMillis, criteriaDurationMillis);
        handleMismatch("count", mismatchMessage, nativeDurationMillis, criteriaDurationMillis);
      }
    } catch (TextUnitSearcherShadowException e) {
      throw e;
    } catch (RuntimeException e) {
      handleException("count", e, nativeDurationMillis, elapsedMillis(criteriaStartedNanos));
    }
  }

  private String confirmSearchMismatch(
      TextUnitSearcherParameters parameters,
      String initialMismatchMessage,
      boolean compareOrder,
      Supplier<TimedResult<List<TextUnitDTO>>> nativeSearchSupplier) {
    if (nativeSearchSupplier == null) {
      return initialMismatchMessage;
    }

    long retryStartedNanos = System.nanoTime();
    try {
      TimedResult<List<TextUnitDTO>> retryNativeResult = nativeSearchSupplier.get();
      long retryCriteriaStartedNanos = System.nanoTime();
      List<TextUnitDTO> retryCriteriaResult = hibernateCriteriaTextUnitSearcher.search(parameters);
      long retryCriteriaDurationMillis = elapsedMillis(retryCriteriaStartedNanos);
      TextUnitSearchResultComparator.Comparison retryComparison =
          TextUnitSearchResultComparator.compareSearch(
              retryNativeResult.result(), retryCriteriaResult, compareOrder);

      logSearchComparison(
          retryComparison,
          retryNativeResult.result(),
          retryCriteriaResult,
          compareOrder,
          retryNativeResult.durationMillis(),
          retryCriteriaDurationMillis);

      if (retryComparison.matches()) {
        logger.warn(
            "TextUnitSearcher shadow search mismatch ignored after retry; the first comparison"
                + " likely crossed concurrent database changes: initialMismatch={}",
            initialMismatchMessage);
        return null;
      }
      return initialMismatchMessage + "; retry mismatch: " + retryComparison.message();
    } catch (TextUnitSearcherShadowException e) {
      throw e;
    } catch (RuntimeException e) {
      handleException("search retry", e, -1, elapsedMillis(retryStartedNanos));
      return initialMismatchMessage;
    }
  }

  private String confirmCountMismatch(
      TextUnitSearcherParameters parameters,
      String initialMismatchMessage,
      Supplier<TimedResult<TextUnitAndWordCount>> nativeCountSupplier) {
    if (nativeCountSupplier == null) {
      return initialMismatchMessage;
    }

    long retryStartedNanos = System.nanoTime();
    try {
      TimedResult<TextUnitAndWordCount> retryNativeResult = nativeCountSupplier.get();
      long retryCriteriaStartedNanos = System.nanoTime();
      TextUnitAndWordCount retryCriteriaResult =
          hibernateCriteriaTextUnitSearcher.countTextUnitAndWordCount(parameters);
      long retryCriteriaDurationMillis = elapsedMillis(retryCriteriaStartedNanos);
      TextUnitSearchResultComparator.Comparison retryComparison =
          TextUnitSearchResultComparator.compareCount(
              retryNativeResult.result(), retryCriteriaResult);

      logCountComparison(
          retryComparison,
          retryNativeResult.result(),
          retryCriteriaResult,
          retryNativeResult.durationMillis(),
          retryCriteriaDurationMillis);

      if (retryComparison.matches()) {
        logger.warn(
            "TextUnitSearcher shadow count mismatch ignored after retry; the first comparison"
                + " likely crossed concurrent database changes: initialMismatch={}",
            initialMismatchMessage);
        return null;
      }
      return initialMismatchMessage + "; retry mismatch: " + retryComparison.message();
    } catch (TextUnitSearcherShadowException e) {
      throw e;
    } catch (RuntimeException e) {
      handleException("count retry", e, -1, elapsedMillis(retryStartedNanos));
      return initialMismatchMessage;
    }
  }

  private void logSearchComparison(
      TextUnitSearchResultComparator.Comparison comparison,
      List<TextUnitDTO> nativeResult,
      List<TextUnitDTO> criteriaResult,
      boolean compareOrder,
      long nativeDurationMillis,
      long criteriaDurationMillis) {
    logger.info(
        "TextUnitSearcher shadow search comparison completed: matches={}, nativeDurationMs={},"
            + " criteriaDurationMs={}, nativeResultSize={}, criteriaResultSize={}, compareOrder={}",
        comparison.matches(),
        nativeDurationMillis,
        criteriaDurationMillis,
        size(nativeResult),
        size(criteriaResult),
        compareOrder);
  }

  private void logCountComparison(
      TextUnitSearchResultComparator.Comparison comparison,
      TextUnitAndWordCount nativeResult,
      TextUnitAndWordCount criteriaResult,
      long nativeDurationMillis,
      long criteriaDurationMillis) {
    logger.info(
        "TextUnitSearcher shadow count comparison completed: matches={}, nativeDurationMs={},"
            + " criteriaDurationMs={}, nativeTextUnitCount={}, criteriaTextUnitCount={},"
            + " nativeWordCount={}, criteriaWordCount={}",
        comparison.matches(),
        nativeDurationMillis,
        criteriaDurationMillis,
        textUnitCount(nativeResult),
        textUnitCount(criteriaResult),
        textUnitWordCount(nativeResult),
        textUnitWordCount(criteriaResult));
  }

  private void handleMismatch(
      String operation, String message, long nativeDurationMillis, long criteriaDurationMillis) {
    String fullMessage =
        "TextUnitSearcher shadow "
            + operation
            + " mismatch with Hibernate Criteria (nativeDurationMs="
            + nativeDurationMillis
            + ", criteriaDurationMs="
            + criteriaDurationMillis
            + "): "
            + message;
    logger.error(fullMessage);
    if (failOnMismatch) {
      throw new TextUnitSearcherShadowException(fullMessage);
    }
  }

  private void handleException(
      String operation,
      RuntimeException e,
      long nativeDurationMillis,
      long criteriaDurationMillis) {
    String message =
        "TextUnitSearcher shadow "
            + operation
            + " failed while running Hibernate Criteria comparison (nativeDurationMs="
            + nativeDurationMillis
            + ", criteriaDurationMs="
            + criteriaDurationMillis
            + ")";
    logger.error(message, e);
    if (failOnMismatch) {
      throw new TextUnitSearcherShadowException(message, e);
    }
  }

  private int size(List<TextUnitDTO> result) {
    return result == null ? -1 : result.size();
  }

  private long textUnitCount(TextUnitAndWordCount result) {
    return result == null ? -1 : result.getTextUnitCount();
  }

  private long textUnitWordCount(TextUnitAndWordCount result) {
    return result == null ? -1 : result.getTextUnitWordCount();
  }

  private long elapsedMillis(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  public record TimedResult<T>(T result, long durationMillis) {}

  public static class TextUnitSearcherShadowException extends RuntimeException {

    public TextUnitSearcherShadowException(String message) {
      super(message);
    }

    public TextUnitSearcherShadowException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
