package com.box.l10n.mojito.service.pollableTask;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.POLLABLE_TASK_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskArchiveStorage.ArchivedPollableTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

public class PollableTaskArchiveStorageTest {

  private StructuredBlobStorage structuredBlobStorage;

  private AzureBlobStorage azureBlobStorage;

  private BlobStorageConfigurationProperties blobStorageProperties;

  private SimpleMeterRegistry meterRegistry;

  private ObjectMapper objectMapper;

  private PollableTaskArchiveStorage archiveStorage;

  @Before
  public void setUp() {
    structuredBlobStorage = mock(StructuredBlobStorage.class);
    azureBlobStorage = mock(AzureBlobStorage.class);
    blobStorageProperties = new BlobStorageConfigurationProperties();
    blobStorageProperties.setDefaultType(BlobStorageType.DATABASE);
    blobStorageProperties
        .getRouting()
        .getPrefixes()
        .put("pollable-task-archive", BlobStorageType.AZURE);
    meterRegistry = new SimpleMeterRegistry();
    objectMapper = ObjectMapper.withNoFailOnUnknownProperties();
    archiveStorage =
        new PollableTaskArchiveStorage(
            structuredBlobStorage,
            blobStorageProperties,
            objectMapper,
            meterRegistry,
            new StaticListableBeanFactory(Map.of("azure", azureBlobStorage))
                .getBeanProvider(AzureBlobStorage.class));
  }

  @Test
  public void storesVersionedPermanentArchiveAndRehydratesTaskAndCreator() {
    PollableTask task = task(42L);
    User creator = new User();
    creator.setId(7L);
    creator.setUsername("translator@example.com");
    creator.setCommonName("Task Owner");
    creator.setGivenName("Task");
    creator.setSurname("Owner");
    creator.setEnabled(true);
    task.setCreatedByUser(creator);
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);

    archiveStorage.putArchive(archive);

    ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
    verify(azureBlobStorage)
        .createPollableTaskArchiveIfAbsent(
            eq("pollable_task_archive/v1/42.json"), content.capture());
    String json = new String(content.getValue(), StandardCharsets.UTF_8);
    assertThat(objectMapper.readTreeUnchecked(json).get("schemaVersion").asInt()).isEqualTo(1);

    when(structuredBlobStorage.getString(POLLABLE_TASK_ARCHIVE, "v1/42.json"))
        .thenReturn(Optional.of(json));

    assertThat(archiveStorage.findArchive(42L)).contains(archive);
    PollableTask restored = archiveStorage.findArchivedTask(42L).orElseThrow();

