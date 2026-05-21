package com.box.l10n.mojito.service.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class RepositoryServiceTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestRepositoryService repositoryService = new TestRepositoryService();

  @Before
  public void setUp() {
    repositoryService.transactionManager = transactionManager;
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void createRepositoryCommitsTransaction() throws RepositoryNameAlreadyUsedException {
    repositoryService.createRepository("repository", "description", new Locale(), false);

    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void createRepositoryCommitsTransactionOnCheckedException()
      throws RepositoryNameAlreadyUsedException {
    RepositoryNameAlreadyUsedException failure =
        new RepositoryNameAlreadyUsedException("repository exists");
    repositoryService.repositoryNameFailure = failure;

    assertThatThrownBy(
            () -> repositoryService.createRepository("repository", "description", null, false))
        .isSameAs(failure);

    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateRepositoryLocalesCommitsTransactionOnCheckedException()
      throws RepositoryLocaleCreationException {
    RepositoryLocaleCreationException failure =
        new RepositoryLocaleCreationException("invalid locale tree");
    repositoryService.repositoryLocaleFailure = failure;

    assertThatThrownBy(() -> repositoryService.updateRepositoryLocales(Set.of())).isSameAs(failure);

    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void deleteRepositoryRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("delete failed");
    repositoryService.runtimeFailure = failure;

    assertThatThrownBy(() -> repositoryService.deleteRepository(new Repository()))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateRepositoryRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("update failed");
    repositoryService.error = failure;

    assertThatThrownBy(
            () ->
                repositoryService.updateRepository(
                    new Repository(), "new-name", "description", false, Set.of(), Set.of()))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestRepositoryService extends RepositoryService {

    private RepositoryNameAlreadyUsedException repositoryNameFailure;
    private RepositoryLocaleCreationException repositoryLocaleFailure;
    private RuntimeException runtimeFailure;
    private Error error;

    @Override
    Repository createRepositoryNoTx(
        String name, String description, Locale sourceLocale, Boolean checkSLA, boolean hidden)
        throws RepositoryNameAlreadyUsedException {
      throwIfConfigured();
      if (repositoryNameFailure != null) {
        throw repositoryNameFailure;
      }
      return new Repository();
    }

    @Override
    Repository createRepositoryNoTx(
        String name,
        String description,
        Locale sourceLocale,
        Boolean checkSLA,
        Set<AssetIntegrityChecker> assetIntegrityCheckers,
        Set<RepositoryLocale> repositoryLocales)
        throws RepositoryLocaleCreationException, RepositoryNameAlreadyUsedException {
      throwIfConfigured();
      if (repositoryNameFailure != null) {
        throw repositoryNameFailure;
      }
      if (repositoryLocaleFailure != null) {
        throw repositoryLocaleFailure;
      }
      return new Repository();
    }

    @Override
    void updateAssetIntegrityCheckersNoTx(
        Repository repository, Set<AssetIntegrityChecker> assetIntegrityCheckers) {
      throwIfConfigured();
    }

    @Override
    void updateRepositoryLocalesNoTx(Repository repository, Set<RepositoryLocale> repositoryLocales)
        throws RepositoryLocaleCreationException {
      throwIfConfigured();
      if (repositoryLocaleFailure != null) {
        throw repositoryLocaleFailure;
      }
    }

    @Override
    RepositoryLocale addRootLocaleNoTx(Repository repository, Locale sourceLocale) {
      throwIfConfigured();
      return new RepositoryLocale();
    }

    @Override
    RepositoryLocale addRepositoryLocaleNoTx(
        Repository repository,
        String bcp47Tag,
        String parentLocaleBcp47Tag,
        boolean toBeFullyTranslated)
        throws RepositoryLocaleCreationException {
      throwIfConfigured();
      if (repositoryLocaleFailure != null) {
        throw repositoryLocaleFailure;
      }
      return new RepositoryLocale();
    }

    @Override
    void deleteRepositoryNoTx(Repository repository) {
      throwIfConfigured();
    }

    @Override
    void renameRepositoryNoTx(Repository repository, String newName)
        throws RepositoryNameAlreadyUsedException {
      throwIfConfigured();
      if (repositoryNameFailure != null) {
        throw repositoryNameFailure;
      }
    }

    @Override
    void updateRepositoryNoTx(
        Repository repository,
        String newName,
        String description,
        Boolean checkSLA,
        Set<RepositoryLocale> repositoryLocales,
        Set<AssetIntegrityChecker> assetIntegrityCheckers)
        throws RepositoryLocaleCreationException, RepositoryNameAlreadyUsedException {
      throwIfConfigured();
      if (repositoryNameFailure != null) {
        throw repositoryNameFailure;
      }
      if (repositoryLocaleFailure != null) {
        throw repositoryLocaleFailure;
      }
    }

    private void throwIfConfigured() {
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
      if (error != null) {
        throw error;
      }
    }
  }
}
