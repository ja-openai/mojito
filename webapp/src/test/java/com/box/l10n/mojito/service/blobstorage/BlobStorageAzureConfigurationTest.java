package com.box.l10n.mojito.service.blobstorage;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class BlobStorageAzureConfigurationTest {

  @Test
  public void azureBlobStorageConfigurationCreatesAzureBlobStorage() {
    BlobStorageConfiguration.AzureBlobStorageConfiguration configuration =
        new BlobStorageConfiguration.AzureBlobStorageConfiguration();

    AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties =
        new AzureBlobStorageConfigurationProperties();
    azureBlobStorageConfigurationProperties.setPrefix("mojito");
    azureBlobStorageConfigurationProperties.setContainer("mojito");

    ReflectionTestUtils.setField(
        configuration,
        "azureBlobStorageConfigurationProperties",
        azureBlobStorageConfigurationProperties);

    BlobContainerClient blobContainerClient = mock(BlobContainerClient.class);
    BlobStorage blobStorage = configuration.azureBlobStorage(blobContainerClient);

    assertEquals(AzureBlobStorage.class, blobStorage.getClass());
  }
}
