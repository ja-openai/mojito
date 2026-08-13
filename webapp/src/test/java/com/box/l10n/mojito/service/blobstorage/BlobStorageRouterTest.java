package com.box.l10n.mojito.service.blobstorage;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.IMAGE;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage.StoredBlob;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

public class BlobStorageRouterTest {

  DatabaseBlobStorage databaseBlobStorage = mock(DatabaseBlobStorage.class);
  AzureBlobStorage azureBlobStorage = mock(AzureBlobStorage.class);
  SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  public void routesPrefixToConfiguredBackend() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties.setDefaultType(BlobStorageType.DATABASE);
    properties.getRouting().getPrefixes().put("image", BlobStorageType.AZURE);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThat(router.getBlobStorage(POLLABLE_TASK)).isSameAs(databaseBlobStorage);
    assertThat(router.getBlobStorage(IMAGE)).isSameAs(azureBlobStorage);
  }

  @Test
  public void supportsKebabCasePrefixKeys() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties.getRouting().getPrefixes().put("pollable-task", BlobStorageType.AZURE);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThat(router.getBlobStorage(POLLABLE_TASK)).isSameAs(azureBlobStorage);
  }

  @Test
  public void routesCorrectedNoBatchReportPrefixToConfiguredBackend() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties
        .getRouting()
        .getPrefixes()
        .put("ai-translate-no-batch-output", BlobStorageType.AZURE);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThat(router.getBlobStorage(AI_TRANSLATE_NO_BATCH_OUTPUT)).isSameAs(azureBlobStorage);
  }

  @Test
  public void routesPrefixToAzureWithDatabaseFallback() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties
        .getRouting()
        .getPrefixes()
        .put("pollable-task", BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("pollable_task/42/input"))
        .thenReturn(
            Optional.of(
                new StoredBlob(
                    "database-input".getBytes(StandardCharsets.UTF_8), Retention.MIN_1_DAY)));

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThat(router.getBlobStorage(POLLABLE_TASK))
        .isInstanceOf(AzureDatabaseFallbackBlobStorage.class);
    assertThat(router.getBlobStorage(POLLABLE_TASK).getString("pollable_task/42/input"))
        .contains("database-input");
    verify(azureBlobStorage).put("pollable_task/42/input", "database-input", Retention.MIN_1_DAY);
    assertThat(router.getBlobStorage(IMAGE)).isSameAs(databaseBlobStorage);
  }

  @Test
  public void supportsAzureWithDatabaseFallbackAsDefaultBackend() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties.setDefaultType(BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThat(router.getBlobStorage(POLLABLE_TASK))
        .isInstanceOf(AzureDatabaseFallbackBlobStorage.class);
    assertThat(router.getBlobStorage(IMAGE)).isInstanceOf(AzureDatabaseFallbackBlobStorage.class);
  }

  @Test
  public void plainAzureRouteDoesNotFallBackToDatabase() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties.getRouting().getPrefixes().put("pollable-task", BlobStorageType.AZURE);
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThat(router.getBlobStorage(POLLABLE_TASK).getString("pollable_task/42/input")).isEmpty();
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void failsWhenDatabaseFallbackBackendIsUnavailable() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties
        .getRouting()
        .getPrefixes()
        .put("pollable-task", BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            emptyDatabaseBlobStorageProvider(),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage),
            meterRegistry);

    assertThatThrownBy(() -> router.getBlobStorage(POLLABLE_TASK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Blob storage type is not configured: database");
  }

  @Test
  public void failsWhenAzureFallbackBackendIsUnavailable() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties
        .getRouting()
        .getPrefixes()
        .put("pollable-task", BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            emptyAzureBlobStorageProvider(),
            meterRegistry);

    assertThatThrownBy(() -> router.getBlobStorage(POLLABLE_TASK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Blob storage type is not configured: azure");
  }

  @Test
  public void failsWhenConfiguredBackendIsUnavailable() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties.getRouting().getPrefixes().put("image", BlobStorageType.S3);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            emptyAzureBlobStorageProvider(),
            meterRegistry);

    assertThatThrownBy(() -> router.getBlobStorage(IMAGE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Blob storage type is not configured: s3");
  }

  private ObjectProvider<AzureBlobStorage> azureBlobStorageProvider(AzureBlobStorage bean) {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("azureBlobStorage", bean);
    return beanFactory.getBeanProvider(AzureBlobStorage.class);
  }

  private ObjectProvider<DatabaseBlobStorage> databaseBlobStorageProvider(
      DatabaseBlobStorage bean) {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("databaseBlobStorage", bean);
    return beanFactory.getBeanProvider(DatabaseBlobStorage.class);
  }

  private ObjectProvider<S3BlobStorage> emptyS3BlobStorageProvider() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    return beanFactory.getBeanProvider(S3BlobStorage.class);
  }

  private ObjectProvider<DatabaseBlobStorage> emptyDatabaseBlobStorageProvider() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    return beanFactory.getBeanProvider(DatabaseBlobStorage.class);
  }

  private ObjectProvider<AzureBlobStorage> emptyAzureBlobStorageProvider() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    return beanFactory.getBeanProvider(AzureBlobStorage.class);
  }
}
