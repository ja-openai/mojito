package com.box.l10n.mojito.service.commit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Commit;
import com.box.l10n.mojito.entity.PullRun;
import com.box.l10n.mojito.entity.PushRun;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.rest.commit.CommitWithNameNotFoundException;
import com.box.l10n.mojito.rest.repository.RepositoryWithIdNotFoundException;
import com.box.l10n.mojito.service.pullrun.PullRunRepository;
import com.box.l10n.mojito.service.pullrun.PullRunWithNameNotFoundException;
import com.box.l10n.mojito.service.pushrun.PushRunRepository;
import com.box.l10n.mojito.service.pushrun.PushRunWithNameNotFoundException;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import java.time.ZonedDateTime;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class CommitServiceTransactionTest {

  @Test
  public void getOrCreateCommitCommitsTransaction() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestCommitService service = service(transactionManager);

    Commit result =
        service.getOrCreateCommit(
            new Repository(), "commit", "author@example.com", "Author", ZonedDateTime.now());

    assertSame(service.commit, result);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void getOrCreateCommitRollsBackCheckedException() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestCommitService service = service(transactionManager);
    service.saveFailure = new SaveCommitMismatchedExistingDataException("field", "old", "new");

    try {
      service.getOrCreateCommit(
          new Repository(), "commit", "author@example.com", "Author", ZonedDateTime.now());
    } catch (SaveCommitMismatchedExistingDataException e) {
      assertSame(service.saveFailure, e);
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected getOrCreateCommit to rethrow the checked failure");
  }

  @Test
  public void associateCommitToPushRunByNameCommitsOneTransaction() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestCommitService service = service(transactionManager);

    service.associateCommitToPushRun(1L, "commit", "push-run");

    assertEquals("commit", service.associatedCommitName);
    assertEquals("push-run", service.associatedPushRunName);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void associateCommitToPushRunByNameRollsBackCheckedException() throws Exception {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestCommitService service = service(transactionManager);
    service.commitNotFoundFailure = new CommitWithNameNotFoundException("commit");

    try {
      service.associateCommitToPushRun(1L, "commit", "push-run");
    } catch (CommitWithNameNotFoundException e) {
      assertSame(service.commitNotFoundFailure, e);
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected associateCommitToPushRun to rethrow the checked failure");
  }

  @Test
  public void associateCommitToPullRunRollsBackRuntimeException() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    TestCommitService service = service(transactionManager);
    service.runtimeFailure = new IllegalStateException("failed");

    try {
      service.associateCommitToPullRun(new Commit(), new PullRun());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected associateCommitToPullRun to rethrow the failure");
  }

  private TestCommitService service(PlatformTransactionManager transactionManager) {
    return new TestCommitService(transactionManager);
  }

  private static class TestCommitService extends CommitService {
    Commit commit = new Commit();
    SaveCommitMismatchedExistingDataException saveFailure;
    CommitWithNameNotFoundException commitNotFoundFailure;
    RuntimeException runtimeFailure;
    String associatedCommitName;
    String associatedPushRunName;

    TestCommitService(PlatformTransactionManager transactionManager) {
      super(
          mock(CommitRepository.class),
          mock(CommitToPushRunRepository.class),
          mock(CommitToPullRunRepository.class),
          mock(PushRunRepository.class),
          mock(PullRunRepository.class),
          mock(RepositoryRepository.class),
          transactionManager);
    }

    @Override
    Commit getOrCreateCommitNoTx(
        Repository repository,
        String commitName,
        String authorEmail,
        String authorName,
        ZonedDateTime sourceCreationDate)
        throws SaveCommitMismatchedExistingDataException {
      if (saveFailure != null) {
        throw saveFailure;
      }
      return commit;
    }

    @Override
    void associateCommitToPushRunNoTx(Long repositoryId, String commitName, String pushRunName)
        throws CommitWithNameNotFoundException,
            RepositoryWithIdNotFoundException,
            PushRunWithNameNotFoundException {
      if (commitNotFoundFailure != null) {
        throw commitNotFoundFailure;
      }
      associatedCommitName = commitName;
      associatedPushRunName = pushRunName;
    }

    @Override
    void associateCommitToPullRunNoTx(Commit commit, PullRun pullRun) {
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
    }

    @Override
    void associateCommitToPushRunNoTx(Commit commit, PushRun pushRun) {
      if (runtimeFailure != null) {
        throw runtimeFailure;
      }
    }

    @Override
    void associateCommitToPullRunNoTx(Long repositoryId, String commitName, String pullRunName)
        throws CommitWithNameNotFoundException, PullRunWithNameNotFoundException {
      if (commitNotFoundFailure != null) {
        throw commitNotFoundFailure;
      }
    }
  }
}
