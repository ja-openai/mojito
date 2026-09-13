package com.box.l10n.mojito.queue;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails startup early for async job queue configuration that would be unsafe at runtime. */
@Component
@ConditionalOnProperty(name = "l10n.org.async-job-queue.enabled", havingValue = "true")
class AsyncJobQueuePropertiesValidator implements InitializingBean {

  private final AsyncJobQueueProperties asyncJobQueueProperties;

  AsyncJobQueuePropertiesValidator(AsyncJobQueueProperties asyncJobQueueProperties) {
    this.asyncJobQueueProperties = asyncJobQueueProperties;
  }

  @Override
  public void afterPropertiesSet() {
    AsyncJobQueueValidation.validateProperties(asyncJobQueueProperties);
  }
}
