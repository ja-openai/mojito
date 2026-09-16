package com.box.l10n.mojito.service.pollableTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import java.util.Optional;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class PollableTaskActorResolutionTest {

  @Test
  public void resolvesCreatedByUserFromAncestorInsideServiceLookup() {
    User user = new User();
    user.setId(14L);
    PollableTask rootTask = new PollableTask();
    rootTask.setId(40L);
    rootTask.setCreatedByUser(user);
    PollableTask parentTask = new PollableTask();
    parentTask.setId(41L);
    parentTask.setParentTask(rootTask);
    PollableTask childTask = new PollableTask();
    childTask.setId(42L);
    childTask.setParentTask(parentTask);

    PollableTaskRepository repository = mock(PollableTaskRepository.class);
    when(repository.findById(childTask.getId())).thenReturn(Optional.of(childTask));
    PollableTaskService service = new PollableTaskService();
    service.transactionManager = mock(PlatformTransactionManager.class);
    when(service.transactionManager.getTransaction(any()))
        .thenReturn(new SimpleTransactionStatus());
    service.pollableTaskArchiveStorage = mock(PollableTaskArchiveStorage.class);
    service.pollableTaskRepository = repository;

    assertThat(service.getCreatedByUserIdWithAncestorFallback(childTask.getId()))
        .isEqualTo(user.getId());
  }
}
