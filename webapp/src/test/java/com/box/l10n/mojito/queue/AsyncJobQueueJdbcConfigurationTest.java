package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.PlatformTransactionManager;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {AsyncJobQueueProperties.class, AsyncJobQueueConfiguration.class},
    properties = {
      "l10n.org.async-job-queue.enabled=true",
      "l10n.org.async-job-queue.store=jdbc",
      "l10n.org.async-job-queue.jdbc-dialect=postgresql"
    })
public class AsyncJobQueueJdbcConfigurationTest {

  @MockBean NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  @MockBean PlatformTransactionManager transactionManager;

  @Autowired AsyncJobStore asyncJobStore;

  @Test
  public void jdbcStorePropertyCreatesJdbcAsyncJobStoreBean() {
    assertThat(asyncJobStore).isInstanceOf(JdbcAsyncJobStore.class);
  }
}
