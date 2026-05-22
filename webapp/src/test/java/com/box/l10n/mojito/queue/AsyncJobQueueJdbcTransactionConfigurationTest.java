package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

public class AsyncJobQueueJdbcTransactionConfigurationTest {

  @Test
  public void jdbcStoreBeanIsTransactionalProxyWhenTransactionManagementIsEnabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      TestPropertyValues.of(
              "l10n.org.async-job-queue.enabled=true",
              "l10n.org.async-job-queue.store=jdbc",
              "l10n.org.async-job-queue.jdbc-dialect=hsql")
          .applyTo(context);
      context.register(
          AsyncJobQueueConfiguration.class,
          AsyncJobQueueProperties.class,
          TransactionTestConfig.class);
      context.refresh();

      AsyncJobStore asyncJobStore = context.getBean(AsyncJobStore.class);

      assertThat(AopUtils.isAopProxy(asyncJobStore)).isTrue();
      assertThat(AopUtils.getTargetClass(asyncJobStore)).isEqualTo(JdbcAsyncJobStore.class);
    }
  }

  @Configuration
  @EnableTransactionManagement
  @EnableConfigurationProperties(AsyncJobQueueProperties.class)
  static class TransactionTestConfig {

    @Bean
    DataSource dataSource() {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setUrl(
          "jdbc:hsqldb:mem:async_job_queue_tx_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      dataSource.setUsername("sa");
      dataSource.setPassword("");
      return dataSource;
    }

    @Bean
    NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }
}
