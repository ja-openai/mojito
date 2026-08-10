package com.box.l10n.mojito.service.monitoring;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.box.l10n.mojito.azure.blobstorage.AzureBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class AzureBlobStorageMonitoringService {

  private static final String PROBE_CONTENT = "mojito-azure-storage-monitoring";

  private final ObjectProvider<BlobContainerClient> blobContainerClientProvider;
  private final AzureBlobStorageConfigurationProperties azureConfigurationProperties;
  private final com.box.l10n.mojito.service.blobstorage.azure
          .AzureBlobStorageConfigurationProperties
      azureStorageProperties;
  private final BlobStorageConfigurationProperties blobStorageProperties;
  private final Environment environment;
  private final MeterRegistry meterRegistry;

  public AzureBlobStorageMonitoringService(
      ObjectProvider<BlobContainerClient> blobContainerClientProvider,
      AzureBlobStorageConfigurationProperties azureConfigurationProperties,
      com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorageConfigurationProperties
          azureStorageProperties,
      BlobStorageConfigurationProperties blobStorageProperties,
      Environment environment,
      MeterRegistry meterRegistry) {
    this.blobContainerClientProvider = blobContainerClientProvider;
    this.azureConfigurationProperties = azureConfigurationProperties;
    this.azureStorageProperties = azureStorageProperties;
    this.blobStorageProperties = blobStorageProperties;
    this.environment = environment;
    this.meterRegistry = meterRegistry;
  }

  public AzureStorageSnapshot getStatus() {
    BlobContainerClient client = blobContainerClientProvider.getIfAvailable();
    boolean enabled =
        environment.getProperty("l10n.azure.blob-storage.enabled", Boolean.class, false);
    List<AzureStorageCheck> checks = new ArrayList<>();
    String status = "NOT_CONFIGURED";

    if (enabled && client != null) {
      AzureStorageCheck containerCheck = runCheck("Container access", client::exists);
      checks.add(containerCheck);
      status = containerCheck.success() ? "READY" : "UNAVAILABLE";
    } else if (enabled) {
      status = "UNAVAILABLE";
      checks.add(new AzureStorageCheck("Azure client", false, 0, "Azure client is unavailable"));
    }

    return snapshot(enabled, client, status, checks);
  }

  public AzureStorageSnapshot runProbe() {
    BlobContainerClient client = blobContainerClientProvider.getIfAvailable();
    boolean enabled =
        environment.getProperty("l10n.azure.blob-storage.enabled", Boolean.class, false);
    if (!enabled || client == null) {
      return getStatus();
    }

    List<AzureStorageCheck> checks = new ArrayList<>();
    AzureStorageCheck containerCheck = runCheck("Container access", client::exists);
    checks.add(containerCheck);
    if (!containerCheck.success()) {
      return snapshot(true, client, "UNAVAILABLE", checks);
    }

    String probeName = "_monitoring/" + UUID.randomUUID();
    BlobClient blobClient =
        client.getBlobClient(azureStorageProperties.getPrefix() + "/" + probeName);
    AzureBlobStorage azureBlobStorage =
        new AzureBlobStorage(client, azureStorageProperties, meterRegistry);
    boolean uploaded = false;
    try {
      AzureStorageCheck writeCheck =
          runCheck(
              "Write probe",
              () -> {
                azureBlobStorage.put(probeName, PROBE_CONTENT, Retention.MIN_1_DAY);
                return true;
              });
      checks.add(writeCheck);
      uploaded = writeCheck.success();

      if (uploaded) {
        checks.add(
            runCheck(
                "Read probe", () -> PROBE_CONTENT.equals(blobClient.downloadContent().toString())));
      }
    } finally {
      if (uploaded) {
        checks.add(runCheck("Delete probe", blobClient::deleteIfExists));
      }
    }

    String status = checks.stream().allMatch(AzureStorageCheck::success) ? "READY" : "UNAVAILABLE";
    return snapshot(true, client, status, checks);
  }

  private AzureStorageSnapshot snapshot(
      boolean enabled, BlobContainerClient client, String status, List<AzureStorageCheck> checks) {
    List<AzureStorageRoute> routes =
        java.util.Arrays.stream(StructuredBlobStorage.Prefix.values())
            .map(
                prefix ->
                    new AzureStorageRoute(
                        prefix.name().toLowerCase(Locale.ROOT),
                        blobStorageProperties
                            .getStorageTypeForPrefix(prefix)
                            .orElse(blobStorageProperties.getDefaultType())
                            .name()
                            .toLowerCase(Locale.ROOT)))
            .toList();

    return new AzureStorageSnapshot(
        Instant.now(),
        enabled,
        status,
        client == null ? azureConfigurationProperties.getEndpoint() : client.getBlobContainerUrl(),
        azureConfigurationProperties.getContainer(),
        azureStorageProperties.getPrefix(),
        blobStorageProperties.getDefaultType().name().toLowerCase(Locale.ROOT),
        routes,
        checks);
  }

  private AzureStorageCheck runCheck(String name, CheckOperation operation) {
    long startNanos = System.nanoTime();
    try {
      boolean success = operation.run();
      return new AzureStorageCheck(
          name,
          success,
          elapsedMillis(startNanos),
          success ? null : "The requested resource was not found or returned unexpected content");
    } catch (RuntimeException exception) {
      String message = exception.getClass().getSimpleName();
      if (exception instanceof BlobStorageException blobStorageException) {
        message +=
            " (HTTP "
                + blobStorageException.getStatusCode()
                + ", "
                + blobStorageException.getErrorCode()
                + ")";
      }
      return new AzureStorageCheck(name, false, elapsedMillis(startNanos), message);
    }
  }

  private long elapsedMillis(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  @FunctionalInterface
  interface CheckOperation {
    boolean run();
  }

  public record AzureStorageSnapshot(
      Instant timestamp,
      boolean enabled,
      String status,
      String endpoint,
      String container,
      String prefix,
      String defaultBackend,
      List<AzureStorageRoute> routes,
      List<AzureStorageCheck> checks) {}

  public record AzureStorageRoute(String prefix, String backend) {}

  public record AzureStorageCheck(String name, boolean success, long latencyMs, String message) {}
}
