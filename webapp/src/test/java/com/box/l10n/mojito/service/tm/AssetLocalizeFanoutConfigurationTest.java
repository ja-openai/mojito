package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.queue.AssetLocalizeFanoutStore;
import java.util.Map;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

public class AssetLocalizeFanoutConfigurationTest {
  @Test
  public void explicitWorkerAndDefaultConsumerBothReconcile() {
    assertReconciler("jdbc", "true", true);
    assertReconciler("jdbc", null, true);
  }

  @Test
  public void apiConsumerDisabledNeverReconciles() {
    assertReconciler("jdbc", "false", false);
  }

  @Test
  public void inMemoryQueueNeverInstantiatesJdbcReconciler() {
    assertReconciler("in-memory", "true", false);
    assertReconciler("in-memory", null, false);
  }

  @Test
  public void durableInputReaderRemainsAvailableAfterAllQueueFlagsAreDisabled() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "disabled",
                  Map.of(
                      "l10n.org.async-job-queue.enabled", "false",
                      "l10n.org.async-job-queue.asset-localize.enabled", "false")));
      context.registerBean(
          org.springframework.jdbc.core.JdbcTemplate.class,
          () -> mock(org.springframework.jdbc.core.JdbcTemplate.class));
      context.registerBean(
          com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.class,
          () -> mock(com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.class));
      context.registerBean(
          com.box.l10n.mojito.json.ObjectMapper.class,
          () -> new com.box.l10n.mojito.json.ObjectMapper());
      context.register(AssetLocalizeFanoutInput.class);
      context.refresh();
      assertThat(context.getBeansOfType(AssetLocalizeFanoutInput.class)).hasSize(1);
    }
  }

  private void assertReconciler(String store, String consumer, boolean expected) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      java.util.HashMap<String, Object> properties =
          new java.util.HashMap<>(
              Map.of(
                  "l10n.org.async-job-queue.enabled", "true",
                  "l10n.org.async-job-queue.asset-localize.enabled", "true",
                  "l10n.org.async-job-queue.asset-localize.producer-enabled", "false",
                  "l10n.org.async-job-queue.asset-localize.fanout-enabled", "false",
                  "l10n.org.async-job-queue.store", store));
      if (consumer != null)
        properties.put("l10n.org.async-job-queue.queues.assetlocalize.consumer-enabled", consumer);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("fixture", properties));
      context
          .getBeanFactory()
          .registerSingleton("fanoutStore", mock(AssetLocalizeFanoutStore.class));
      context
          .getBeanFactory()
          .registerSingleton("fanoutService", mock(AssetLocalizeFanoutService.class));
      context.register(AssetLocalizeFanoutReconciler.class);
      context.refresh();
      assertThat(context.getBeansOfType(AssetLocalizeFanoutReconciler.class))
          .hasSize(expected ? 1 : 0);
    }
  }
}
