package com.box.l10n.mojito.service.security.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.User;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class UserDeletionServiceTransactionTest {

  @Test
  public void hardDeleteUserCommitsRequiresNewTransaction() {
    UserDeletionService service = userDeletionService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.userRepository.findById(7L)).thenReturn(Optional.of(new User()));

    service.hardDeleteUser(7L);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(service.transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        transactionDefinitionCaptor.getValue().getPropagationBehavior());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
    verify(service.userRepository).deleteById(7L);
    verify(service.userRepository).flush();
  }

  @Test
  public void hardDeleteUserRollsBackRequiresNewTransaction() {
    UserDeletionService service = userDeletionService();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.userRepository.findById(7L))
        .thenThrow(new DataIntegrityViolationException("still referenced"));

    try {
      service.hardDeleteUser(7L);
      fail("Expected hardDeleteUser to rethrow the delete failure");
    } catch (DataIntegrityViolationException e) {
      assertEquals("still referenced", e.getMessage());
    }

    verify(service.transactionManager).rollback(transaction);
    verify(service.transactionManager, never()).commit(transaction);
  }

  private UserDeletionService userDeletionService() {
    UserDeletionService service = new UserDeletionService();
    service.userRepository = mock(UserRepository.class);
    service.authorityRepository = mock(AuthorityRepository.class);
    service.userLocaleRepository = mock(UserLocaleRepository.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }
}
