package com.box.l10n.mojito.service.assetcontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageRouter;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.s3.S3BlobStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Uses the production prefix router with mocked providers; no external storage is contacted. */
public class AssetContentBlobStorageTest {
  private static final String CONTENT = "{/* mojito-id: intro */}\r\n# Bonjour 🌎\r\n";
  private static final String MD5 = DigestUtils.md5Hex(CONTENT);
  private static final String NAME = "v1/42/7/source/" + MD5;
  private static final String FULL_NAME = "asset_content/" + NAME;

  private final DatabaseBlobStorage database = mock(DatabaseBlobStorage.class);
  private final AzureBlobStorage azure = mock(AzureBlobStorage.class);
  private final S3BlobStorage s3 = mock(S3BlobStorage.class);
  private final BlobStorageConfigurationProperties configuration =
      new BlobStorageConfigurationProperties();
  private final StaticListableBeanFactory providers =
      new StaticListableBeanFactory(Map.of("database", database, "azure", azure, "s3", s3));
  private final AssetContentBlobStorage storage =
      new AssetContentBlobStorage(
          new StructuredBlobStorage(
              new BlobStorageRouter(
                  configuration,
                  providers.getBeanProvider(DatabaseBlobStorage.class),
                  providers.getBeanProvider(S3BlobStorage.class),
                  providers.getBeanProvider(AzureBlobStorage.class),
                  new SimpleMeterRegistry())),
          configuration);

