package com.box.l10n.mojito.service.security.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.Role;
import java.util.Set;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class UserServiceTransactionTest {

  @Test
  public void saveUserWithRoleCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestUserService service = service(transactionManager);
    User user = new User();

    User result =
        service.saveUserWithRole(
            user, "password", Role.ROLE_USER, null, null, null, null, true, false);

    assertSame(user, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void saveUserWithRoleRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestUserService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.saveUserWithRole(
          new User(), "password", Role.ROLE_USER, null, null, null, null, true, false);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected saveUserWithRole to rethrow the failure");
  }

  @Test
  public void updatePasswordRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestUserService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.updatePassword("old", "new");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updatePassword to rethrow the failure");
  }

  @Test
  public void findOrCreateLeverageUserCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestUserService service = service(transactionManager);

    User result = service.findOrCreateLeverageUser();

    assertSame(service.user, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateCreatedByUserToSystemUserCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestUserService service = service(transactionManager);

    service.updateCreatedByUserToSystemUser(new User());

    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteUserRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestUserService service = service(transactionManager);
    service.failure = new IllegalStateException("failed");

    try {
      service.deleteUser(new User());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected deleteUser to rethrow the failure");
  }

  private TestUserService service(PlatformTransactionManager transactionManager) {
    TestUserService service = new TestUserService();
    service.transactionManager = transactionManager;
    return service;
  }

  private static class TestUserService extends UserService {
    User user = new User();
    RuntimeException failure;

    @Override
    User saveUserWithRoleNoTx(
        User user,
        String password,
        Role role,
        String givenName,
        String surname,
        String commonName,
        Set<String> translatableLocales,
        boolean canTranslateAllLocales,
        boolean partiallyCreated) {
      if (failure != null) {
        throw failure;
      }
      return user;
    }

    @Override
    User updatePasswordNoTx(String currentPassword, String newPassword) {
      if (failure != null) {
        throw failure;
      }
      return user;
    }

    @Override
    User findOrCreateLeverageUserNoTx() {
      if (failure != null) {
        throw failure;
      }
      return user;
    }

    @Override
    void updateCreatedByUserToSystemUserNoTx(User userToUpdate) {
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    void deleteUserNoTx(User user) {
      if (failure != null) {
        throw failure;
      }
    }
  }
}
