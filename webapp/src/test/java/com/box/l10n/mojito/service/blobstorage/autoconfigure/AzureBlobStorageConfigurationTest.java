package com.box.l10n.mojito.service.blobstorage.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfiguration;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AzureBlobStorageConfigurationTest {

  ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              BlobStorageConfiguration.class,
              AzureBlobStorageConfigurationProperties.class,
              TestConfig.class);

  @Test
  public void testAzureImplementationIsLoaded() {
    applicationContextRunner
        .withPropertyValues("l10n.blob-storage.type=azure")
        .run(context -> assertThat(context).hasSingleBean(AzureBlobStorage.class));
  }

  @Configuration
  static class TestConfig {

    @Bean
    public BlobContainerClient blobContainerClient() {
      return mock(BlobContainerClient.class);
    }
  }
}
