package com.box.l10n.mojito.rest.glossary;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "l10n.glossary.term-index.entries.search.hybrid")
public record TermIndexEntriesHybridProperties(
    @DefaultValue("PT1S") Duration convertToAsyncAfter,
    @DefaultValue("PT120S") Duration recommendedPollingDuration,
    @DefaultValue Pool pool) {

  record Pool(
      @DefaultValue("2") int corePoolSize,
      @DefaultValue("4") int maxPoolSize,
      @DefaultValue("100") int queueCapacity,
      @DefaultValue("term-index-entry-search") String threadNamePrefix) {}
}
