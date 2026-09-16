package com.box.l10n.mojito.service.blobstorage.azure;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.box.l10n.mojito.service.blobstorage.Retention;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AzureBlobStorageTest {

  BlobContainerClient blobContainerClient;
  BlobClient blobClient;
  AzureBlobStorage azureBlobStorage;
  SimpleMeterRegistry meterRegistry;

  @Before
  public void before() {
    blobContainerClient = mock(BlobContainerClient.class);
    blobClient = mock(BlobClient.class);

    AzureBlobStorageConfigurationProperties properties =
        new AzureBlobStorageConfigurationProperties();
    properties.setPrefix("prefix");

    meterRegistry = new SimpleMeterRegistry();
    azureBlobStorage = new AzureBlobStorage(blobContainerClient, properties, meterRegistry);
    when(blobContainerClient.getBlobClient("prefix/name")).thenReturn(blobClient);
  }

  @Test
  public void testGetBytes() {
    byte[] content = "content".getBytes(StandardCharsets.UTF_8);
    when(blobClient.downloadContent()).thenReturn(BinaryData.fromBytes(content));

    Optional<byte[]> bytes = azureBlobStorage.getBytes("name");

    assertArrayEquals(content, bytes.get());
    assertOperationCount("other", "read", "success", 1);
  }

  @Test
  public void testGetBytesNotFound() {
    BlobStorageException blobStorageException = blobNotFound();
    when(blobClient.downloadContent()).thenThrow(blobStorageException);

    Optional<byte[]> bytes = azureBlobStorage.getBytes("name");

    assertFalse(bytes.isPresent());
    assertOperationCount("other", "read", "miss", 1);
  }

  @Test
  public void testGetBytesFailureRecordsMetricAndPropagates() {
    BlobStorageException blobStorageException = mock(BlobStorageException.class);
    when(blobStorageException.getErrorCode())
        .thenReturn(BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH);
    when(blobClient.downloadContent()).thenThrow(blobStorageException);

    assertThrows(BlobStorageException.class, () -> azureBlobStorage.getBytes("name"));
    assertOperationCount("other", "read", "failure", 1);
  }

  @Test
  public void testReadLatencyIsRecorded() {
    MockClock clock = new MockClock();
    SimpleMeterRegistry timedMeterRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    AzureBlobStorageConfigurationProperties properties =
        new AzureBlobStorageConfigurationProperties();
    properties.setPrefix("prefix");
    AzureBlobStorage timedAzureBlobStorage =
        new AzureBlobStorage(blobContainerClient, properties, timedMeterRegistry);
    when(blobClient.downloadContent())
        .thenAnswer(
            invocation -> {
              clock.add(Duration.ofMillis(37));
              return BinaryData.fromString("content");
            });

    timedAzureBlobStorage.getBytes("name");

    Timer timer =
        timedMeterRegistry
            .get(AzureBlobStorage.OPERATION_DURATION_METRIC)
            .tag("prefix", "other")
            .tag("operation", "read")
            .tag("result", "success")
            .timer();
    assertEquals(37, timer.totalTime(TimeUnit.MILLISECONDS), 0);
  }

  @Test
  public void testPutString() {
    ArgumentCaptor<BlobParallelUploadOptions> optionsCaptor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(optionsCaptor.capture(), eq(null), eq(Context.NONE)))
        .thenReturn(mock(Response.class));

    azureBlobStorage.put("name", "content", Retention.MIN_1_DAY);

    BlobParallelUploadOptions options = optionsCaptor.getValue();
    assertEquals("MIN_1_DAY", options.getTags().get("retention"));
    assertEquals("text/plain", options.getHeaders().getContentType());
    assertEquals("UTF-8", options.getHeaders().getContentEncoding());
    assertOperationCount("other", "write", "success", 1);
  }

  @Test
  public void testPutBytes() {
    ArgumentCaptor<BlobParallelUploadOptions> optionsCaptor =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(optionsCaptor.capture(), eq(null), eq(Context.NONE)))
        .thenReturn(mock(Response.class));

    azureBlobStorage.put("name", "content".getBytes(StandardCharsets.UTF_8), Retention.PERMANENT);

    BlobParallelUploadOptions options = optionsCaptor.getValue();
    assertEquals("PERMANENT", options.getTags().get("retention"));
    assertOperationCount("other", "write", "success", 1);
  }

  @Test
  public void testPutFailureRecordsMetricAndPropagates() {
    IllegalStateException azureFailure = new IllegalStateException("Upload denied");
    when(blobClient.uploadWithResponse(
            org.mockito.ArgumentMatchers.any(BlobParallelUploadOptions.class),
            eq(null),
            eq(Context.NONE)))
        .thenThrow(azureFailure);

    assertThrows(
        IllegalStateException.class,
        () -> azureBlobStorage.put("name", "content", Retention.PERMANENT));
    assertOperationCount("other", "write", "failure", 1);
  }

  @Test
  public void testDelete() {
    when(blobClient.deleteIfExists()).thenReturn(true);
    azureBlobStorage.delete("name");

    verify(blobClient).deleteIfExists();
    assertOperationCount("other", "delete", "success", 1);
  }

  @Test
  public void testDeleteMissingRecordsMiss() {
    when(blobClient.deleteIfExists()).thenReturn(false);

    azureBlobStorage.delete("name");

    assertOperationCount("other", "delete", "miss", 1);
  }

  @Test
  public void testExists() {
    when(blobClient.exists()).thenReturn(true);

    azureBlobStorage.exists("name");

    verify(blobClient).exists();
    assertOperationCount("other", "exists", "success", 1);
  }

  @Test
  public void testExistsMissingRecordsMiss() {
    when(blobClient.exists()).thenReturn(false);

    assertFalse(azureBlobStorage.exists("name"));

    assertOperationCount("other", "exists", "miss", 1);
  }

  @Test
  public void testKnownPrefixIsTaggedWithoutObjectName() {
    when(blobContainerClient.getBlobClient("prefix/pollable_task/42/input")).thenReturn(blobClient);
    when(blobClient.exists()).thenReturn(true);

    azureBlobStorage.exists("pollable_task/42/input");

    assertOperationCount("pollable_task", "exists", "success", 1);
  }

  @Test
  public void testGetAzureUrl() {
    when(blobClient.getBlobUrl())
        .thenReturn("https://example.blob.core.windows.net/container/prefix/name");

    assertEquals(
        "https://example.blob.core.windows.net/container/prefix/name",
        azureBlobStorage.getAzureUrl("name"));
  }

  @Test
  public void testFullName() {
    azureBlobStorage.exists("name");

    verify(blobContainerClient).getBlobClient("prefix/name");
  }

  @Test
  public void snapshotCreateUsesConditionalPermanentWriteAndRejectsCanonicalNames() {
    String name = snapshotName();
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    ArgumentCaptor<BlobParallelUploadOptions> options =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(
            options.capture(), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(mock(Response.class));
    org.junit.Assert.assertTrue(azureBlobStorage.createSnapshotIfAbsent(name, new byte[] {0, -1}));
    assertEquals("*", options.getValue().getRequestConditions().getIfNoneMatch());
    assertEquals("PERMANENT", options.getValue().getTags().get("retention"));
    assertThrows(
        IllegalArgumentException.class,
        () -> azureBlobStorage.createSnapshotIfAbsent("pollable_task/1/input", new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> azureBlobStorage.getSnapshotBytes("clob_storage_ws/a", 1));
  }

  @Test
  public void existingSnapshotIsNotOverwrittenAndProviderErrorsPropagate() {
    String name = snapshotName();
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    BlobStorageException exists = mock(BlobStorageException.class);
    when(exists.getErrorCode()).thenReturn(BlobErrorCode.CONDITION_NOT_MET);
    when(blobClient.uploadWithResponse(
            org.mockito.ArgumentMatchers.any(BlobParallelUploadOptions.class),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenThrow(exists);
    assertFalse(azureBlobStorage.createSnapshotIfAbsent(name, new byte[] {1}));
    BlobStorageException denied = mock(BlobStorageException.class);
    when(denied.getErrorCode()).thenReturn(BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH);
    when(blobClient.uploadWithResponse(
            org.mockito.ArgumentMatchers.any(BlobParallelUploadOptions.class),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenThrow(denied);
    assertThrows(
        BlobStorageException.class,
        () -> azureBlobStorage.createSnapshotIfAbsent(name, new byte[] {1}));
  }

  @Test
  public void snapshotReadBackIsBoundedAndChecksExactLengthIncludingEmptyContent() {
    String name = snapshotName();
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    when(blobClient.downloadStreamWithResponse(
            org.mockito.ArgumentMatchers.any(java.io.OutputStream.class),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenAnswer(
            call -> {
              ((java.io.OutputStream) call.getArgument(0)).write(new byte[] {0, -1});
              return mock(com.azure.storage.blob.models.BlobDownloadResponse.class);
            });
    assertArrayEquals(new byte[] {0, -1}, azureBlobStorage.getSnapshotBytes(name, 2));
    assertThrows(
        AzureBlobStorage.SnapshotVerificationException.class,
        () -> azureBlobStorage.getSnapshotBytes(name, 1));
    assertThrows(
        AzureBlobStorage.SnapshotVerificationException.class,
        () -> azureBlobStorage.getSnapshotBytes(name, 3));
    when(blobClient.downloadStreamWithResponse(
            org.mockito.ArgumentMatchers.any(java.io.OutputStream.class),
            eq(null),
            eq(null),
            eq(null),
            eq(false),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenReturn(mock(com.azure.storage.blob.models.BlobDownloadResponse.class));
    assertArrayEquals(new byte[0], azureBlobStorage.getSnapshotBytes(name, 0));
  }

  @Test
  public void pollableArchiveCreateIsConditionalPermanentAndNamespaceRestricted() {
    String name = "pollable_task_archive/v1/42.json";
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    ArgumentCaptor<BlobParallelUploadOptions> options =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(
            options.capture(), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(mock(Response.class));
    org.junit.Assert.assertTrue(
        azureBlobStorage.createPollableTaskArchiveIfAbsent(name, new byte[] {1}));
    assertEquals("*", options.getValue().getRequestConditions().getIfNoneMatch());
    assertEquals("PERMANENT", options.getValue().getTags().get("retention"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            azureBlobStorage.createPollableTaskArchiveIfAbsent(
                "pollable_task/42/input", new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class,
        () -> azureBlobStorage.createPollableTaskArchiveIfAbsent(snapshotName(), new byte[] {1}));
    BlobStorageException exists = mock(BlobStorageException.class);
    when(exists.getErrorCode()).thenReturn(BlobErrorCode.CONDITION_NOT_MET);
    when(blobClient.uploadWithResponse(
            org.mockito.ArgumentMatchers.any(BlobParallelUploadOptions.class),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenThrow(exists);
    assertFalse(azureBlobStorage.createPollableTaskArchiveIfAbsent(name, new byte[] {1}));
  }

  @Test
  public void canonicalMigrationUsesConditionalCreationAndSourceRetention() {
    String name = "clob_storage_ws/legacy";
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    ArgumentCaptor<BlobParallelUploadOptions> options =
        ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
    when(blobClient.uploadWithResponse(
            options.capture(), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(mock(Response.class));
    org.junit.Assert.assertTrue(
        azureBlobStorage.createCanonicalBlobForMigrationIfAbsent(
            name, new byte[] {1}, Retention.MIN_1_DAY));
    assertEquals("*", options.getValue().getRequestConditions().getIfNoneMatch());
    assertEquals("MIN_1_DAY", options.getValue().getTags().get("retention"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            azureBlobStorage.createCanonicalBlobForMigrationIfAbsent(
                "ai_review_execution/v1/capacity", new byte[] {1}, Retention.PERMANENT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            azureBlobStorage.createCanonicalBlobForMigrationIfAbsent(
                snapshotName(), new byte[] {1}, Retention.PERMANENT));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void canonicalReadChecksTagsAndPinsByteReadToObservedEtag() {
    String name = "clob_storage_ws/legacy";
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    var properties = mock(com.azure.storage.blob.models.BlobProperties.class);
    when(properties.getBlobSize()).thenReturn(2L);
    when(properties.getETag()).thenReturn("observed-etag");
    Response<com.azure.storage.blob.models.BlobProperties> response = mock(Response.class);
    when(response.getValue()).thenReturn(properties);
    when(blobClient.getPropertiesWithResponse(
            eq(null), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(response);
    Response<java.util.Map<String, String>> tags = mock(Response.class);
    when(tags.getValue()).thenReturn(java.util.Map.of("retention", "PERMANENT"));
    when(blobClient.getTagsWithResponse(eq(null), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(tags);
    ArgumentCaptor<com.azure.storage.blob.models.BlobRequestConditions> conditions =
        ArgumentCaptor.forClass(com.azure.storage.blob.models.BlobRequestConditions.class);
    when(blobClient.downloadStreamWithResponse(
            org.mockito.ArgumentMatchers.any(java.io.OutputStream.class),
            eq(null),
            eq(null),
            conditions.capture(),
            eq(false),
            eq(Duration.ofSeconds(30)),
            eq(Context.NONE)))
        .thenAnswer(
            call -> {
              ((java.io.OutputStream) call.getArgument(0)).write(new byte[] {1, 2});
              return mock(com.azure.storage.blob.models.BlobDownloadResponse.class);
            });
    var result = azureBlobStorage.inspectCanonicalBlobForMigration(name, 2).orElseThrow();
    assertArrayEquals(new byte[] {1, 2}, result.content());
    assertEquals("PERMANENT", result.retention());
    assertEquals("observed-etag", result.eTag());
    assertEquals("observed-etag", conditions.getValue().getIfMatch());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void canonicalLengthConflictDoesNotReadUnexpectedBytes() {
    String name = "clob_storage_ws/legacy";
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    var properties = mock(com.azure.storage.blob.models.BlobProperties.class);
    when(properties.getBlobSize()).thenReturn(1_000_000L);
    when(properties.getETag()).thenReturn("etag");
    Response<com.azure.storage.blob.models.BlobProperties> response = mock(Response.class);
    when(response.getValue()).thenReturn(properties);
    when(blobClient.getPropertiesWithResponse(
            eq(null), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(response);
    Response<java.util.Map<String, String>> tags = mock(Response.class);
    when(tags.getValue()).thenReturn(java.util.Map.of("retention", "PERMANENT"));
    when(blobClient.getTagsWithResponse(eq(null), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenReturn(tags);
    var result = azureBlobStorage.inspectCanonicalBlobForMigration(name, 2).orElseThrow();
    org.junit.Assert.assertNull(result.content());
    assertEquals(1_000_000L, result.length());
    org.mockito.Mockito.verify(blobClient, org.mockito.Mockito.never())
        .downloadStreamWithResponse(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  public void canonicalAbsenceAndAuthorizationFailureRemainDistinct() {
    String name = "clob_storage_ws/legacy";
    when(blobContainerClient.getBlobClient("prefix/" + name)).thenReturn(blobClient);
    BlobStorageException absent = blobNotFound();
    when(blobClient.getPropertiesWithResponse(
            eq(null), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenThrow(absent);
    assertFalse(azureBlobStorage.inspectCanonicalBlobForMigration(name, 1).isPresent());
    var denied = mock(BlobStorageException.class);
    when(denied.getErrorCode()).thenReturn(BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH);
    when(blobClient.getPropertiesWithResponse(
            eq(null), eq(Duration.ofSeconds(30)), eq(Context.NONE)))
        .thenThrow(denied);
    assertThrows(
        BlobStorageException.class,
        () -> azureBlobStorage.inspectCanonicalBlobForMigration(name, 1));
  }

  private String snapshotName() {
    return "mblob_migration/v1/00000000-0000-0000-0000-000000000000/1/" + "a".repeat(64);
  }

  BlobStorageException blobNotFound() {
    BlobStorageException blobStorageException = mock(BlobStorageException.class);
    when(blobStorageException.getErrorCode()).thenReturn(BlobErrorCode.BLOB_NOT_FOUND);
    return blobStorageException;
  }

  private void assertOperationCount(
      String prefix, String operation, String result, double expectedCount) {
    assertEquals(
        expectedCount,
        meterRegistry
            .get(AzureBlobStorage.OPERATION_DURATION_METRIC)
            .tag("prefix", prefix)
            .tag("operation", operation)
            .tag("result", result)
            .timer()
            .count(),
        0);
  }
}
