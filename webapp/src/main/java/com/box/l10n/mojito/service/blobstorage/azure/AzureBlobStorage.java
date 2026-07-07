package com.box.l10n.mojito.service.blobstorage.azure;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.box.l10n.mojito.service.blobstorage.BlobStorage;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.google.common.base.Preconditions;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation that uses Azure Blob Storage to store blobs.
 *
 * <p>Rely on Azure Blob Storage lifecycle management rules to cleanup expired blobs. This must be
 * setup manually else no cleanup will happen.
 *
 * <p>Objects will have a "retention" blob index tag, see values in {@link Retention}.
 */
public class AzureBlobStorage implements BlobStorage {

  BlobContainerClient blobContainerClient;

  AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties;

  public AzureBlobStorage(
      BlobContainerClient blobContainerClient,
      AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties) {
    Preconditions.checkNotNull(blobContainerClient);
    Preconditions.checkNotNull(azureBlobStorageConfigurationProperties);

    this.blobContainerClient = blobContainerClient;
    this.azureBlobStorageConfigurationProperties = azureBlobStorageConfigurationProperties;
  }

  @Override
  public Optional<byte[]> getBytes(String name) {
    byte[] bytes = null;

    try {
      bytes = getBlobClient(name).downloadContent().toBytes();
    } catch (BlobStorageException e) {
      if (!BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())) {
        throw e;
      }
    }

    return Optional.ofNullable(bytes);
  }

  @Override
  public void put(String name, byte[] content, Retention retention) {
    put(name, content, retention, null);
  }

  @Override
  public void delete(String name) {
    getBlobClient(name).deleteIfExists();
  }

  @Override
  public boolean exists(String name) {
    return getBlobClient(name).exists();
  }

  @Override
  public void put(String name, String content, Retention retention) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    BlobHttpHeaders blobHttpHeaders =
        new BlobHttpHeaders()
            .setContentType("text/plain")
            .setContentEncoding(StandardCharsets.UTF_8.toString());

    put(name, bytes, retention, blobHttpHeaders);
  }

  void put(String name, byte[] content, Retention retention, BlobHttpHeaders blobHttpHeaders) {
    BlobParallelUploadOptions blobParallelUploadOptions =
        new BlobParallelUploadOptions(BinaryData.fromBytes(content))
            .setTags(Map.of("retention", retention.toString()));

    if (blobHttpHeaders != null) {
      blobParallelUploadOptions.setHeaders(blobHttpHeaders);
    }

    getBlobClient(name).uploadWithResponse(blobParallelUploadOptions, null, Context.NONE);
  }

  public String getAzureUrl(String name) {
    return getBlobClient(name).getBlobUrl();
  }

  @Override
  public String getTargetDescription(String name) {
    return getAzureUrl(name);
  }

  BlobClient getBlobClient(String name) {
    return blobContainerClient.getBlobClient(getFullName(name));
  }

  String getFullName(String name) {
    return azureBlobStorageConfigurationProperties.getPrefix() + "/" + name;
  }
}
