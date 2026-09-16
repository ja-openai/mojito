package com.box.l10n.mojito.service.blobstorage.azure;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobRequestConditions;
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

  /**
   * Create-only immutable backup writes. Deliberately restricted to the non-serving migration
   * namespace so a migration worker cannot resurrect or overwrite a canonical application blob.
   * True means created; false means another copy already exists and must still be verified.
   */
  public boolean createSnapshotIfAbsent(String name, byte[] content) {
    requireSnapshotName(name);
    return createImmutableIfAbsent(name, content, "snapshot-create");
  }

  /** Pollable-task archives are immutable, permanently retained records. */
  public boolean createPollableTaskArchiveIfAbsent(String name, byte[] content) {
    if (name == null || !name.matches("pollable_task_archive/v1/[0-9]+\\.json"))
      throw new IllegalArgumentException("Archive creation requires a pollable-task archive name");
    return createImmutableIfAbsent(name, content, "archive-create");
  }

  private boolean createImmutableIfAbsent(String name, byte[] content, String operation) {
    BlobParallelUploadOptions options =
        new BlobParallelUploadOptions(BinaryData.fromBytes(content))
            .setTags(Map.of("retention", Retention.PERMANENT.toString()))
            .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
    return recordOperation(
        name,
        operation,
        () -> {
          try {
            getBlobClient(name)
                .uploadWithResponse(options, java.time.Duration.ofSeconds(30), Context.NONE);
            return true;
          } catch (BlobStorageException exception) {
            if (BlobErrorCode.BLOB_ALREADY_EXISTS.equals(exception.getErrorCode())
                || BlobErrorCode.CONDITION_NOT_MET.equals(exception.getErrorCode())) return false;
            throw exception;
          }
        },
        created -> created ? "success" : "exists");
  }

  /** Read-back is bounded even when an existing destination contains unexpected oversized data. */
  public byte[] getSnapshotBytes(String name, int expectedLength) {
    requireSnapshotName(name);
    return recordOperation(
        name,
        "snapshot-read",
        () -> readMigrationBytes(name, expectedLength, null),
        ignored -> "success");
  }

  /**
   * Canonical promotion is a separately invoked maintenance operation. Its caller must hold the
   * externally enforced writer fence; this method's conditional write prevents overwrites but does
   * not establish that fence. The normal snapshot job never calls this method.
   */
  public boolean createCanonicalBlobForMigrationIfAbsent(
      String name, byte[] content, Retention retention) {
    requireCanonicalMigrationName(name);
    BlobParallelUploadOptions options =
        new BlobParallelUploadOptions(BinaryData.fromBytes(content))
            .setTags(Map.of("retention", retention.toString()))
            .setRequestConditions(new BlobRequestConditions().setIfNoneMatch("*"));
    return recordOperation(
        name,
        "migration-create",
        () -> {
          try {
            getBlobClient(name)
                .uploadWithResponse(options, java.time.Duration.ofSeconds(30), Context.NONE);
            return true;
          } catch (BlobStorageException exception) {
            if (BlobErrorCode.BLOB_ALREADY_EXISTS.equals(exception.getErrorCode())
                || BlobErrorCode.CONDITION_NOT_MET.equals(exception.getErrorCode())) return false;
            throw exception;
          }
        },
        created -> created ? "success" : "exists");
  }

  public record MigrationBlob(long length, byte[] content, String eTag, String retention) {}

  /** At most three 30-second calls. Different-length objects are reported without reading bytes. */
  public Optional<MigrationBlob> inspectCanonicalBlobForMigration(String name, int expectedLength) {
    requireCanonicalMigrationName(name);
    requireMigrationLength(expectedLength);
    return recordOperation(
        name,
        "migration-read",
        () -> {
          com.azure.storage.blob.models.BlobProperties properties;
          try {
            properties =
                getBlobClient(name)
                    .getPropertiesWithResponse(null, java.time.Duration.ofSeconds(30), Context.NONE)
                    .getValue();
          } catch (BlobStorageException exception) {
            if (BlobErrorCode.BLOB_NOT_FOUND.equals(exception.getErrorCode()))
              return Optional.empty();
            throw exception;
          }
          Map<String, String> tags =
              getBlobClient(name)
                  .getTagsWithResponse(null, java.time.Duration.ofSeconds(30), Context.NONE)
                  .getValue();
          String etag = properties.getETag();
          if (etag == null || etag.isBlank()) throw new SnapshotVerificationException();
          byte[] bytes =
              properties.getBlobSize() == expectedLength
                  ? readMigrationBytes(
                      name, expectedLength, new BlobRequestConditions().setIfMatch(etag))
                  : null;
          return Optional.of(
              new MigrationBlob(
                  properties.getBlobSize(),
                  bytes,
                  etag,
                  tags == null ? null : tags.get("retention")));
        },
        value -> value.isPresent() ? "success" : "miss");
  }

  private byte[] readMigrationBytes(
      String name, int expectedLength, BlobRequestConditions conditions) {
    requireMigrationLength(expectedLength);
    var output =
        new java.io.ByteArrayOutputStream() {
          @Override
          public synchronized void write(byte[] bytes, int offset, int length) {
            if (length > expectedLength - size()) throw new SnapshotVerificationException();
            super.write(bytes, offset, length);
          }

          @Override
          public synchronized void write(int value) {
            if (size() >= expectedLength) throw new SnapshotVerificationException();
            super.write(value);
          }
        };
    try {
      getBlobClient(name)
          .downloadStreamWithResponse(
              output,
              null,
              null,
              conditions,
              false,
              java.time.Duration.ofSeconds(30),
              Context.NONE);
    } catch (BlobStorageException exception) {
      if (BlobErrorCode.BLOB_NOT_FOUND.equals(exception.getErrorCode()))
        throw new SnapshotVerificationException();
      throw exception;
    }
    byte[] bytes = output.toByteArray();
    if (bytes.length != expectedLength) throw new SnapshotVerificationException();
    return bytes;
  }

  private void requireMigrationLength(int length) {
    if (length < 0 || length > 256 * 1024 * 1024)
      throw new IllegalArgumentException("Invalid migration payload size");
  }

  private void requireCanonicalMigrationName(String name) {
    if (name == null || name.length() > 255 || name.indexOf('/') <= 0)
      throw new IllegalArgumentException("Canonical migration requires a semantic blob name");
    String prefix = name.substring(0, name.indexOf('/'));
    if (!prefix.equals(prefix.toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException(
          "Canonical migration requires a lowercase semantic prefix");
    try {
      StructuredBlobStorage.Prefix.valueOf(prefix.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unknown canonical migration prefix");
    }
  }

  public static class SnapshotVerificationException extends IllegalStateException {
    public SnapshotVerificationException() {
      super("Migration snapshot verification failed");
    }
  }

  private void requireSnapshotName(String name) {
    if (name == null || !name.matches("mblob_migration/v1/[a-f0-9-]{36}/[0-9]+/[a-f0-9]{64}"))
      throw new IllegalArgumentException("Snapshot operations require a migration snapshot name");
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