  @Test
  public void databaseDefaultRejectsPayloadBeforeAnyStorageAccess() {
    assertThatThrownBy(() -> storage.put(42L, 7L, MD5, false, CONTENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires external blob storage");
    assertThatThrownBy(() -> storage.get(42L, 7L, MD5, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires external blob storage");
    verifyNoInteractions(database, azure, s3);
  }

  @Test
  public void explicitDatabaseRouteOverridesAnExternalDefaultAndStillRejectsWrites() {
    configuration.setDefaultType(BlobStorageType.AZURE);
    route(BlobStorageType.DATABASE);
    assertThatThrownBy(() -> storage.put(42L, 7L, MD5, false, CONTENT))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(database, azure, s3);
  }

  @Test
  public void azurePrefixStoresAnImmutablePermanentObjectAndReadsItWithIntegrityChecks() {
    route(BlobStorageType.AZURE);
    storage.put(42L, 7L, MD5, false, CONTENT);
    storage.put(42L, 7L, MD5, false, CONTENT);
    verify(azure, times(2)).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    when(azure.getString(FULL_NAME)).thenReturn(Optional.of(CONTENT));
    assertThat(storage.get(42L, 7L, MD5, false)).contains(CONTENT);
    verifyNoInteractions(database, s3);
  }

  @Test
  public void s3DefaultUsesTheSameSemanticPrefixAndScope() {
    configuration.setDefaultType(BlobStorageType.S3);
    storage.put(42L, 7L, MD5, false, CONTENT);
    verify(s3).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    verifyNoInteractions(database, azure);
  }

  @Test
  public void migrationFallbackPublishesOnlyAfterItsAzureWriteSucceeds() {
    route(BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);
    storage.put(42L, 7L, MD5, false, CONTENT);
    verify(azure).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    verifyNoInteractions(database, s3);
  }

  @Test
  public void directExternalFailuresPropagateWithoutReadingDeletingOrUsingDatabase() {
    RuntimeException unavailable = new IllegalStateException("External storage unavailable");
    doThrow(unavailable).when(azure).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    doThrow(unavailable).when(s3).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    route(BlobStorageType.AZURE);
    assertThatThrownBy(() -> storage.put(42L, 7L, MD5, false, CONTENT)).isSameAs(unavailable);
    route(BlobStorageType.S3);
    assertThatThrownBy(() -> storage.put(42L, 7L, MD5, false, CONTENT)).isSameAs(unavailable);
    verify(azure).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    verify(s3).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    verifyNoMoreInteractions(azure, s3);
    verifyNoInteractions(database);
  }

  @Test
  public void migrationFallbackWritesOnlyToAzureAndNeverRetriesFailuresInDatabase() {
    route(BlobStorageType.AZURE_WITH_DATABASE_FALLBACK);
    RuntimeException unavailable = new IllegalStateException("Azure unavailable");
    doThrow(unavailable).when(azure).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    assertThatThrownBy(() -> storage.put(42L, 7L, MD5, false, CONTENT)).isSameAs(unavailable);
    verify(azure).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    verifyNoMoreInteractions(azure);
    verifyNoInteractions(database, s3);
  }

  @Test
  public void objectNamesSeparateRevisionsAssetsAndBranches() {
    route(BlobStorageType.AZURE);
    String revised = CONTENT + "\r\nMore content.\r\n";
    String revisedMd5 = DigestUtils.md5Hex(revised);
    storage.put(42L, 7L, MD5, false, CONTENT);
    storage.put(42L, 7L, revisedMd5, false, revised);
    storage.put(43L, 7L, MD5, false, CONTENT);
    storage.put(42L, 8L, MD5, false, CONTENT);
    verify(azure).put(FULL_NAME, CONTENT, Retention.PERMANENT);
    verify(azure).put("asset_content/v1/42/7/source/" + revisedMd5, revised, Retention.PERMANENT);
    verify(azure).put("asset_content/v1/43/7/source/" + MD5, CONTENT, Retention.PERMANENT);
    verify(azure).put("asset_content/v1/42/8/source/" + MD5, CONTENT, Retention.PERMANENT);
    verifyNoMoreInteractions(azure);
  }

  @Test
  public void delayedConcurrentUploadCannotOverwriteAnotherRevision() throws Exception {
    route(BlobStorageType.AZURE);
    Map<String, String> stored = new ConcurrentHashMap<>();
    CountDownLatch oldUploadStarted = new CountDownLatch(1);
    CountDownLatch completeOldUpload = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              String content = invocation.getArgument(1);
              if (content.equals(CONTENT)) {
                oldUploadStarted.countDown();
                assertThat(completeOldUpload.await(5, TimeUnit.SECONDS)).isTrue();
              }
              stored.put(invocation.getArgument(0), content);
              return null;
            })
        .when(azure)
        .put(anyString(), anyString(), any(Retention.class));
    when(azure.getString(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
    String revised = CONTENT + "New paragraph.";
    String revisedMd5 = DigestUtils.md5Hex(revised);
    try (var executor = Executors.newFixedThreadPool(1)) {
      CompletableFuture<Void> oldUpload =
          CompletableFuture.runAsync(() -> storage.put(42L, 7L, MD5, false, CONTENT), executor);
      try {
        assertThat(oldUploadStarted.await(5, TimeUnit.SECONDS)).isTrue();
        storage.put(42L, 7L, revisedMd5, false, revised);
      } finally {
        completeOldUpload.countDown();
      }
      oldUpload.get(5, TimeUnit.SECONDS);
    }
    assertThat(storage.get(42L, 7L, MD5, false)).contains(CONTENT);
    assertThat(storage.get(42L, 7L, revisedMd5, false)).contains(revised);
    assertThat(stored).hasSize(2);
  }

  @Test
  public void invalidInputCannotWriteOrReadArbitraryObjects() {
    route(BlobStorageType.AZURE);
    assertThatThrownBy(() -> storage.put(42L, 7L, "invalid-md5", false, CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.put(null, 7L, MD5, false, CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.put(42L, 0L, MD5, false, CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.get(42L, 7L, "../other-object", false))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(database, azure, s3);
  }

  @Test
  public void missingObjectsRemainMissingAndCorruptedObjectsFailClosed() {
    route(BlobStorageType.AZURE);
    when(azure.getString(FULL_NAME)).thenReturn(Optional.empty());
    assertThat(storage.get(42L, 7L, MD5, false)).isEmpty();
    when(azure.getString(FULL_NAME)).thenReturn(Optional.of("Changed bytes"));
    assertThatThrownBy(() -> storage.get(42L, 7L, MD5, false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fingerprint mismatch");
  }

  @Test
  public void byteIdenticalSourceAndCatalogPayloadsUseSeparateObjects() {
    route(BlobStorageType.AZURE);
    String content = "[]";
    String md5 = DigestUtils.md5Hex(content);
    String sourceName = "asset_content/v1/42/7/source/" + md5;
    String catalogName = "asset_content/v1/42/7/catalog/" + md5;
    storage.put(42L, 7L, md5, false, content);
    storage.put(42L, 7L, md5, true, content);
    verify(azure).put(sourceName, content, Retention.PERMANENT);
    verify(azure).put(catalogName, content, Retention.PERMANENT);
    when(azure.getString(sourceName)).thenReturn(Optional.of(content));
    when(azure.getString(catalogName)).thenReturn(Optional.empty());
    assertThat(storage.get(42L, 7L, md5, false)).contains(content);
    assertThat(storage.get(42L, 7L, md5, true)).isEmpty();
  }

  @Test
  public void uppercaseFingerprintsAndMissingScopeCannotReachStorage() {
    route(BlobStorageType.AZURE);
    assertThatThrownBy(
            () -> storage.put(42L, 7L, MD5.toUpperCase(java.util.Locale.ROOT), false, CONTENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.get(null, 7L, MD5, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.get(42L, null, MD5, true))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(database, azure, s3);
  }

  private void route(BlobStorageType type) {
    configuration.getRouting().getPrefixes().put("asset-content", type);
  }
}
