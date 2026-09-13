package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.PlatformTransactionManager;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {
      AsyncJobQueueProperties.class,
      AsyncJobQueueConfiguration.class,
      AsyncJobQueueJdbcConfigurationTest.JdbcWiring.class
    },
    properties = {
      "l10n.org.async-job-queue.enabled=true",
      "l10n.org.async-job-queue.store=jdbc",
      "l10n.org.async-job-queue.jdbc-dialect=postgresql"
    })
public class AsyncJobQueueJdbcConfigurationTest {

  @Autowired AsyncJobStore asyncJobStore;

  @Test
  public void jdbcStorePropertyCreatesJdbcAsyncJobStoreBean() {
    assertThat(asyncJobStore).isInstanceOf(JdbcAsyncJobStore.class);
  }

  @TestConfiguration
  static class JdbcWiring {
    @Bean
    DataSource dataSource() {
      return mock(DataSource.class);
    }

    @Bean
    NamedParameterJdbcTemplate jdbc(DataSource dataSource) {
      return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }
}
