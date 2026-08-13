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
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.google.common.base.Preconditions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementation that uses Azure Blob Storage to store blobs.
 *
 * <p>Rely on Azure Blob Storage lifecycle management rules to cleanup expired blobs. This must be
 * setup manually else no cleanup will happen.
 *
 * <p>Objects will have a "retention" blob index tag, see values in {@link Retention}.
 */
public class AzureBlobStorage implements BlobStorage {

  static final String OPERATION_DURATION_METRIC = "AzureBlobStorage.operation.duration";

  BlobContainerClient blobContainerClient;

  AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties;

  MeterRegistry meterRegistry;

  public AzureBlobStorage(
      BlobContainerClient blobContainerClient,
      AzureBlobStorageConfigurationProperties azureBlobStorageConfigurationProperties,
      MeterRegistry meterRegistry) {
    Preconditions.checkNotNull(blobContainerClient);
    Preconditions.checkNotNull(azureBlobStorageConfigurationProperties);
    Preconditions.checkNotNull(meterRegistry);

    this.blobContainerClient = blobContainerClient;
    this.azureBlobStorageConfigurationProperties = azureBlobStorageConfigurationProperties;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Optional<byte[]> getBytes(String name) {
    return recordOperation(
        name,
        "read",
        () -> {
          try {
            return Optional.of(getBlobClient(name).downloadContent().toBytes());
          } catch (BlobStorageException exception) {
            if (!BlobErrorCode.BLOB_NOT_FOUND.equals(exception.getErrorCode())) {
              throw exception;
            }
            return Optional.empty();
          }
        },
        bytes -> bytes.isPresent() ? "success" : "miss");
  }

  @Override
  public void put(String name, byte[] content, Retention retention) {
    put(name, content, retention, null);
  }

  @Override
  public void delete(String name) {
    recordOperation(
        name,
        "delete",
        () -> getBlobClient(name).deleteIfExists(),
        deleted -> deleted ? "success" : "miss");
  }

  @Override
  public boolean exists(String name) {
    return recordOperation(
        name, "exists", () -> getBlobClient(name).exists(), exists -> exists ? "success" : "miss");
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

    recordOperation(
        name,
        "write",
        () -> getBlobClient(name).uploadWithResponse(blobParallelUploadOptions, null, Context.NONE),
        ignored -> "success");
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

  private <T> T recordOperation(
      String name,
      String operation,
      Supplier<T> executeOperation,
      Function<T, String> classifyResult) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String result = "failure";
    try {
      T value = executeOperation.get();
      result = classifyResult.apply(value);
      return value;
    } finally {
      sample.stop(
          meterRegistry.timer(
              OPERATION_DURATION_METRIC,
              "prefix",
              getKnownPrefix(name),
              "operation",
              operation,
              "result",
              result));
    }
  }

  private String getKnownPrefix(String name) {
    int separator = name.indexOf('/');
    if (separator < 0) {
      return "other";
    }

    try {
      return StructuredBlobStorage.Prefix.valueOf(
              name.substring(0, separator).toUpperCase(Locale.ROOT))
          .name()
          .toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      return "other";
    }
  }
}
