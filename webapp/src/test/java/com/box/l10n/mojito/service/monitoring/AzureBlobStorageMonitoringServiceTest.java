package com.box.l10n.mojito.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.box.l10n.mojito.azure.blobstorage.AzureBlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.monitoring.AzureBlobStorageMonitoringService.AzureStorageSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

public class AzureBlobStorageMonitoringServiceTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<BlobContainerClient> blobContainerClientProvider =
      mock(ObjectProvider.class);

  private final BlobContainerClient blobContainerClient = mock(BlobContainerClient.class);

  private final AzureBlobStorageConfigurationProperties azureConfigurationProperties =
      new AzureBlobStorageConfigurationProperties();

  private final com.box.l10n.mojito.service.blobstorage.azure
          .AzureBlobStorageConfigurationProperties
      azureStorageProperties =
          new com.box.l10n.mojito.service.blobstorage.azure
              .AzureBlobStorageConfigurationProperties();

  private final BlobStorageConfigurationProperties blobStorageProperties =
      new BlobStorageConfigurationProperties();

  private final MockEnvironment environment = new MockEnvironment();

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final AzureBlobStorageMonitoringService service =
      new AzureBlobStorageMonitoringService(
          blobContainerClientProvider,
          azureConfigurationProperties,
          azureStorageProperties,
          blobStorageProperties,
          environment,
          meterRegistry);

  @Test
  public void reportsDisabledAzureWithoutRequiringAClient() {
    AzureStorageSnapshot snapshot = service.getStatus();

    assertThat(snapshot.enabled()).isFalse();
    assertThat(snapshot.status()).isEqualTo("NOT_CONFIGURED");
    assertThat(snapshot.defaultBackend()).isEqualTo("database");
    assertThat(snapshot.checks()).isEmpty();
  }

  @Test
  public void reportsContainerAccessAndExplicitRoutingWithoutChangingTheDefault() {
    enableAzure();
    when(blobContainerClient.exists()).thenReturn(true);
    when(blobContainerClient.getBlobContainerUrl())
        .thenReturn("https://example.blob.core.windows.net/mojito");
    blobStorageProperties
        .getRouting()
        .getPrefixes()
        .put("ai-translate-lineage", BlobStorageType.AZURE);

    AzureStorageSnapshot snapshot = service.getStatus();

    assertThat(snapshot.status()).isEqualTo("READY");
    assertThat(snapshot.defaultBackend()).isEqualTo("database");
    assertThat(snapshot.routes())
        .anySatisfy(
            route -> {
              assertThat(route.prefix()).isEqualTo("ai_translate_lineage");
              assertThat(route.backend()).isEqualTo("azure");
            });
    assertThat(snapshot.checks())
        .singleElement()
        .satisfies(check -> assertThat(check.success()).isTrue());
  }

  @Test
  public void reportsAzureDatabaseFallbackRoute() {
    enableAzure();
    when(blobContainerClient.exists()).thenReturn(true);
    blobStorageProperties
        .getRouting()
        .getPrefixes()
        .put("pollable-task", BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);

    AzureStorageSnapshot snapshot = service.getStatus();

    assertThat(snapshot.routes())
        .anySatisfy(
            route -> {
              assertThat(route.prefix()).isEqualTo("pollable_task");
              assertThat(route.backend()).isEqualTo("azure_with_database_fallback");
            });
  }

  @Test
  public void reportsContainerFailureWithoutThrowing() {
    enableAzure();
    when(blobContainerClient.exists()).thenThrow(new IllegalStateException("unavailable"));

    AzureStorageSnapshot snapshot = service.getStatus();

    assertThat(snapshot.status()).isEqualTo("UNAVAILABLE");
    assertThat(snapshot.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.success()).isFalse();
              assertThat(check.message()).isEqualTo("IllegalStateException");
            });
  }

  @Test
  public void probesWriteReadAndDelete() {
    enableAzure();
    when(blobContainerClient.exists()).thenReturn(true);
    BlobClient blobClient = mock(BlobClient.class);
    when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
    when(blobClient.downloadContent())
        .thenReturn(BinaryData.fromString("mojito-azure-storage-monitoring"));
    when(blobClient.deleteIfExists()).thenReturn(true);

    AzureStorageSnapshot snapshot = service.runProbe();

    assertThat(snapshot.status()).isEqualTo("READY");
    assertThat(snapshot.checks())
        .extracting("name")
        .containsExactly("Container access", "Write probe", "Read probe", "Delete probe");
    ArgumentCaptor<BlobParallelUploadOptions> uploadOptions =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    verify(blobClient).uploadWithResponse(uploadOptions.capture(), eq(null), eq(Context.NONE));
    assertThat(uploadOptions.getValue().getTags()).containsEntry("retention", "MIN_1_DAY");
    assertThat(uploadOptions.getValue().getHeaders().getContentType()).isEqualTo("text/plain");
    verify(blobClient).deleteIfExists();
    assertThat(
            meterRegistry
                .get("AzureBlobStorage.operation.duration")
                .tag("prefix", "other")
                .tag("operation", "write")
                .tag("result", "success")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void reportsMissingBlobTagPermissions() {
    enableAzure();
    when(blobContainerClient.exists()).thenReturn(true);
    BlobClient blobClient = mock(BlobClient.class);
    when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
    BlobStorageException blobStorageException = mock(BlobStorageException.class);
    when(blobStorageException.getStatusCode()).thenReturn(403);
    when(blobStorageException.getErrorCode())
        .thenReturn(BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH);
    when(blobClient.uploadWithResponse(
            any(BlobParallelUploadOptions.class), eq(null), eq(Context.NONE)))
        .thenThrow(blobStorageException);

    AzureStorageSnapshot snapshot = service.runProbe();

    assertThat(snapshot.status()).isEqualTo("UNAVAILABLE");
    assertThat(snapshot.checks())
        .extracting("name")
        .containsExactly("Container access", "Write probe");
    assertThat(snapshot.checks().get(1).message())
        .isEqualTo("BlobStorageException (HTTP 403, AuthorizationPermissionMismatch)");
    verify(blobClient, never()).downloadContent();
    verify(blobClient, never()).deleteIfExists();
  }

  private void enableAzure() {
    environment.setProperty("l10n.azure.blob-storage.enabled", "true");
    when(blobContainerClientProvider.getIfAvailable()).thenReturn(blobContainerClient);
  }
}
