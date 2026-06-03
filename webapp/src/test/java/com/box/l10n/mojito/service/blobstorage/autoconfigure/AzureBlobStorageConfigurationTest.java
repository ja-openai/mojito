package com.box.l10n.mojito.service.blobstorage.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.retry.DataIntegrityViolationExceptionRetryTemplate;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfiguration;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import org.junit.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public class AzureBlobStorageConfigurationTest {

  ApplicationContextRunner applicationContextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              BlobStorageConfiguration.class,
              BlobStorageConfigurationProperties.class,
              BlobStorageRouter.class,
              AzureBlobStorageConfigurationProperties.class,
              DatabaseBlobStorageConfigurationProperties.class,
              TestConfig.class);

  @Test
  public void testAzureImplementationIsLoaded() {
    applicationContextRunner
        .withPropertyValues("l10n.azure.blob-storage.enabled=true", "l10n.blob-storage.type=azure")
        .run(
            context ->
                assertThat(context.getBean("azureBlobStorage"))
                    .isInstanceOf(AzureBlobStorage.class));
  }

  @Test
  public void testAzureClientConfigurationDoesNotCreateAzureBlobStorageWhenUnused() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true", "l10n.blob-storage.type=database")
        .run(context -> assertThat(context).doesNotHaveBean(AzureBlobStorage.class));
  }

  @Test
  public void testDatabaseImplementationIsLoadedForPrefixRoute() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true",
            "l10n.blob-storage.type=azure",
            "l10n.blob-storage.routing.prefixes.pollable-task=database")
        .run(
            context ->
                assertThat(context.getBean("databaseBlobStorage"))
                    .isInstanceOf(DatabaseBlobStorage.class));
  }

  @Configuration
  @EnableConfigurationProperties
  static class TestConfig {

    @Bean
    public BlobContainerClient blobContainerClient() {
      return mock(BlobContainerClient.class);
    }

    @Bean
    public MBlobRepository mBlobRepository() {
      return mock(MBlobRepository.class);
    }

    @Bean
    public DataIntegrityViolationExceptionRetryTemplate
        dataIntegrityViolationExceptionRetryTemplate() {
      return new DataIntegrityViolationExceptionRetryTemplate();
    }
  }
}
