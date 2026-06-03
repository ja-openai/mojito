package com.box.l10n.mojito.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.queue.AsyncJobQueueInspectionService;
import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AsyncJobQueueAdminWSConfigurationTest {

  @Test
  public void adminEndpointsAreNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = createContext()) {
      assertThat(context.getBeansOfType(AsyncJobQueueAdminWS.class)).isEmpty();
      assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairWS.class)).isEmpty();
    }
  }

  @Test
  public void queueAdminEndpointIsCreatedWhenQueueIsEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.enabled=true")) {
      assertThat(context.getBean(AsyncJobQueueAdminWS.class)).isNotNull();
      assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairWS.class)).isEmpty();
    }
  }

  @Test
  public void assetLocalizeRepairEndpointRequiresQueueFlag() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertThat(context.getBeansOfType(AsyncJobQueueAdminWS.class)).isEmpty();
      assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairWS.class)).isEmpty();
    }
  }

  @Test
  public void assetLocalizeRepairEndpointIsCreatedWhenBothFlagsAreEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertThat(context.getBean(AsyncJobQueueAdminWS.class)).isNotNull();
      assertThat(context.getBean(AssetLocalizeAsyncJobRepairWS.class)).isNotNull();
    }
  }

  private AnnotationConfigApplicationContext createContext(String... properties) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    if (properties.length > 0) {
      TestPropertyValues.of(properties).applyTo(context);
    }
    context.register(
        AsyncJobQueueAdminWS.class, AssetLocalizeAsyncJobRepairWS.class, AdminWSTestConfig.class);
    context.refresh();
    return context;
  }

  @Configuration
  static class AdminWSTestConfig {

    @Bean
    AsyncJobQueueInspectionService asyncJobQueueInspectionService() {
      return mock(AsyncJobQueueInspectionService.class);
    }

    @Bean
    AssetLocalizeAsyncJobRepairService assetLocalizeAsyncJobRepairService() {
      return mock(AssetLocalizeAsyncJobRepairService.class);
    }
  }
}
