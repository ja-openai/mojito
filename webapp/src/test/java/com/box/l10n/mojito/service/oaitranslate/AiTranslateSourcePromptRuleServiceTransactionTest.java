package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateSourcePromptRuleEntity;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class AiTranslateSourcePromptRuleServiceTransactionTest {

  @Test
  public void getAllUsesReadOnlyTransaction() {
    AiTranslateSourcePromptRuleRepository repository =
        mock(AiTranslateSourcePromptRuleRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findAllByOrderByPriorityAscNameAsc())
        .thenReturn(List.of(entity("Slots", "\\[[^\\]]+\\]", "Preserve slots.")));
    AiTranslateSourcePromptRuleService service = service(repository, transactionManager);

    List<AiTranslateSourcePromptRuleService.SourcePromptRule> rules = service.getAll();

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    assertEquals("Slots", rules.get(0).name());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void upsertCommitsTransaction() {
    AiTranslateSourcePromptRuleRepository repository =
        mock(AiTranslateSourcePromptRuleRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AiTranslateSourcePromptRuleService service = service(repository, transactionManager);

    AiTranslateSourcePromptRuleService.SourcePromptRule rule =
        service.upsert(
            new AiTranslateSourcePromptRuleService.SourcePromptRuleInput(
                null, "Slots", null, true, 0, "REGEX", "\\[[^\\]]+\\]", "Preserve slots."));

    assertEquals("Slots", rule.name());
    verify(repository).save(any());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteRollsBackTransaction() {
    AiTranslateSourcePromptRuleRepository repository =
        mock(AiTranslateSourcePromptRuleRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findById(1L)).thenThrow(new IllegalStateException("failed"));
    AiTranslateSourcePromptRuleService service = service(repository, transactionManager);

    try {
      service.delete(1L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected delete to rethrow the repository failure");
  }

  @Test
  public void deleteCommitsTransaction() {
    AiTranslateSourcePromptRuleRepository repository =
        mock(AiTranslateSourcePromptRuleRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    AiTranslateSourcePromptRuleEntity entity = entity("Slots", "\\[[^\\]]+\\]", "Preserve slots.");
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findById(1L)).thenReturn(Optional.of(entity));
    AiTranslateSourcePromptRuleService service = service(repository, transactionManager);

    service.delete(1L);

    verify(repository).delete(entity);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  private AiTranslateSourcePromptRuleService service(
      AiTranslateSourcePromptRuleRepository repository,
      PlatformTransactionManager transactionManager) {
    return new AiTranslateSourcePromptRuleService(repository, transactionManager);
  }

  private AiTranslateSourcePromptRuleEntity entity(
      String name, String sourceRegex, String promptSuffix) {
    AiTranslateSourcePromptRuleEntity entity = new AiTranslateSourcePromptRuleEntity();
    entity.setName(name);
    entity.setEnabled(true);
    entity.setPriority(0);
    entity.setMatchType(AiTranslateSourcePromptRuleService.MATCH_TYPE_REGEX);
    entity.setSourceRegex(sourceRegex);
    entity.setPromptSuffix(promptSuffix);
    return entity;
  }
}
