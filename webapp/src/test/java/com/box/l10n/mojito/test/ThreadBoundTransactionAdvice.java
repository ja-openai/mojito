package com.box.l10n.mojito.test;

import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;

/** Uses a fixture manager on the current thread and preserves background transaction semantics. */
public final class ThreadBoundTransactionAdvice
    implements PlatformTransactionManager, AutoCloseable {
  private final Set<Thread> fixtureThreads;
  private final PlatformTransactionManager fixtureManager;
  private final TransactionManager previousManager;

  public ThreadBoundTransactionAdvice(PlatformTransactionManager manager) {
    this(manager, Set.of(Thread.currentThread()));
  }

  /** Includes explicitly owned workers without changing unrelated background transactions. */
  public ThreadBoundTransactionAdvice(PlatformTransactionManager manager, Set<Thread> threads) {
    fixtureThreads = Set.copyOf(threads);
    fixtureManager = manager;
    AnnotationTransactionAspect aspect = AnnotationTransactionAspect.aspectOf();
    previousManager = aspect.getTransactionManager();
    aspect.setTransactionManager(this);
  }

  @Override
  public TransactionStatus getTransaction(TransactionDefinition definition) {
    return managerForCurrentThread().getTransaction(definition);
  }

  @Override
  public void commit(TransactionStatus status) {
    managerForCurrentThread().commit(status);
  }

  @Override
  public void rollback(TransactionStatus status) {
    managerForCurrentThread().rollback(status);
  }

  private PlatformTransactionManager managerForCurrentThread() {
    if (fixtureThreads.contains(Thread.currentThread())) {
      return fixtureManager;
    }
    if (previousManager instanceof PlatformTransactionManager manager) {
      return manager;
    }
    throw new IllegalStateException("No previous transaction manager for the background thread");
  }

  @Override
  public void close() {
    AnnotationTransactionAspect.aspectOf().setTransactionManager(previousManager);
  }
}
