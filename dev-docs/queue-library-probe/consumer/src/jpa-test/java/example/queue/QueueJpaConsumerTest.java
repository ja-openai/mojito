package example.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.box.l10n.mojito.queue.AsyncJobHandler;
import com.box.l10n.mojito.queue.AsyncJobHandlerResult;
import com.box.l10n.mojito.queue.AsyncJobId;
import com.box.l10n.mojito.queue.AsyncJobQueueConfiguration;
import com.box.l10n.mojito.queue.AsyncJobQueueCoordinator;
import com.box.l10n.mojito.queue.AsyncJobQueueProperties;
import com.box.l10n.mojito.queue.AsyncJobRecord;
import com.box.l10n.mojito.queue.AsyncJobStatus;
import com.box.l10n.mojito.queue.AsyncJobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Public JAR consumer with a host-owned Hibernate setup, no Mojito or transaction advice. */
public class QueueJpaConsumerTest {

  private Fixture fixture;

  @Before
  public void createFixture() {
    fixture = new Fixture();
  }

  @After
  public void closeFixture() throws Exception {
    fixture.close();
    assertNoTransactionResources();
  }

  @Test
  public void enqueueSuspendsJpaResourcesAndSurvivesOuterRollback() throws Exception {
    fixture.open(null);
    AtomicReference<AsyncJobId> id = new AtomicReference<>();
    fixture
        .businessTransaction()
        .executeWithoutResult(
            status -> {
              fixture.persist(1L);
              EntityManager outer = fixture.entityManager();
              Connection outerConnection = fixture.sharedConnection();
              assertIsolation(outerConnection, Connection.TRANSACTION_SERIALIZABLE);
              fixture.afterInsert =
                  inner -> {
                    assertThat(fixture.entityManager()).isNotSameAs(outer);
                    assertThat(inner).isNotSameAs(outerConnection);
                    assertIsolation(inner, Connection.TRANSACTION_READ_COMMITTED);
                    assertThat(
                            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
                  };
              id.set(fixture.store.enqueueNow("example", "independent intent"));
              assertThat(fixture.entityManager()).isSameAs(outer);
              assertThat(fixture.sharedConnection()).isSameAs(outerConnection);
              assertIsolation(outerConnection, Connection.TRANSACTION_SERIALIZABLE);
              assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                  .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
              assertThat(status.isRollbackOnly()).isFalse();
              status.setRollbackOnly();
            });

    assertNoTransactionResources();
    assertThat(
            fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_consumer_marker", Integer.class))
        .isZero();
    assertThat(fixture.store.getByIds(List.of(id.get())).getFirst().status())
        .isEqualTo(AsyncJobStatus.QUEUED);
  }

  @Test
  public void enqueueDoesNotInheritReadOnlyHostScope() throws Exception {
    fixture.open(null);
    TransactionTemplate outer = fixture.businessTransaction();
    outer.setReadOnly(true);
    fixture.afterInsert =
        connection -> {
          assertIsolation(connection, Connection.TRANSACTION_READ_COMMITTED);
          assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
          assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
              .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        };
    AsyncJobId id =
        outer.execute(
            status -> {
              EntityManager entityManager = fixture.entityManager();
              Connection outerConnection = fixture.sharedConnection();
              assertIsolation(outerConnection, Connection.TRANSACTION_SERIALIZABLE);
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
              AsyncJobId accepted = fixture.store.enqueueNow("example", "from read-only caller");
              assertThat(fixture.entityManager()).isSameAs(entityManager);
              assertThat(fixture.sharedConnection()).isSameAs(outerConnection);
              assertIsolation(outerConnection, Connection.TRANSACTION_SERIALIZABLE);
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
              status.setRollbackOnly();
              return accepted;
            });
    assertNoTransactionResources();
    assertThat(fixture.store.getByIds(List.of(id))).hasSize(1);
  }

  @Test
  public void failedRealQueueInsertRollsBackWithoutPoisoningOuterJpaCommit() throws Exception {
    fixture.open(null);
    RuntimeException failure = new IllegalStateException("after real queue insert");
    fixture
        .businessTransaction()
        .executeWithoutResult(
            status -> {
              fixture.persist(1L);
              EntityManager outer = fixture.entityManager();
              Connection outerConnection = fixture.sharedConnection();
              assertIsolation(outerConnection, Connection.TRANSACTION_SERIALIZABLE);
              fixture.afterInsert =
                  inner -> {
                    assertThat(inner).isNotSameAs(outerConnection);
                    assertIsolation(inner, Connection.TRANSACTION_READ_COMMITTED);
                    throw failure;
                  };
              assertThatThrownBy(() -> fixture.store.enqueueNow("example", "rolled back intent"))
                  .isSameAs(failure);
              assertThat(fixture.entityManager()).isSameAs(outer);
              assertThat(fixture.sharedConnection()).isSameAs(outerConnection);
              assertIsolation(outerConnection, Connection.TRANSACTION_SERIALIZABLE);
              assertThat(status.isRollbackOnly()).isFalse();
              fixture.persist(2L);
            });
    assertNoTransactionResources();
    assertThat(
            fixture.jdbc.queryForList(
                "SELECT id FROM queue_consumer_marker ORDER BY id", Long.class))
        .containsExactly(1L, 2L);
    assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM async_job_queue", Integer.class))
        .isZero();
  }

  @Test
  public void workerOwnsItsBusinessTransactionOutsideQueueTransactions() throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<Throwable> workerFailure = new AtomicReference<>();
    fixture.open(
        new AsyncJobHandler() {
          @Override
          public String queueName() {
            return "example";
          }

          @Override
          public AsyncJobHandlerResult process(AsyncJobRecord record) {
            fixture.workerThread.set(Thread.currentThread());
            try {
              assertNoTransactionResources();
              fixture.businessTransaction().executeWithoutResult(status -> fixture.persist(1L));
              assertNoTransactionResources();
              return AsyncJobHandlerResult.done("business committed");
            } catch (RuntimeException | Error failure) {
              workerFailure.set(failure);
              throw failure;
            }
          }

          @Override
          public void onJobDone(AsyncJobRecord record, AsyncJobHandlerResult result) {
            try {
              assertNoTransactionResources();
            } catch (RuntimeException | Error failure) {
              workerFailure.set(failure);
              throw failure;
            } finally {
              completed.countDown();
            }
          }

          @Override
          public void onJobFailedPermanently(
              AsyncJobRecord record, Throwable failure, String lastError) {
            workerFailure.compareAndSet(null, failure);
            completed.countDown();
          }
        });
    AsyncJobId id = fixture.store.enqueueNow("example", "run business transaction");
    assertThat(completed.await(10, TimeUnit.SECONDS)).as("worker completed").isTrue();
    assertThat(workerFailure.get()).isNull();
    AsyncJobRecord done = fixture.store.getByIds(List.of(id)).getFirst();
    assertThat(done.status()).isEqualTo(AsyncJobStatus.DONE);
    assertThat(done.attemptCount()).isEqualTo(1);
    assertThat(done.jobData()).isEqualTo("business committed");
    assertThat(
            fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM queue_consumer_marker", Integer.class))
        .isEqualTo(1);
    assertNoTransactionResources();
  }

  @Test
  public void bootstrapRejectsJdbcVersusJpaManagerDataSourceMismatch() throws Exception {
    assertDataSourceMismatchRejected(false);
  }

  @Test
  public void bootstrapRejectsManagerAndJdbcAgreementWithAnotherFactoryDataSource()
      throws Exception {
    assertDataSourceMismatchRejected(true);
  }

  private void assertDataSourceMismatchRejected(boolean useOtherForManager) throws Exception {
    fixture.open(null);
    DataSource other = mock(DataSource.class);
    JpaTransactionManager manager = new JpaTransactionManager(fixture.factory.getObject());
    if (useOtherForManager) manager.setDataSource(other);
    try (var context = fixture.newContext(new NamedParameterJdbcTemplate(other), manager)) {
      assertThatThrownBy(context::refresh)
          .hasRootCauseInstanceOf(IllegalArgumentException.class)
          .rootCause()
          .hasMessageContaining(
              useOtherForManager
                  ? "Spring-managed EntityManagerFactory using the same DataSource"
                  : "JDBC template and transaction manager must use the same DataSource");
    }
    verifyNoInteractions(other);
    assertNoTransactionResources();
  }

  private static void assertNoTransactionResources() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
  }

  private static void assertIsolation(Connection connection, int isolation) {
    try {
      assertThat(connection.getAutoCommit()).isFalse();
      assertThat(connection.getTransactionIsolation()).isEqualTo(isolation);
    } catch (SQLException failure) {
      throw new AssertionError("Cannot inspect physical transaction isolation", failure);
    }
  }

  @Entity(name = "QueueConsumerMarker")
  @Table(name = "queue_consumer_marker")
  public static class HostMarker {
    @Id private Long id;

    @Column(name = "marker_value")
    private String value;

    protected HostMarker() {}

    HostMarker(Long id) {
      this.id = id;
      this.value = "host work";
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AsyncJobQueueProperties.class)
  @Import(AsyncJobQueueConfiguration.class)
  static class HostConfiguration {}

  private static final class Fixture {
    final DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            "jdbc:hsqldb:mem:external_jpa_" + UUID.randomUUID() + ";hsqldb.tx=mvcc", "sa", "");
    final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    final LocalContainerEntityManagerFactoryBean factory =
        new LocalContainerEntityManagerFactoryBean();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final AtomicReference<Thread> workerThread = new AtomicReference<>();
    Consumer<Connection> afterInsert = connection -> {};
    JpaTransactionManager manager;
    AsyncJobStore store;
    AnnotationConfigApplicationContext context;
    ThreadPoolTaskScheduler scheduler;
    boolean databaseOpened;

    void open(AsyncJobHandler handler) {
      databaseOpened = true;
      jdbc.setQueryTimeout(5);
      // Disposable HSQL fixture, not an engine resource or Flyway adoption/upgrade path.
      jdbc.execute(
          """
          CREATE TABLE async_job_queue (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY(START WITH 1) PRIMARY KEY,
            queue_name VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
            available_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6),
            worker_id VARCHAR(128), lease_token VARCHAR(64), job_data LONGVARCHAR NOT NULL,
            attempt_count INTEGER DEFAULT 0 NOT NULL, last_error LONGVARCHAR,
            created_date TIMESTAMP(6) NOT NULL, updated_date TIMESTAMP(6) NOT NULL
          )
          """);
      factory.setDataSource(dataSource);
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setManagedTypes(PersistenceManagedTypes.of(HostMarker.class.getName()));
      factory.setJpaPropertyMap(
          Map.of(
              "hibernate.hbm2ddl.auto", "create-only",
              "hibernate.hbm2ddl.halt_on_error", "true",
              "hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_HOLD",
              "jakarta.persistence.validation.mode", "none"));
      factory.afterPropertiesSet();
      manager = new JpaTransactionManager(factory.getObject());
      NamedParameterJdbcTemplate named =
          new NamedParameterJdbcTemplate(jdbc) {
            @Override
            public int update(
                String sql, SqlParameterSource parameters, KeyHolder keys, String[] columns) {
              int rows = super.update(sql, parameters, keys, columns);
              assertThat(rows).isEqualTo(1);
              afterInsert.accept(sharedConnection());
              return rows;
            }
          };
      context = newContext(named, manager);
      if (handler != null) {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
        context.getBeanFactory().registerSingleton("meterRegistry", metrics);
        context.registerBean(AsyncJobHandler.class, () -> handler);
        context.register(AsyncJobQueueCoordinator.class);
      }
      context.refresh();
      store = context.getBean(AsyncJobStore.class);
    }

    AnnotationConfigApplicationContext newContext(
        NamedParameterJdbcTemplate named, JpaTransactionManager manager) {
      var result = new AnnotationConfigApplicationContext();
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=hsql",
              "l10n.org.async-job-queue.queues.example.poll-interval-ms=10",
              "l10n.org.async-job-queue.queues.example.max-poll-interval-ms=50",
              "l10n.org.async-job-queue.queues.example.max-concurrency=1",
              "l10n.org.async-job-queue.queues.example.claim-batch-size=1",
              "l10n.org.async-job-queue.queues.example.max-attempts=1",
              "l10n.org.async-job-queue.queues.example.heartbeat-interval-ms=0",
              "l10n.org.async-job-queue.queues.example.shutdown-await-termination-ms=5000")
          .applyTo(result);
      result.registerBean(NamedParameterJdbcTemplate.class, () -> named);
      // The host already initialized this manager; do not rederive its factory settings.
      result.getBeanFactory().registerSingleton("transactionManager", manager);
      result.register(HostConfiguration.class);
      return result;
    }

    TransactionTemplate businessTransaction() {
      var transaction = new TransactionTemplate(manager);
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
      transaction.setTimeout(5);
      return transaction;
    }

    EntityManager entityManager() {
      return ((EntityManagerHolder)
              TransactionSynchronizationManager.getResource(factory.getObject()))
          .getEntityManager();
    }

    Connection sharedConnection() {
      Connection connection =
          ((ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource))
              .getConnection();
      Connection jpaConnection =
          entityManager().unwrap(Session.class).doReturningWork(current -> current);
      assertThat(jpaConnection).isSameAs(connection);
      return connection;
    }

    void persist(Long id) {
      entityManager().persist(new HostMarker(id));
      entityManager().flush();
      sharedConnection();
    }

    void close() throws Exception {
      try {
        if (context != null) context.close();
      } finally {
        try {
          if (scheduler != null) {
            scheduler.shutdown();
            assertThat(
                    scheduler
                        .getScheduledThreadPoolExecutor()
                        .awaitTermination(5, TimeUnit.SECONDS))
                .isTrue();
          }
          if (workerThread.get() != null) {
            workerThread.get().join(5_000);
            assertThat(workerThread.get().isAlive()).isFalse();
          }
        } finally {
          try {
            factory.destroy();
          } finally {
            try {
              if (databaseOpened) jdbc.execute("SHUTDOWN");
            } finally {
              metrics.close();
            }
          }
        }
      }
    }
  }
}
