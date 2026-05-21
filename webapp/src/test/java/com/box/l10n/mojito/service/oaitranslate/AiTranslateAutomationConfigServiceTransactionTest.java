package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateAutomationConfigEntity;
import com.box.l10n.mojito.json.ObjectMapper;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class AiTranslateAutomationConfigServiceTransactionTest {

  @Test
  public void updateConfigCommitsTransaction() {
    AiTranslateAutomationConfigRepository repository =
        mock(AiTranslateAutomationConfigRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    AiTranslateAutomationConfigService service = service(repository, transactionManager);

    AiTranslateAutomationConfigService.Config config =
        service.updateConfig(
            new AiTranslateAutomationConfigService.Config(
                true, List.of(3L, 1L, 3L), 0, "0 0/5 * * * ?"));

    ArgumentCaptor<AiTranslateAutomationConfigEntity> entityCaptor =
        ArgumentCaptor.forClass(AiTranslateAutomationConfigEntity.class);
    verify(repository).save(entityCaptor.capture());
    assertEquals(true, entityCaptor.getValue().isEnabled());
    assertEquals("[1,3]", entityCaptor.getValue().getRepositoryIdsJson());
    assertEquals(1, entityCaptor.getValue().getSourceTextMaxCountPerLocale());
    assertEquals("0 0/5 * * * ?", entityCaptor.getValue().getCronExpression());
    assertEquals(List.of(1L, 3L), config.repositoryIds());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateConfigRollsBackTransaction() {
    AiTranslateAutomationConfigRepository repository =
        mock(AiTranslateAutomationConfigRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.save(any())).thenThrow(new IllegalStateException("failed"));
    AiTranslateAutomationConfigService service = service(repository, transactionManager);

    try {
      service.updateConfig(
          new AiTranslateAutomationConfigService.Config(true, List.of(1L), 1, null));
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updateConfig to rethrow the save failure");
  }

  private AiTranslateAutomationConfigService service(
      AiTranslateAutomationConfigRepository repository,
      PlatformTransactionManager transactionManager) {
    return new AiTranslateAutomationConfigService(
        repository, ObjectMapper.withNoFailOnUnknownProperties(), transactionManager);
  }
}
