package com.box.l10n.mojito.service.blobstorage.azure;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
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
 * <p>Rely on Azure lifecycle management rules to cleanup expired blobs. This must be setup manually
 * else no cleanup will happen.
 *
 * <p>Blobs will have a "retention" tag, see values in {@link Retention}
 */
public class AzureBlobStorage implements BlobStorage {

  private final BlobContainerClient blobContainerClient;

  private final String prefix;

  public AzureBlobStorage(
      BlobContainerClient blobContainerClient,
      AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties) {
    Preconditions.checkNotNull(blobContainerClient);
    Preconditions.checkNotNull(azureBlobStorageConfigurationProperties);
    Preconditions.checkNotNull(azureBlobStorageConfigurationProperties.getPrefix());

    this.blobContainerClient = blobContainerClient;
    this.prefix = azureBlobStorageConfigurationProperties.getPrefix();
  }

  @Override
  public Optional<byte[]> getBytes(String name) {
    try {
      return Optional.of(getBlobClient(name).downloadContent().toBytes());
    } catch (BlobStorageException e) {
      if (!isNotFound(e)) {
        throw e;
      }
      return Optional.empty();
    }
  }

  @Override
  public void put(String name, byte[] content, Retention retention) {
    upload(name, content, retention, null);
  }

  @Override
  public void put(String name, String content, Retention retention) {
    upload(
        name,
        content.getBytes(StandardCharsets.UTF_8),
        retention,
        new BlobHttpHeaders()
            .setContentType("text/plain")
            .setContentEncoding(StandardCharsets.UTF_8.toString()));
  }

  @Override
  public void delete(String name) {
    getBlobClient(name).deleteIfExists();
  }

  @Override
  public boolean exists(String name) {
    return getBlobClient(name).exists();
  }

  BlobClient getBlobClient(String name) {
    return blobContainerClient.getBlobClient(getFullName(name));
  }

  Map<String, String> getTags(Retention retention) {
    return Map.of("retention", retention.toString());
  }

  void upload(String name, byte[] content, Retention retention, BlobHttpHeaders blobHttpHeaders) {
    BlobParallelUploadOptions blobParallelUploadOptions =
        new BlobParallelUploadOptions(BinaryData.fromBytes(content))
            .setHeaders(blobHttpHeaders)
            .setTags(getTags(retention));
    getBlobClient(name).uploadWithResponse(blobParallelUploadOptions, null, Context.NONE);
  }

  boolean isNotFound(BlobStorageException blobStorageException) {
    return blobStorageException.getStatusCode() == 404;
  }

  String getFullName(String name) {
    return prefix + "/" + name;
  }
}
