package com.box.l10n.mojito.service.blobstorage;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.IMAGE;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

public class BlobStorageRouterTest {

  DatabaseBlobStorage databaseBlobStorage = mock(DatabaseBlobStorage.class);
  AzureBlobStorage azureBlobStorage = mock(AzureBlobStorage.class);

  @Test
  public void routesPrefixToConfiguredBackend() {
    BlobStorageConfigurationProperties properties = new BlobStorageConfigurationProperties();
    properties.setType(BlobStorageType.DATABASE);
    properties.getRouting().getPrefixes().put("image", BlobStorageType.AZURE);

    BlobStorageRouter router =
        new BlobStorageRouter(
            properties,
            databaseBlobStorageProvider(databaseBlobStorage),
            emptyS3BlobStorageProvider(),
            azureBlobStorageProvider(azureBlobStorage));

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
            azureBlobStorageProvider(azureBlobStorage));

    assertThat(router.getBlobStorage(POLLABLE_TASK)).isSameAs(azureBlobStorage);
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
            emptyAzureBlobStorageProvider());

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

  private ObjectProvider<AzureBlobStorage> emptyAzureBlobStorageProvider() {
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    return beanFactory.getBeanProvider(AzureBlobStorage.class);
  }
}
