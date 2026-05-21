package com.box.l10n.mojito.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class BootstrapTransactionTest {

  @Test
  public void createSystemUserCommitsTransaction() {
    Bootstrap bootstrap = bootstrap();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(bootstrap.transactionManager.getTransaction(any())).thenReturn(transaction);
    User systemUser = new User();
    when(bootstrap.userService.createUserWithRole(
            eq(UserService.SYSTEM_USERNAME), any(), eq(Role.ROLE_ADMIN)))
        .thenReturn(systemUser);

    bootstrap.createSystemUser();

    assertEquals(false, systemUser.getEnabled());
    verify(bootstrap.userRepository).save(systemUser);
    verify(bootstrap.userService).updateCreatedByUserToSystemUser(systemUser);
    verify(bootstrap.transactionManager).commit(transaction);
    verify(bootstrap.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createSystemUserRollsBackTransaction() {
    Bootstrap bootstrap = bootstrap();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(bootstrap.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(bootstrap.userService.createUserWithRole(
            eq(UserService.SYSTEM_USERNAME), any(), eq(Role.ROLE_ADMIN)))
        .thenThrow(new IllegalStateException("failed"));

    try {
      bootstrap.createSystemUser();
      fail("Expected createSystemUser to rethrow the creation failure");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
    }

    verify(bootstrap.transactionManager).rollback(transaction);
    verify(bootstrap.transactionManager, never()).commit(transaction);
  }

  private Bootstrap bootstrap() {
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.userService = mock(UserService.class);
    bootstrap.userRepository = mock(UserRepository.class);
    bootstrap.transactionManager = mock(PlatformTransactionManager.class);
    return bootstrap;
  }
}
