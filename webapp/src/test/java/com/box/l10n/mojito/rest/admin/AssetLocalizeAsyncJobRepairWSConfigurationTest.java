package com.box.l10n.mojito.rest.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.service.tm.AssetLocalizeAsyncJobRepairService;
import org.junit.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AssetLocalizeAsyncJobRepairWSConfigurationTest {

  @Test
  public void repairEndpointIsNotCreatedByDefault() {
    try (AnnotationConfigApplicationContext context = createContext()) {
      assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairWS.class)).isEmpty();
    }
  }

  @Test
  public void repairEndpointRequiresAssetLocalizeFlag() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.enabled=true")) {
      assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairWS.class)).isEmpty();
    }
  }

  @Test
  public void repairEndpointRequiresQueueFlag() {
    try (AnnotationConfigApplicationContext context =
        createContext("l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertThat(context.getBeansOfType(AssetLocalizeAsyncJobRepairWS.class)).isEmpty();
    }
  }

  @Test
  public void repairEndpointIsCreatedWhenBothFlagsAreEnabled() {
    try (AnnotationConfigApplicationContext context =
        createContext(
            "l10n.org.async-job-queue.enabled=true",
            "l10n.org.async-job-queue.asset-localize.enabled=true")) {
      assertThat(context.getBean(AssetLocalizeAsyncJobRepairWS.class)).isNotNull();
    }
  }

  private AnnotationConfigApplicationContext createContext(String... properties) {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    if (properties.length > 0) {
      TestPropertyValues.of(properties).applyTo(context);
    }
    context.register(AssetLocalizeAsyncJobRepairWS.class, RepairWSTestConfig.class);
    context.refresh();
    return context;
  }

  @Configuration
  static class RepairWSTestConfig {

    @Bean
    AssetLocalizeAsyncJobRepairService repairService() {
      return mock(AssetLocalizeAsyncJobRepairService.class);
    }
  }
}
