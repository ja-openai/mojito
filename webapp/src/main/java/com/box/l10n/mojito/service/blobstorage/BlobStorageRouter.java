package com.box.l10n.mojito.service.blobstorage;

import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class BlobStorageRouter {

  BlobStorageConfigurationProperties blobStorageConfigurationProperties;
  ObjectProvider<DatabaseBlobStorage> databaseBlobStorage;
  ObjectProvider<S3BlobStorage> s3BlobStorage;
  ObjectProvider<AzureBlobStorage> azureBlobStorage;

  public BlobStorageRouter(
      BlobStorageConfigurationProperties blobStorageConfigurationProperties,
      ObjectProvider<DatabaseBlobStorage> databaseBlobStorage,
      ObjectProvider<S3BlobStorage> s3BlobStorage,
      ObjectProvider<AzureBlobStorage> azureBlobStorage) {
    this.blobStorageConfigurationProperties = blobStorageConfigurationProperties;
    this.databaseBlobStorage = databaseBlobStorage;
    this.s3BlobStorage = s3BlobStorage;
    this.azureBlobStorage = azureBlobStorage;
  }

  public BlobStorage getBlobStorage(StructuredBlobStorage.Prefix prefix) {
    BlobStorageType storageType =
        blobStorageConfigurationProperties
            .getStorageTypeForPrefix(prefix)
            .orElse(blobStorageConfigurationProperties.getType());
    return getBlobStorage(storageType);
  }

  BlobStorage getBlobStorage(BlobStorageType storageType) {
    return switch (storageType) {
      case DATABASE -> getRequiredBlobStorage(storageType, databaseBlobStorage);
      case S3 -> getRequiredBlobStorage(storageType, s3BlobStorage);
      case AZURE -> getRequiredBlobStorage(storageType, azureBlobStorage);
    };
  }

  <T extends BlobStorage> T getRequiredBlobStorage(
      BlobStorageType storageType, ObjectProvider<T> blobStorage) {
    T availableBlobStorage = blobStorage.getIfAvailable();
    if (availableBlobStorage == null) {
      throw new IllegalStateException(
          "Blob storage type is not configured: " + storageType.name().toLowerCase());
    }
    return availableBlobStorage;
  }
}
