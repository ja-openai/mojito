package com.box.l10n.mojito.service.pollableTask;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK_ARCHIVE;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Versioned, permanently retained Azure snapshots of standalone completed tasks. */
@Service
public class PollableTaskArchiveStorage {

  static final int SCHEMA_VERSION = 1;

  static final String READ_METRIC = "PollableTaskArchiveStorage.read";

  private final StructuredBlobStorage structuredBlobStorage;

  private final BlobStorageConfigurationProperties blobStorageConfigurationProperties;

  private final ObjectMapper objectMapper;

  private final MeterRegistry meterRegistry;

  private final ObjectProvider<AzureBlobStorage> azureBlobStorage;

  public PollableTaskArchiveStorage(
      StructuredBlobStorage structuredBlobStorage,
      BlobStorageConfigurationProperties blobStorageConfigurationProperties,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      ObjectProvider<AzureBlobStorage> azureBlobStorage) {
    this.structuredBlobStorage = Objects.requireNonNull(structuredBlobStorage);
    this.blobStorageConfigurationProperties =
        Objects.requireNonNull(blobStorageConfigurationProperties);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
    this.azureBlobStorage = Objects.requireNonNull(azureBlobStorage);
  }

  public Optional<PollableTask> findArchivedTask(long taskId) {
    return findArchive(taskId).map(ArchivedPollableTask::toPollableTask);
  }

  Optional<ArchivedPollableTask> findArchive(long taskId) {
    if (!isAzureArchiveConfigured()) {
      recordRead("disabled");
      return Optional.empty();
    }

    String result = "error";
    try {
      Optional<String> archivedJson =
          structuredBlobStorage.getString(POLLABLE_TASK_ARCHIVE, getArchiveName(taskId));
      if (archivedJson.isEmpty()) {
        result = "miss";
        return Optional.empty();
      }

      ArchivedPollableTask archive =
          objectMapper.readValueUnchecked(archivedJson.get(), ArchivedPollableTask.class);
      validateArchive(taskId, archive);
      result = "hit";
      return Optional.of(archive);
    } finally {
      recordRead(result);
    }
  }

  boolean putArchive(ArchivedPollableTask archive) {
    if (!isAzureArchiveConfigured()) {
      throw new IllegalStateException(
          "Pollable-task archival requires the pollable-task-archive blob prefix to route directly to Azure");
    }
    validateArchive(archive.id(), archive);
    // The archive is immutable. A delayed worker whose lease expired cannot overwrite a snapshot
    // another worker has already verified and used to delete the MySQL row.
    return azureBlobStorage
        .getObject()
        .createPollableTaskArchiveIfAbsent(
            "pollable_task_archive/" + getArchiveName(archive.id()),
            objectMapper.writeValueAsStringUnchecked(archive).getBytes(StandardCharsets.UTF_8));
  }

  boolean isAzureArchiveConfigured() {
    return blobStorageConfigurationProperties
            .getStorageTypeForPrefix(POLLABLE_TASK_ARCHIVE)
            .orElse(blobStorageConfigurationProperties.getDefaultType())
        == BlobStorageType.AZURE;
  }

  String getArchiveName(long taskId) {
    return "v1/" + taskId + ".json";
  }

  private void validateArchive(long expectedTaskId, ArchivedPollableTask archive) {
    if (archive.schemaVersion() != SCHEMA_VERSION
        || archive.id() != expectedTaskId
        || archive.finishedDate() == null
        || archive.parentTaskId() != null
        || archive.expectedSubTaskNumber() != 0
        || archive.subTaskIds() == null
        || !archive.subTaskIds().isEmpty()) {
      throw new IllegalStateException("Invalid pollable-task archive for task: " + expectedTaskId);
    }
  }

  private void recordRead(String result) {
    meterRegistry.counter(READ_METRIC, "result", result).increment();
  }

