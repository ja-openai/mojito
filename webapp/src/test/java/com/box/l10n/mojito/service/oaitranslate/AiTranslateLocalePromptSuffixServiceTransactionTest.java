package com.box.l10n.mojito.service.oaitranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.AiTranslateLocalePromptSuffixEntity;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class AiTranslateLocalePromptSuffixServiceTransactionTest {

  @Test
  public void getAllUsesReadOnlyTransaction() {
    AiTranslateLocalePromptSuffixRepository repository =
        mock(AiTranslateLocalePromptSuffixRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findAllByOrderByLocaleBcp47TagAsc())
        .thenReturn(List.of(entity("fr-FR", "Use Canadian French tone.")));
    AiTranslateLocalePromptSuffixService service =
        service(repository, mock(LocaleService.class), transactionManager);

    List<AiTranslateLocalePromptSuffixService.LocalePromptSuffix> promptSuffixes = service.getAll();

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    assertEquals("fr-FR", promptSuffixes.get(0).localeTag());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void upsertCommitsTransaction() {
    AiTranslateLocalePromptSuffixRepository repository =
        mock(AiTranslateLocalePromptSuffixRepository.class);
    LocaleService localeService = mock(LocaleService.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(localeService.findByBcp47Tag("fr-FR")).thenReturn(locale("fr-FR"));
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AiTranslateLocalePromptSuffixService service =
        service(repository, localeService, transactionManager);

    AiTranslateLocalePromptSuffixService.LocalePromptSuffix promptSuffix =
        service.upsert("fr-FR", "Use Canadian French tone.");

    assertEquals("fr-FR", promptSuffix.localeTag());
    assertEquals("Use Canadian French tone.", promptSuffix.promptSuffix());
    verify(repository).save(any());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteRollsBackTransaction() {
    AiTranslateLocalePromptSuffixRepository repository =
        mock(AiTranslateLocalePromptSuffixRepository.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    when(repository.findByLocaleBcp47TagIgnoreCase("fr-FR"))
        .thenThrow(new IllegalStateException("failed"));
    AiTranslateLocalePromptSuffixService service =
        service(repository, mock(LocaleService.class), transactionManager);

    try {
      service.delete("fr-FR");
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected delete to rethrow the repository failure");
  }

  private AiTranslateLocalePromptSuffixService service(
      AiTranslateLocalePromptSuffixRepository repository,
      LocaleService localeService,
      PlatformTransactionManager transactionManager) {
    return new AiTranslateLocalePromptSuffixService(repository, localeService, transactionManager);
  }

  private AiTranslateLocalePromptSuffixEntity entity(String localeTag, String promptSuffix) {
    AiTranslateLocalePromptSuffixEntity entity = new AiTranslateLocalePromptSuffixEntity();
    entity.setLocale(locale(localeTag));
    entity.setPromptSuffix(promptSuffix);
    return entity;
  }

  private Locale locale(String localeTag) {
    Locale locale = new Locale();
    locale.setBcp47Tag(localeTag);
    return locale;
  }
}
