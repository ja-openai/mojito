package com.box.l10n.mojito.service.blobstorage;

import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage;
import com.box.l10n.mojito.service.blobstorage.database.DatabaseBlobStorage.StoredBlob;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Routes writes to Azure and backfills missing objects from legacy database blob storage. */
public class AzureDatabaseFallbackBlobStorage implements BlobStorage {

  static final String READ_METRIC = "AzureDatabaseFallbackBlobStorage.read";

  private final AzureBlobStorage azureBlobStorage;

  private final DatabaseBlobStorage databaseBlobStorage;

  private final MeterRegistry meterRegistry;

  public AzureDatabaseFallbackBlobStorage(
      AzureBlobStorage azureBlobStorage,
      DatabaseBlobStorage databaseBlobStorage,
      MeterRegistry meterRegistry) {
    this.azureBlobStorage = azureBlobStorage;
    this.databaseBlobStorage = databaseBlobStorage;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public Optional<byte[]> getBytes(String name) {
    return get(
        name,
        "bytes",
        () -> azureBlobStorage.getBytes(name),
        StoredBlob::content,
        (content, retention) -> azureBlobStorage.put(name, content, retention));
  }

  @Override
  public Optional<String> getString(String name) {
    return get(
        name,
        "string",
        () -> azureBlobStorage.getString(name),
        storedBlob -> new String(storedBlob.content(), StandardCharsets.UTF_8),
        (content, retention) -> azureBlobStorage.put(name, content, retention));
  }

  private <T> Optional<T> get(
      String name,
      String format,
      Supplier<Optional<T>> getFromAzure,
      Function<StoredBlob, T> readContent,
      BiConsumer<T, Retention> writeToAzure) {
    String result = "azure_error";
    try {
      Optional<T> azureContent = getFromAzure.get();
      if (azureContent.isPresent()) {
        result = "azure_hit";
        return azureContent;
      }

      result = "database_error";
      Optional<StoredBlob> storedBlob = databaseBlobStorage.getStoredBlob(name);
      if (storedBlob.isEmpty()) {
        result = "miss";
        return Optional.empty();
      }

      result = "backfill_error";
      T content = readContent.apply(storedBlob.get());
      writeToAzure.accept(content, storedBlob.get().retention());
      result = "database_hit";
      return Optional.of(content);
    } finally {
      meterRegistry
          .counter(READ_METRIC, "prefix", getKnownPrefix(name), "format", format, "result", result)
          .increment();
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

  @Override
  public void put(String name, byte[] content, Retention retention) {
    azureBlobStorage.put(name, content, retention);
  }

  @Override
  public void put(String name, String content, Retention retention) {
    azureBlobStorage.put(name, content, retention);
  }

  @Override
  public void delete(String name) {
    azureBlobStorage.delete(name);
  }

  @Override
  public boolean exists(String name) {
    return azureBlobStorage.exists(name);
  }

  @Override
  public String getTargetDescription(String name) {
    return azureBlobStorage.getTargetDescription(name);
  }
}
