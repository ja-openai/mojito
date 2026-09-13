package com.box.l10n.mojito.test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ThreadBoundTransactionAdviceTest {

  @Test
  public void concurrentWovenTransactionsKeepTheirOwnDataSourceAndRestoreAdvice() throws Exception {
    String url = "jdbc:hsqldb:mem:thread_bound_advice_" + UUID.randomUUID();
    DataSource fixtureDataSource = new DriverManagerDataSource(url, "sa", "");
    DataSource backgroundDataSource = new DriverManagerDataSource(url, "sa", "");
    DataSourceTransactionManager backgroundManager =
        new DataSourceTransactionManager(backgroundDataSource);
    AnnotationTransactionAspect aspect = AnnotationTransactionAspect.aspectOf();
    TransactionManager originalManager = aspect.getTransactionManager();
    CompletableFuture<ThreadBoundTransactionAdvice> backgroundInstalled = new CompletableFuture<>();
    CountDownLatch fixtureInstalled = new CountDownLatch(1);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch transactionFinished = new CountDownLatch(1);
    CountDownLatch closeBackgroundScope = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      TransactionProbe probe = new TransactionProbe();
      var background =
          executor.submit(
              () -> {
                try (var backgroundAdvice = new ThreadBoundTransactionAdvice(backgroundManager)) {
                  backgroundInstalled.complete(backgroundAdvice);
                  try {
                    assertTrue(fixtureInstalled.await(10, TimeUnit.SECONDS));
                    probe.check(backgroundDataSource, fixtureDataSource, entered, release);
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertFalse(
                        TransactionSynchronizationManager.hasResource(backgroundDataSource));
                  } finally {
                    transactionFinished.countDown();
                    // Restore the outer scope only after the main thread has closed its scope.
                    assertTrue(closeBackgroundScope.await(10, TimeUnit.SECONDS));
                  }
                }
                return null;
              });
      ThreadBoundTransactionAdvice backgroundAdvice = backgroundInstalled.get(10, TimeUnit.SECONDS);
      try (var ignored =
          new ThreadBoundTransactionAdvice(new DataSourceTransactionManager(fixtureDataSource))) {
        fixtureInstalled.countDown();
        assertTrue("Background transaction did not start", entered.await(10, TimeUnit.SECONDS));
        try {
          probe.check(fixtureDataSource, backgroundDataSource, null, null);
        } finally {
          release.countDown();
        }
        assertTrue(transactionFinished.await(10, TimeUnit.SECONDS));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.hasResource(fixtureDataSource));
      }
      assertSame(backgroundAdvice, aspect.getTransactionManager());
      closeBackgroundScope.countDown();
      background.get(10, TimeUnit.SECONDS);
      assertSame(originalManager, aspect.getTransactionManager());
    } finally {
      fixtureInstalled.countDown();
      release.countDown();
      closeBackgroundScope.countDown();
      executor.shutdownNow();
      try {
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      } finally {
        aspect.setTransactionManager(originalManager);
        new JdbcTemplate(fixtureDataSource).execute("SHUTDOWN");
      }
    }
  }

  private static class TransactionProbe {
    @Transactional
    public void check(
        DataSource expected, DataSource other, CountDownLatch entered, CountDownLatch release)
        throws InterruptedException {
      if (entered != null) {
        entered.countDown();
        assertTrue(
            "Concurrent fixture transaction did not finish", release.await(10, TimeUnit.SECONDS));
      }
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      assertTrue(
          "Expected transaction DataSource was not bound",
          TransactionSynchronizationManager.hasResource(expected));
      assertFalse(
          "Other thread's DataSource was bound",
          TransactionSynchronizationManager.hasResource(other));
    }
  }
}
