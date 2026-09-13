package com.box.l10n.mojito.queue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Injects one acknowledgement failure after an actual JDBC commit or rollback. */
final class CommitFaultDataSource extends DelegatingDataSource {
  private static final Runnable NO_OP = () -> {};

  enum CommitFault {
    NONE,
    AFTER_COMMIT,
    ROLLBACK_BEFORE_COMMIT
  }

  final AtomicInteger connections = new AtomicInteger();
  final AtomicReference<CommitFault> fault = new AtomicReference<>(CommitFault.NONE);
  final AtomicInteger commits = new AtomicInteger();
  final AtomicInteger rollbacks = new AtomicInteger();
  final AtomicInteger injectedRollbacks = new AtomicInteger();
  final AtomicInteger faults = new AtomicInteger();
  SQLException failure;

  private final Object faultLock = new Object();
  private Runnable afterResolution = NO_OP;

  CommitFaultDataSource(DataSource target) {
    super(target);
  }

  void failNextCommit(CommitFault mode) {
    failNextCommit(mode, NO_OP);
  }

  void failNextCommit(CommitFault mode, Runnable afterResolution) {
    synchronized (faultLock) {
      failure = new SQLException("injected " + mode, "08006");
      this.afterResolution = afterResolution;
      fault.set(mode);
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    connections.incrementAndGet();
    return wrap(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    connections.incrementAndGet();
    return wrap(super.getConnection(username, password));
  }

  private Connection wrap(Connection target) {
    return (Connection)
        Proxy.newProxyInstance(
            ConnectionProxy.class.getClassLoader(),
            new Class<?>[] {ConnectionProxy.class},
            (proxy, method, arguments) -> {
              if (method.getName().equals("getTargetConnection")) {
                return target;
              }
              if (method.getName().equals("commit")) {
                CommitFault injected;
                SQLException injectedFailure;
                Runnable callback;
                // Consume the entire injection before JDBC or a blocking callback can run.
                synchronized (faultLock) {
                  injected = fault.getAndSet(CommitFault.NONE);
                  injectedFailure = failure;
                  callback = afterResolution;
                  afterResolution = NO_OP;
                }
                if (injected == CommitFault.ROLLBACK_BEFORE_COMMIT) {
                  target.rollback();
                  rollbacks.incrementAndGet();
                  injectedRollbacks.incrementAndGet();
                } else {
                  target.commit();
                  commits.incrementAndGet();
                }
                if (injected != CommitFault.NONE) {
                  faults.incrementAndGet();
                  callback.run();
                  throw injectedFailure;
                }
                return null;
              }
              if (method.getName().equals("rollback") && method.getParameterCount() == 0) {
                target.rollback();
                rollbacks.incrementAndGet();
                return null;
              }
              try {
                return method.invoke(target, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }
}
