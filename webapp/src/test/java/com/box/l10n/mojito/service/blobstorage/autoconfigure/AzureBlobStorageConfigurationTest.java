package com.box.l10n.mojito.service.blobstorage.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.retry.DataIntegrityViolationExceptionRetryTemplate;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfiguration;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.database.MBlobRepository;
import com.box.l10n.mojito.service.image.BlobStorageFallbackImageService;
import com.box.l10n.mojito.service.image.ImageMigrationService;
import com.box.l10n.mojito.service.image.ImageRepository;
import com.box.l10n.mojito.service.image.ImageService;
import com.box.l10n.mojito.service.image.ImageServiceConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true", "l10n.blob-storage.default-type=azure")
        .run(
            context ->
                assertThat(context.getBean("azureBlobStorage"))
                    .isInstanceOf(AzureBlobStorage.class));
  }

  @Test
  public void testAzureClientConfigurationDoesNotCreateAzureBlobStorageWhenUnused() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true", "l10n.blob-storage.default-type=database")
        .run(context -> assertThat(context).doesNotHaveBean(AzureBlobStorage.class));
  }

  @Test
  public void testDatabaseImplementationIsLoadedForPrefixRoute() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true",
            "l10n.blob-storage.default-type=azure",
            "l10n.blob-storage.routing.prefixes.pollable-task=database")
        .run(
            context ->
                assertThat(context.getBean("databaseBlobStorage"))
                    .isInstanceOf(DatabaseBlobStorage.class));
  }

  @Test
  public void testLegacyTypeStillConfiguresDefaultBackend() {
    applicationContextRunner
        .withPropertyValues("l10n.azure.blob-storage.enabled=true", "l10n.blob-storage.type=azure")
        .run(
            context -> {
              assertThat(context.getBean("azureBlobStorage")).isInstanceOf(AzureBlobStorage.class);
              assertThat(context.getBean(BlobStorageConfigurationProperties.class).getDefaultType())
                  .isEqualTo(BlobStorageType.AZURE);
            });
  }

  @Test
  public void testDefaultTypeTakesPrecedenceOverLegacyType() {
    applicationContextRunner
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true",
            "l10n.blob-storage.type=database",
            "l10n.blob-storage.default-type=azure")
        .run(
            context -> {
              assertThat(context.getBean("azureBlobStorage")).isInstanceOf(AzureBlobStorage.class);
              assertThat(context.getBean(BlobStorageConfigurationProperties.class).getDefaultType())
                  .isEqualTo(BlobStorageType.AZURE);
            });
  }

  @Test
  public void testImagePrefixRouteEnablesImageMigrationWithoutLegacyImageStorageSetting() {
    applicationContextRunner
        .withUserConfiguration(ImageServiceConfiguration.class, ImageMigrationService.class)
        .withBean(ImageRepository.class, () -> mock(ImageRepository.class))
        .withPropertyValues(
            "l10n.azure.blob-storage.enabled=true",
            "l10n.blob-storage.default-type=database",
            "l10n.blob-storage.routing.prefixes.image=azure",
            "l10n.image-service.migration.enabled=true")
        .run(
            context -> {
              assertThat(context.getBean(ImageService.class))
                  .isInstanceOf(BlobStorageFallbackImageService.class);
              assertThat(context).hasSingleBean(ImageMigrationService.class);
            });
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

    @Bean
    public SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
