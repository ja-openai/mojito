package com.box.l10n.mojito.service.blobstorage;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSALATE_NO_BATCH_OUTPUT;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_LINEAGE;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.AI_TRANSLATE_NO_BATCH_OUTPUT;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.BULK_IMPORT_LINEAGE;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.IMAGE;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.MULTI_BRANCH_STATE;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK;
import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.TEXT_UNIT_DTOS_CACHE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage.StoredBlob;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class StructuredBlobStorageTest {

  @Mock BlobStorage blobStorage;

  @Mock AzureBlobStorage azureBlobStorage;

  @Mock DatabaseBlobStorage databaseBlobStorage;

  @Mock BlobStorageRouter blobStorageRouter;

  StructuredBlobStorage structuredBlobStorage;

  SimpleMeterRegistry meterRegistry;

  @Before
  public void before() {
    structuredBlobStorage = new StructuredBlobStorage(blobStorageRouter);
    meterRegistry = new SimpleMeterRegistry();
  }

  @Test
  public void getPollableTask() {
    assertEquals("pollable_task/test1", structuredBlobStorage.getFullName(POLLABLE_TASK, "test1"));
  }

  @Test
  public void getImage() {
    assertEquals("image/test1.jpg", structuredBlobStorage.getFullName(IMAGE, "test1.jpg"));
  }

  @Test
  public void getBulkImportLineage() {
    assertEquals(
        "bulk_import_lineage/run-id/input.json",
        structuredBlobStorage.getFullName(BULK_IMPORT_LINEAGE, "run-id/input.json"));
  }

  @Test
  public void noBatchReportPrefixesUseDistinctObjectNames() {
    assertEquals(
        "ai_translate_no_batch_output/42/report",
        structuredBlobStorage.getFullName(AI_TRANSLATE_NO_BATCH_OUTPUT, "42/report"));
    assertEquals(
        "ai_transalate_no_batch_output/42/report",
        structuredBlobStorage.getFullName(AI_TRANSALATE_NO_BATCH_OUTPUT, "42/report"));
  }

  @Test
  public void testRetention() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(blobStorage);

    structuredBlobStorage.put(
        POLLABLE_TASK, "testretention", "testrentention-content", Retention.MIN_1_DAY);
    verify(blobStorage)
        .put("pollable_task/testretention", "testrentention-content", Retention.MIN_1_DAY);
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void azureStringHitDoesNotReadDatabase() {
    when(blobStorageRouter.getBlobStorage(AI_TRANSLATE_LINEAGE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("ai_translate_lineage/run/42"))
        .thenReturn(Optional.of("azure-value"));

    assertThat(structuredBlobStorage.getString(AI_TRANSLATE_LINEAGE, "run/42"))
        .contains("azure-value");
    verify(azureBlobStorage, never()).put(anyString(), anyString(), any(Retention.class));
    verifyNoInteractions(databaseBlobStorage);
    assertReadCount("ai_translate_lineage", "string", "azure_hit", 1);
  }

  @Test
  public void plainAzureStringRouteDoesNotReadDatabase() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureBlobStorage);
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());

    assertThat(structuredBlobStorage.getString(POLLABLE_TASK, "42/input")).isEmpty();
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void azureBytesHitDoesNotReadDatabase() {
    byte[] content = new byte[] {1, 2, 3};
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getBytes("text_unit_dtos_cache/asset/42.smile"))
        .thenReturn(Optional.of(content));

    assertThat(structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "asset/42.smile"))
        .contains(content);
    verify(azureBlobStorage, never()).put(anyString(), any(byte[].class), any(Retention.class));
    verifyNoInteractions(databaseBlobStorage);
    assertReadCount("text_unit_dtos_cache", "bytes", "azure_hit", 1);
  }

  @Test
  public void plainAzureBytesRouteDoesNotReadDatabase() {
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE)).thenReturn(azureBlobStorage);
    when(azureBlobStorage.getBytes("text_unit_dtos_cache/asset/42.smile"))
        .thenReturn(Optional.empty());

    assertThat(structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "asset/42.smile")).isEmpty();
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void missingPollableInputBackfillsAzureWithTemporaryRetention() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("pollable_task/42/input"))
        .thenReturn(Optional.of(storedString("database-input", Retention.MIN_1_DAY)));

    assertThat(structuredBlobStorage.getString(POLLABLE_TASK, "42/input"))
        .contains("database-input");
    verify(azureBlobStorage).put("pollable_task/42/input", "database-input", Retention.MIN_1_DAY);
    verify(databaseBlobStorage, times(1)).getStoredBlob("pollable_task/42/input");
    assertReadCount("pollable_task", "string", "database_hit", 1);
  }

  @Test
  public void missingPollableOutputBackfillsAzureWithTemporaryRetention() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("pollable_task/42/output")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("pollable_task/42/output"))
        .thenReturn(Optional.of(storedString("database-output", Retention.MIN_1_DAY)));

    assertThat(structuredBlobStorage.getString(POLLABLE_TASK, "42/output"))
        .contains("database-output");
    verify(azureBlobStorage).put("pollable_task/42/output", "database-output", Retention.MIN_1_DAY);
  }

  @Test
  public void missingNonPollableStringBackfillsAzureWithPermanentRetention() {
    when(blobStorageRouter.getBlobStorage(MULTI_BRANCH_STATE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("multi_branch_state/extraction/42"))
        .thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("multi_branch_state/extraction/42"))
        .thenReturn(Optional.of(storedString("database-state", Retention.PERMANENT)));

    assertThat(structuredBlobStorage.getString(MULTI_BRANCH_STATE, "extraction/42"))
        .contains("database-state");
    verify(azureBlobStorage)
        .put("multi_branch_state/extraction/42", "database-state", Retention.PERMANENT);
  }

  @Test
  public void missingJsonCacheBackfillsAzureWithPermanentRetention() {
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("text_unit_dtos_cache/asset/42/locale/7"))
        .thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("text_unit_dtos_cache/asset/42/locale/7"))
        .thenReturn(Optional.of(storedString("{\"textUnits\":[]}", Retention.PERMANENT)));

    assertThat(structuredBlobStorage.getString(TEXT_UNIT_DTOS_CACHE, "asset/42/locale/7"))
        .contains("{\"textUnits\":[]}");
    verify(azureBlobStorage)
        .put("text_unit_dtos_cache/asset/42/locale/7", "{\"textUnits\":[]}", Retention.PERMANENT);
  }

  @Test
  public void missingSmileCacheBackfillsAzureWithPermanentRetention() {
    byte[] content = new byte[] {4, 5, 6};
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getBytes("text_unit_dtos_cache/asset/42.smile"))
        .thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("text_unit_dtos_cache/asset/42.smile"))
        .thenReturn(Optional.of(new StoredBlob(content, Retention.PERMANENT)));

    assertThat(structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "asset/42.smile"))
        .contains(content);
    verify(azureBlobStorage)
        .put("text_unit_dtos_cache/asset/42.smile", content, Retention.PERMANENT);
    verify(databaseBlobStorage, times(1)).getStoredBlob("text_unit_dtos_cache/asset/42.smile");
    assertReadCount("text_unit_dtos_cache", "bytes", "database_hit", 1);
  }

  @Test
  public void stringRemainsMissingWhenBothBackendsMiss() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("pollable_task/42/input")).thenReturn(Optional.empty());

    assertThat(structuredBlobStorage.getString(POLLABLE_TASK, "42/input")).isEmpty();
    verify(azureBlobStorage, never()).put(anyString(), anyString(), any(Retention.class));
    assertReadCount("pollable_task", "string", "miss", 1);
  }

  @Test
  public void bytesRemainMissingWhenBothBackendsMiss() {
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getBytes("text_unit_dtos_cache/42.smile")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("text_unit_dtos_cache/42.smile"))
        .thenReturn(Optional.empty());

    assertThat(structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "42.smile")).isEmpty();
    verify(azureBlobStorage, never()).put(anyString(), any(byte[].class), any(Retention.class));
    assertReadCount("text_unit_dtos_cache", "bytes", "miss", 1);
  }

  @Test
  public void azureStringFailurePropagatesWithoutDatabaseFallback() {
    RuntimeException azureFailure = new IllegalStateException("Azure authorization denied");
    when(blobStorageRouter.getBlobStorage(AI_TRANSLATE_LINEAGE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("ai_translate_lineage/run/42")).thenThrow(azureFailure);

    assertThatThrownBy(() -> structuredBlobStorage.getString(AI_TRANSLATE_LINEAGE, "run/42"))
        .isSameAs(azureFailure);
    verifyNoInteractions(databaseBlobStorage);
    assertReadCount("ai_translate_lineage", "string", "azure_error", 1);
  }

  @Test
  public void azureBytesFailurePropagatesWithoutDatabaseFallback() {
    RuntimeException azureFailure = new IllegalStateException("Azure transport failed");
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getBytes("text_unit_dtos_cache/42.smile")).thenThrow(azureFailure);

    assertThatThrownBy(() -> structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "42.smile"))
        .isSameAs(azureFailure);
    verifyNoInteractions(databaseBlobStorage);
    assertReadCount("text_unit_dtos_cache", "bytes", "azure_error", 1);
  }

  @Test
  public void databaseFallbackFailurePropagatesAndRecordsMetric() {
    RuntimeException databaseFailure = new IllegalStateException("Database unavailable");
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("pollable_task/42/input")).thenThrow(databaseFailure);

    assertThatThrownBy(() -> structuredBlobStorage.getString(POLLABLE_TASK, "42/input"))
        .isSameAs(databaseFailure);
    assertReadCount("pollable_task", "string", "database_error", 1);
  }

  @Test
  public void unrecognizedPrefixUsesBoundedMetricTag() {
    when(azureBlobStorage.getString("user-supplied-prefix/42")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("user-supplied-prefix/42")).thenReturn(Optional.empty());

    assertThat(azureWithDatabaseFallback().getString("user-supplied-prefix/42")).isEmpty();
    assertReadCount("other", "string", "miss", 1);
  }

  @Test
  public void azureStringBackfillFailurePropagates() {
    RuntimeException azureFailure = new IllegalStateException("Azure write authorization denied");
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("pollable_task/42/input"))
        .thenReturn(Optional.of(storedString("database-input", Retention.MIN_1_DAY)));
    doThrow(azureFailure)
        .when(azureBlobStorage)
        .put("pollable_task/42/input", "database-input", Retention.MIN_1_DAY);

    assertThatThrownBy(() -> structuredBlobStorage.getString(POLLABLE_TASK, "42/input"))
        .isSameAs(azureFailure);
    assertReadCount("pollable_task", "string", "backfill_error", 1);
  }

  @Test
  public void azureBytesBackfillFailurePropagates() {
    byte[] content = new byte[] {4, 5, 6};
    RuntimeException azureFailure = new IllegalStateException("Azure write transport failed");
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getBytes("text_unit_dtos_cache/asset/42.smile"))
        .thenReturn(Optional.empty());
    when(databaseBlobStorage.getStoredBlob("text_unit_dtos_cache/asset/42.smile"))
        .thenReturn(Optional.of(new StoredBlob(content, Retention.PERMANENT)));
    doThrow(azureFailure)
        .when(azureBlobStorage)
        .put("text_unit_dtos_cache/asset/42.smile", content, Retention.PERMANENT);

    assertThatThrownBy(() -> structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "asset/42.smile"))
        .isSameAs(azureFailure);
    assertReadCount("text_unit_dtos_cache", "bytes", "backfill_error", 1);
  }

  @Test
  public void databasePrimaryDoesNotRepeatMissingStringRead() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(databaseBlobStorage);
    when(databaseBlobStorage.getString("pollable_task/42/input")).thenReturn(Optional.empty());

    assertThat(structuredBlobStorage.getString(POLLABLE_TASK, "42/input")).isEmpty();
    verify(databaseBlobStorage, times(1)).getString("pollable_task/42/input");
  }

  @Test
  public void databasePrimaryDoesNotRepeatMissingBytesRead() {
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE)).thenReturn(databaseBlobStorage);
    when(databaseBlobStorage.getBytes("text_unit_dtos_cache/42.smile"))
        .thenReturn(Optional.empty());

    assertThat(structuredBlobStorage.getBytes(TEXT_UNIT_DTOS_CACHE, "42.smile")).isEmpty();
    verify(databaseBlobStorage, times(1)).getBytes("text_unit_dtos_cache/42.smile");
  }

  @Test
  public void binaryWritesRemainOnRoutedStorageOnly() {
    byte[] content = new byte[] {7, 8, 9};
    when(blobStorageRouter.getBlobStorage(TEXT_UNIT_DTOS_CACHE))
        .thenReturn(azureWithDatabaseFallback());

    structuredBlobStorage.putBytes(TEXT_UNIT_DTOS_CACHE, "42.smile", content, Retention.PERMANENT);

    verify(azureBlobStorage).put("text_unit_dtos_cache/42.smile", content, Retention.PERMANENT);
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void existenceAndDeletionUseOnlyRoutedStorage() {
    when(blobStorageRouter.getBlobStorage(MULTI_BRANCH_STATE))
        .thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.exists("multi_branch_state/extraction/42")).thenReturn(false);

    assertThat(structuredBlobStorage.exists(MULTI_BRANCH_STATE, "extraction/42")).isFalse();
    structuredBlobStorage.delete(MULTI_BRANCH_STATE, "extraction/42");

    verify(azureBlobStorage).delete("multi_branch_state/extraction/42");
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void stringWritesPreserveAzureStringUploadBehavior() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());

    structuredBlobStorage.put(POLLABLE_TASK, "42/input", "content", Retention.MIN_1_DAY);

    verify(azureBlobStorage).put("pollable_task/42/input", "content", Retention.MIN_1_DAY);
    verifyNoInteractions(databaseBlobStorage);
  }

  @Test
  public void targetDescriptionUsesAzureDestination() {
    when(blobStorageRouter.getBlobStorage(POLLABLE_TASK)).thenReturn(azureWithDatabaseFallback());
    when(azureBlobStorage.getTargetDescription("pollable_task/42/input"))
        .thenReturn("https://example.blob.core.windows.net/container/pollable_task/42/input");

    assertThat(structuredBlobStorage.getTargetDescription(POLLABLE_TASK, "42/input"))
        .isEqualTo("https://example.blob.core.windows.net/container/pollable_task/42/input");
    verifyNoInteractions(databaseBlobStorage);
  }

  private AzureDatabaseFallbackBlobStorage azureWithDatabaseFallback() {
    return new AzureDatabaseFallbackBlobStorage(
        azureBlobStorage, databaseBlobStorage, meterRegistry);
  }

  private StoredBlob storedString(String content, Retention retention) {
    return new StoredBlob(content.getBytes(StandardCharsets.UTF_8), retention);
  }

  private void assertReadCount(String prefix, String format, String result, double expectedCount) {
    assertThat(
            meterRegistry
                .get(AzureDatabaseFallbackBlobStorage.READ_METRIC)
                .tag("prefix", prefix)
                .tag("format", format)
                .tag("result", result)
                .counter()
                .count())
        .isEqualTo(expectedCount);
  }
}
