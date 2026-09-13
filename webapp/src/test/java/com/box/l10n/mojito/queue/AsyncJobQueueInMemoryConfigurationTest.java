package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {AsyncJobQueueProperties.class, AsyncJobQueueConfiguration.class},
    properties = {"l10n.org.async-job-queue.enabled=true"})
public class AsyncJobQueueInMemoryConfigurationTest {

  @Autowired AsyncJobQueueProperties asyncJobQueueProperties;
  @Autowired AsyncJobStore asyncJobStore;

  @Test
  public void missingStorePropertyDefaultsToInMemoryAsyncJobStoreBean() {
    assertThat(asyncJobQueueProperties.getStore()).isEqualTo("in-memory");
    assertThat(asyncJobStore).isInstanceOf(InMemoryAsyncJobStore.class);
  }
}
