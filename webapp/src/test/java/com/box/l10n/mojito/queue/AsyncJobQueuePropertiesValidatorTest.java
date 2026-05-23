package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueuePropertiesValidatorTest {

  @Test
  public void acceptsDefaultProperties() throws Exception {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    validator.afterPropertiesSet();

    assertThat(properties.getStore()).isEqualTo("in-memory");
  }

  @Test
  public void validatorBeanIsNotCreatedByDefaultSoInvalidQueuePropertiesStayInert() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(AsyncJobQueuePropertiesValidator.class, InvalidPropertiesTestConfig.class);

      context.refresh();

      assertThat(context.getBeansOfType(AsyncJobQueuePropertiesValidator.class)).isEmpty();
    }
  }

  @Test
  public void validatorBeanFailsStartupWhenQueueIsEnabledAndPropertiesAreInvalid() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of("l10n.org.async-job-queue.enabled=true").applyTo(context);
      context.register(AsyncJobQueuePropertiesValidator.class, InvalidPropertiesTestConfig.class);

      BeanCreationException exception = assertThrows(BeanCreationException.class, context::refresh);

      assertThat(exception).hasRootCauseInstanceOf(IllegalArgumentException.class);
      assertThat(exception.getRootCause()).hasMessageContaining("store must be one of");
    }
  }

  @Test
  public void rejectsInvalidStoreBeforeConditionalBeansFailIndirectly() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStore("redis");
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("store must be one of");
  }

  @Test
  public void rejectsNonPositiveStatusMetricsInterval() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStatusMetricsIntervalMs(0);
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("statusMetricsIntervalMs must be > 0");
  }

  @Test
  public void rejectsExcessiveStatusMetricsInterval() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStatusMetricsIntervalMs(
        AsyncJobQueueValidation.STATUS_METRICS_INTERVAL_MS_MAX + 1);
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("statusMetricsIntervalMs must be <=");
  }

  @Test
  public void rejectsInvalidRetentionSettingsBeforeScheduledCleanupStarts() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.getRetention().setBatchSize(AsyncJobQueueValidation.RETENTION_BATCH_SIZE_MAX + 1);
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("retention.batchSize must be <=");
  }

  @Test
  public void rejectsInvalidJdbcDialectWhenJdbcStoreIsSelected() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.setStore("jdbc");
    properties.setJdbcDialect("sqlite");
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("Unsupported async job queue JDBC dialect: sqlite");
  }

  @Test
  public void rejectsInvalidConfiguredQueueName() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    properties.getQueues().put(" ", new AsyncJobQueueProperties.QueueSettings());
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("queueName must not be blank");
  }

  @Test
  public void rejectsInvalidConfiguredQueueSettingsEvenWithoutHandler() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setMaxConcurrency(0);
    properties.getQueues().put("stats", queueSettings);
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);

    assertThat(exception).hasMessageContaining("maxConcurrency must be > 0");
  }

  @Test
  public void rejectsExcessiveQueueSettingsBeforeRuntimeStartup() {
    AsyncJobQueueProperties properties = new AsyncJobQueueProperties();
    AsyncJobQueueProperties.QueueSettings queueSettings =
        new AsyncJobQueueProperties.QueueSettings();
    queueSettings.setClaimBatchSize(AsyncJobQueueValidation.CLAIM_BATCH_SIZE_MAX + 1);
    properties.getQueues().put("stats", queueSettings);
    AsyncJobQueuePropertiesValidator validator = new AsyncJobQueuePropertiesValidator(properties);

    IllegalArgumentException claimBatchException =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);
    assertThat(claimBatchException).hasMessageContaining("claimBatchSize must be <=");

    queueSettings.setClaimBatchSize(AsyncJobQueueValidation.CLAIM_BATCH_SIZE_MAX);
    queueSettings.setMaxConcurrency(AsyncJobQueueValidation.MAX_CONCURRENCY_MAX + 1);

    IllegalArgumentException maxConcurrencyException =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);
    assertThat(maxConcurrencyException).hasMessageContaining("maxConcurrency must be <=");

    queueSettings.setMaxConcurrency(AsyncJobQueueValidation.MAX_CONCURRENCY_MAX);
    queueSettings.setMaxAttempts(AsyncJobQueueValidation.MAX_ATTEMPTS_MAX + 1);

    IllegalArgumentException maxAttemptsException =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);
    assertThat(maxAttemptsException).hasMessageContaining("maxAttempts must be <=");

    queueSettings.setMaxAttempts(AsyncJobQueueValidation.MAX_ATTEMPTS_MAX);
    queueSettings.setShutdownAwaitTerminationMs(
        AsyncJobQueueValidation.SHUTDOWN_AWAIT_TERMINATION_MS_MAX + 1);

    IllegalArgumentException shutdownAwaitException =
        assertThrows(IllegalArgumentException.class, validator::afterPropertiesSet);
    assertThat(shutdownAwaitException)
        .hasMessageContaining("shutdownAwaitTerminationMs must be <=");
  }

  @Configuration
  static class InvalidPropertiesTestConfig {

    @Bean
    AsyncJobQueueProperties asyncJobQueueProperties() {
      AsyncJobQueueProperties asyncJobQueueProperties = new AsyncJobQueueProperties();
      asyncJobQueueProperties.setStore("redis");
      return asyncJobQueueProperties;
    }
  }
}