    assertThat(restored.getId()).isEqualTo(42L);
    assertThat(restored.getName()).isEqualTo(task.getName());
    assertThat(restored.getCreatedDate().toInstant()).isEqualTo(task.getCreatedDate().toInstant());
    assertThat(restored.getFinishedDate().toInstant())
        .isEqualTo(task.getFinishedDate().toInstant());
    assertThat(restored.getMessage()).isEqualTo(task.getMessage());
    assertThat(restored.getErrorMessage()).isEqualTo(task.getErrorMessage());
    assertThat(restored.getErrorStack()).isEqualTo(task.getErrorStack());
    assertThat(restored.isAllFinished()).isTrue();
    assertThat(restored.getSubTasks()).isEmpty();
    assertThat(restored.getCreatedByUser().getId()).isEqualTo(7L);
    assertThat(restored.getCreatedByUser().getUsername()).isEqualTo("translator@example.com");
    assertThat(restored.getCreatedByUser().getCommonName()).isEqualTo("Task Owner");
    assertThat(
            meterRegistry
                .get(PollableTaskArchiveStorage.READ_METRIC)
                .tag("result", "hit")
                .counter()
                .count())
        .isEqualTo(2);
  }

  @Test
  public void doesNotReadDatabaseBlobStorageWhenArchiveRouteIsAbsent() {
    blobStorageProperties.getRouting().getPrefixes().clear();

    assertThat(archiveStorage.findArchivedTask(404L)).isEmpty();

    verifyNoInteractions(structuredBlobStorage);
    assertThat(
            meterRegistry
                .get(PollableTaskArchiveStorage.READ_METRIC)
                .tag("result", "disabled")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void missingAzureObjectIsANormalMiss() {
    when(structuredBlobStorage.getString(POLLABLE_TASK_ARCHIVE, "v1/404.json"))
        .thenReturn(Optional.empty());

    assertThat(archiveStorage.findArchivedTask(404L)).isEmpty();
    assertThat(
            meterRegistry
                .get(PollableTaskArchiveStorage.READ_METRIC)
                .tag("result", "miss")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void canonicalizesDatabaseTimeZonesAndSubMillisecondPrecision() {
    PollableTask task = task(42L);
    task.setCreatedDate(ZonedDateTime.parse("2025-01-01T10:00:00.123456789-08:00"));
    task.setLastModifiedDate(ZonedDateTime.parse("2025-01-01T10:05:00.987654321-08:00"));
    task.setFinishedDate(ZonedDateTime.parse("2025-01-01T10:05:00.987654321-08:00"));
    ArchivedPollableTask archive = ArchivedPollableTask.from(task);
    String json = objectMapper.writeValueAsStringUnchecked(archive);
    when(structuredBlobStorage.getString(POLLABLE_TASK_ARCHIVE, "v1/42.json"))
        .thenReturn(Optional.of(json));

    assertThat(archiveStorage.findArchive(42L)).contains(archive);
    assertThat(archive.createdDate().toInstant())
        .isEqualTo(task.getCreatedDate().toInstant().truncatedTo(ChronoUnit.MILLIS));
    assertThat(archive.finishedDate().toInstant())
        .isEqualTo(task.getFinishedDate().toInstant().truncatedTo(ChronoUnit.MILLIS));
  }

  @Test
  public void azureOperationalFailuresPropagate() {
    RuntimeException failure = new IllegalStateException("Azure authorization denied");
    when(structuredBlobStorage.getString(POLLABLE_TASK_ARCHIVE, "v1/42.json")).thenThrow(failure);

    assertThatThrownBy(() -> archiveStorage.findArchivedTask(42L)).isSameAs(failure);
    assertThat(
            meterRegistry
                .get(PollableTaskArchiveStorage.READ_METRIC)
                .tag("result", "error")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  public void rejectsArchivesWithUnexpectedIdentityOrGraphShape() {
    ArchivedPollableTask wrongTask = ArchivedPollableTask.from(task(43L));
    when(structuredBlobStorage.getString(POLLABLE_TASK_ARCHIVE, "v1/42.json"))
        .thenReturn(Optional.of(objectMapper.writeValueAsStringUnchecked(wrongTask)));

    assertThatThrownBy(() -> archiveStorage.findArchivedTask(42L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid pollable-task archive");
  }

  @Test
  public void rejectsArchiveWritesWithoutDirectAzureRouting() {
    blobStorageProperties.getRouting().getPrefixes().clear();

    assertThatThrownBy(() -> archiveStorage.putArchive(ArchivedPollableTask.from(task(42L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("route directly to Azure");
    verifyNoInteractions(structuredBlobStorage);
  }

  @Test
  public void taskComparisonIgnoresMutableOwnerProfileButRequiresTheSameOwnerId() {
    PollableTask task = task(42L);
    User owner = new User();
    owner.setId(7L);
    owner.setCommonName("Original name");
    owner.setEnabled(true);
    task.setCreatedByUser(owner);
    ArchivedPollableTask snapshot = ArchivedPollableTask.from(task);
    owner.setCommonName("Renamed user");
    owner.setEnabled(false);
    owner.setCanTranslateAllLocales(false);

    assertThat(snapshot.matchesTaskState(ArchivedPollableTask.from(task))).isTrue();
    assertThat(snapshot.createdByUser().commonName()).isEqualTo("Original name");
    owner.setId(8L);
    assertThat(snapshot.matchesTaskState(ArchivedPollableTask.from(task))).isFalse();
  }

  private PollableTask task(long id) {
    PollableTask task = new PollableTask();
    task.setId(id);
    task.setName("ExampleJob");
    task.setCreatedDate(ZonedDateTime.parse("2025-01-01T10:00:00Z"));
    task.setLastModifiedDate(ZonedDateTime.parse("2025-01-01T10:05:00Z"));
    task.setFinishedDate(ZonedDateTime.parse("2025-01-01T10:05:00Z"));
    task.setMessage("{\"message\":\"completed\"}");
    task.setErrorMessage("{\"expected\":false}");
    task.setErrorStack("java.lang.IllegalStateException: archived failure");
    task.setTimeout(60L);
    task.setSubTasks(new LinkedHashSet<>());
    return task;
  }
}