  public record ArchivedPollableTask(
      int schemaVersion,
      long id,
      String name,
      ZonedDateTime createdDate,
      ZonedDateTime lastModifiedDate,
      ZonedDateTime finishedDate,
      String message,
      String errorMessage,
      String errorStack,
      int expectedSubTaskNumber,
      Long timeout,
      Long parentTaskId,
      List<Long> subTaskIds,
      ArchivedUser createdByUser) {

    /** User profile fields are historical presentation; only the stable owner ID is task state. */
    boolean matchesTaskState(ArchivedPollableTask other) {
      return other != null
          && schemaVersion == other.schemaVersion
          && id == other.id
          && Objects.equals(name, other.name)
          && Objects.equals(createdDate, other.createdDate)
          && Objects.equals(lastModifiedDate, other.lastModifiedDate)
          && Objects.equals(finishedDate, other.finishedDate)
          && Objects.equals(message, other.message)
          && Objects.equals(errorMessage, other.errorMessage)
          && Objects.equals(errorStack, other.errorStack)
          && expectedSubTaskNumber == other.expectedSubTaskNumber
          && Objects.equals(timeout, other.timeout)
          && Objects.equals(parentTaskId, other.parentTaskId)
          && Objects.equals(subTaskIds, other.subTaskIds)
          && Objects.equals(
              createdByUser == null ? null : createdByUser.id(),
              other.createdByUser == null ? null : other.createdByUser.id());
    }

    static ArchivedPollableTask from(PollableTask task) {
      User creator = task.getCreatedByUser();
      return new ArchivedPollableTask(
          SCHEMA_VERSION,
          task.getId(),
          task.getName(),
          canonicalTimestamp(task.getCreatedDate()),
          canonicalTimestamp(task.getLastModifiedDate()),
          canonicalTimestamp(task.getFinishedDate()),
          task.getMessage(),
          task.getErrorMessage(),
          task.getErrorStack(),
          task.getExpectedSubTaskNumber(),
          task.getTimeout(),
          task.getParentTask() == null ? null : task.getParentTask().getId(),
          task.getSubTasks() == null
              ? List.of()
              : task.getSubTasks().stream().map(PollableTask::getId).sorted().toList(),
          creator == null ? null : ArchivedUser.from(creator));
    }

    private static ZonedDateTime canonicalTimestamp(ZonedDateTime value) {
      return value == null
          ? null
          : value.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    PollableTask toPollableTask() {
      PollableTask task = new PollableTask();
      task.setId(id);
      task.setName(name);
      task.setCreatedDate(createdDate);
      task.setLastModifiedDate(lastModifiedDate);
      task.setFinishedDate(finishedDate);
      task.setMessage(message);
      task.setErrorMessage(errorMessage);
      task.setErrorStack(errorStack);
      task.setExpectedSubTaskNumber(expectedSubTaskNumber);
      task.setTimeout(timeout);
      task.setSubTasks(new LinkedHashSet<>());
      task.setCreatedByUser(createdByUser == null ? null : createdByUser.toUser());
      return task;
    }
  }

  public record ArchivedUser(
      Long id,
      String username,
      String commonName,
      String givenName,
      String surname,
      Boolean enabled,
      boolean canTranslateAllLocales,
      Boolean partiallyCreated) {

    static ArchivedUser from(User user) {
      return new ArchivedUser(
          user.getId(),
          user.getUsername(),
          user.getCommonName(),
          user.getGivenName(),
          user.getSurname(),
          user.getEnabled(),
          user.getCanTranslateAllLocales(),
          user.getPartiallyCreated());
    }

    User toUser() {
      User user = new User();
      user.setId(id);
      user.setUsername(username);
      user.setCommonName(commonName);
      user.setGivenName(givenName);
      user.setSurname(surname);
      user.setEnabled(enabled);
      user.setCanTranslateAllLocales(canTranslateAllLocales);
      user.setPartiallyCreated(partiallyCreated);
      return user;
    }
  }
}
