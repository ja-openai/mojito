package com.box.l10n.mojito.service.blobstorage.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.MBlob;
import com.box.l10n.mojito.retry.DataIntegrityViolationExceptionRetryTemplate;
import com.box.l10n.mojito.service.blobstorage.Retention;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class DatabaseBlobStorageTransactionTest {

  @Test
  public void putBaseCommitsRequiresNewTransaction() {
    MBlobRepository repository = mock(MBlobRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findByName("name")).thenReturn(Optional.empty());

    DatabaseBlobStorage databaseBlobStorage = databaseBlobStorage(repository, transactionManager);
    databaseBlobStorage.putBase("name", "content".getBytes(), Retention.MIN_1_DAY);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertEquals(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        transactionDefinitionCaptor.getValue().getPropagationBehavior());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
    verify(repository).save(any(MBlob.class));
  }

  @Test
  public void putBaseRollsBackRequiresNewTransaction() {
    MBlobRepository repository = mock(MBlobRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findByName("name")).thenThrow(new IllegalStateException("failed"));

    DatabaseBlobStorage databaseBlobStorage = databaseBlobStorage(repository, transactionManager);

    try {
      databaseBlobStorage.putBase("name", "content".getBytes(), Retention.MIN_1_DAY);
      fail("Expected putBase to rethrow the repository failure");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
    }

    verify(transactionManager).rollback(transaction);
    verify(transactionManager, never()).commit(transaction);
  }

  private DatabaseBlobStorage databaseBlobStorage(
      MBlobRepository repository, PlatformTransactionManager transactionManager) {
    DatabaseBlobStorageConfigurationProperties properties =
        new DatabaseBlobStorageConfigurationProperties();
    return new DatabaseBlobStorage(
        properties,
        repository,
        new DataIntegrityViolationExceptionRetryTemplate(),
        transactionManager);
  }
}
