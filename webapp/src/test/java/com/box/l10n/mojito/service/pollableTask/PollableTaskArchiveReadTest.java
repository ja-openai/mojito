package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.agentreview.IncidentReviewCreateJob;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobAccess;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJob;
import com.box.l10n.mojito.service.oaireview.AiReviewChatJobAccess;
import com.box.l10n.mojito.service.security.user.UserService;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class PollableTaskArchiveReadTest {

  private PollableTaskService taskService;

  private PollableTaskRepository taskRepository;

  private PollableTaskArchiveStorage archiveStorage;

  private AtomicInteger activeTransactions;

  @Before
  public void setUp() {
    taskRepository = mock(PollableTaskRepository.class);
    archiveStorage = mock(PollableTaskArchiveStorage.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    activeTransactions = new AtomicInteger();
    when(transactionManager.getTransaction(any()))
        .thenAnswer(
            invocation -> {
              TransactionDefinition definition = invocation.getArgument(0);
              boolean active =
                  definition.getPropagationBehavior()
                      != TransactionDefinition.PROPAGATION_NOT_SUPPORTED;
              if (active) activeTransactions.incrementAndGet();
              return new SimpleTransactionStatus(active);
            });
    doAnswer(
            invocation -> {
              TransactionStatus status = invocation.getArgument(0);
              if (status.isNewTransaction()) activeTransactions.decrementAndGet();
              return null;
            })
        .when(transactionManager)
        .commit(any());

    taskService = new PollableTaskService();
    taskService.pollableTaskRepository = taskRepository;
    taskService.pollableTaskArchiveStorage = archiveStorage;
    taskService.transactionManager = transactionManager;
  }

  @Test
  public void returnsDatabaseTaskWithoutCheckingAzure() {
    PollableTask task = task(42L);
    when(taskRepository.findById(42L)).thenReturn(Optional.of(task));

    assertThat(taskService.getPollableTask(42L)).isSameAs(task);
    verifyNoInteractions(archiveStorage);
    assertThat(activeTransactions.get()).isZero();
  }

  @Test
  public void readsAzureOnlyAfterDatabaseTransactionCloses() {
    PollableTask archivedTask = task(42L);
    when(taskRepository.findById(42L)).thenReturn(Optional.empty());
    when(archiveStorage.findArchivedTask(42L))
        .thenAnswer(
            invocation -> {
              assertThat(activeTransactions.get()).isZero();
              return Optional.of(archivedTask);
            });

    assertThat(taskService.getPollableTask(42L)).isSameAs(archivedTask);
  }

  @Test
  public void returnsNullWhenDatabaseAndAzureBothMiss() {
    when(taskRepository.findById(404L)).thenReturn(Optional.empty());
    when(archiveStorage.findArchivedTask(404L)).thenReturn(Optional.empty());

    assertThat(taskService.getPollableTask(404L)).isNull();
  }

  @Test
  public void propagatesAzureOperationalFailure() {
    RuntimeException failure = new IllegalStateException("Azure transport failed");
    when(taskRepository.findById(42L)).thenReturn(Optional.empty());
    when(archiveStorage.findArchivedTask(42L)).thenThrow(failure);

    assertThatThrownBy(() -> taskService.getPollableTask(42L)).isSameAs(failure);
  }

  @Test
  public void ownerLookupUsesArchivedCreatorOnlyAfterDatabaseMiss() {
    PollableTask archived = task(42L);
    User owner = new User();
    owner.setId(14L);
    archived.setCreatedByUser(owner);
    when(taskRepository.findById(42L)).thenReturn(Optional.empty());
    when(archiveStorage.findArchivedTask(42L))
        .thenAnswer(
            invocation -> {
              assertThat(activeTransactions.get()).isZero();
              return Optional.of(archived);
            });

    assertThat(taskService.getCreatedByUserIdWithAncestorFallback(42L)).isEqualTo(14L);

    when(taskRepository.findById(42L)).thenReturn(Optional.of(task(42L)));
    // An ownerless database row is authoritative; never borrow stale archived ownership.
    assertThat(taskService.getCreatedByUserIdWithAncestorFallback(42L)).isNull();
    verify(archiveStorage).findArchivedTask(42L);
  }

  @Test
  public void archivedChatOwnershipStillAllowsOnlyTheRequester() {
    PollableTask archived = task(42L);
    archived.setName(AiReviewChatJob.class.getCanonicalName());
    User owner = new User();
    owner.setId(14L);
    archived.setCreatedByUser(owner);
    when(taskRepository.findById(42L)).thenReturn(Optional.empty());
    when(archiveStorage.findArchivedTask(42L)).thenReturn(Optional.of(archived));
    UserService users = mock(UserService.class);
    when(users.getCurrentUser()).thenReturn(Optional.of(owner));
    AiReviewChatJobAccess access = new AiReviewChatJobAccess(taskService, users);

    access.assertCanRead(taskService.getPollableTask(42L));

    User anotherUser = new User();
    anotherUser.setId(15L);
    when(users.getCurrentUser()).thenReturn(Optional.of(anotherUser));
    assertThatThrownBy(() -> access.assertCanRead(taskService.getPollableTask(42L)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  public void archivedIncidentOwnershipStillAllowsOnlyTheRequester() {
    PollableTask archived = task(42L);
    archived.setName(IncidentReviewCreateJob.class.getCanonicalName());
    User owner = new User();
    owner.setId(14L);
    archived.setCreatedByUser(owner);
    when(taskRepository.findById(42L)).thenReturn(Optional.empty());
    when(archiveStorage.findArchivedTask(42L)).thenReturn(Optional.of(archived));
    UserService users = mock(UserService.class);
    when(users.getCurrentUser()).thenReturn(Optional.of(owner));
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    IncidentReviewJobAccess access = new IncidentReviewJobAccess(taskService, users);

    access.assertCanRead(taskService.getPollableTask(42L));

    User anotherUser = new User();
    anotherUser.setId(15L);
    when(users.getCurrentUser()).thenReturn(Optional.of(anotherUser));
    assertThatThrownBy(() -> access.assertCanRead(taskService.getPollableTask(42L)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");

    when(users.getCurrentUser()).thenReturn(Optional.of(owner));
    when(taskRepository.findById(42L)).thenReturn(Optional.of(task(42L)));
    // An ownerless live row must not inherit ownership from a stale archive.
    assertThatThrownBy(() -> access.assertCanRead(archived))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  public void freshOperationalLookupDoesNotLoadAnArchivedTask() {
    taskService.entityManager = mock(EntityManager.class);

    assertThat(taskService.getFreshPollableTask(42L)).isNull();

    verifyNoInteractions(archiveStorage);
  }

  @Test
  public void archivedTasksCannotBeRecreatedByLifecycleUpdates() {
    when(taskRepository.findById(42L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> taskService.finishTask(42L, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> taskService.updateMessage(42L, "late update"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> taskService.updateExpectedSubTaskNumber(42L, 1))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(archiveStorage);
    verify(taskRepository, never()).save(any());
  }

  private PollableTask task(long id) {
    PollableTask task = new PollableTask();
    task.setId(id);
    task.setSubTasks(new LinkedHashSet<>());
    return task;
  }
}
