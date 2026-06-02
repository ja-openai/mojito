package com.box.l10n.mojito.rest.admin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "l10n.linguist-time-spent.hybrid")
public record LinguistTimeSpentHybridProperties(
    @DefaultValue("PT1S") Duration convertToAsyncAfter,
    @DefaultValue("PT120S") Duration recommendedPollingDuration,
    @DefaultValue Pool pool) {

  record Pool(
      @DefaultValue("2") int corePoolSize,
      @DefaultValue("4") int maxPoolSize,
      @DefaultValue("100") int queueCapacity,
      @DefaultValue("linguist-time-spent") String threadNamePrefix) {}
}
