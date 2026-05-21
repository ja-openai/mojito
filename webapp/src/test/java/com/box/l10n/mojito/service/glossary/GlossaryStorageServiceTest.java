package com.box.l10n.mojito.service.glossary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryNameAlreadyUsedException;
import com.box.l10n.mojito.service.repository.RepositoryService;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryStorageServiceTest {

  @Mock RepositoryService repositoryService;
  @Mock AssetRepository assetRepository;
  @Mock VirtualAssetService virtualAssetService;
  @Mock LocaleService localeService;
  @Mock RepositoryLocaleRepository repositoryLocaleRepository;
  @Mock PlatformTransactionManager transactionManager;
  @Mock TransactionStatus transaction;

  GlossaryStorageService glossaryStorageService;

  com.box.l10n.mojito.entity.Locale defaultLocale;

  @Before
  public void setUp() {
    glossaryStorageService =
        new GlossaryStorageService(
            repositoryService,
            assetRepository,
            virtualAssetService,
            localeService,
            repositoryLocaleRepository,
            transactionManager);
    defaultLocale = new com.box.l10n.mojito.entity.Locale();
    defaultLocale.setBcp47Tag("en-US");
    when(localeService.getDefaultLocale()).thenReturn(defaultLocale);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
  }

  @Test
  public void createManagedBackingRepositoryUsesVisibleRepositoryCreation() throws Exception {
    Repository repository = new Repository();
    repository.setName("glossary-core-glossary");

    when(repositoryService.createRepository(anyString(), anyString(), any(), eq(false)))
        .thenReturn(repository);

    Repository created = glossaryStorageService.createManagedBackingRepository("Core Glossary");

    assertSame(repository, created);

    ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
    verify(repositoryService)
        .createRepository(
            nameCaptor.capture(),
            eq("Managed glossary backing repository for Core Glossary"),
            eq(defaultLocale),
            eq(false));
    verify(repositoryService, never())
        .createHiddenRepository(anyString(), anyString(), any(), any());

    assertTrue(nameCaptor.getValue().startsWith("glossary-"));
    assertFalse(nameCaptor.getValue().startsWith("__glossary__"));
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void createManagedBackingRepositoryRollsBackTransaction() throws Exception {
    when(repositoryService.createRepository(anyString(), anyString(), any(), eq(false)))
        .thenThrow(new RepositoryNameAlreadyUsedException("already used"));

    try {
      glossaryStorageService.createManagedBackingRepository("Core Glossary");
    } catch (IllegalStateException e) {
      assertEquals("Failed to create managed glossary backing repository", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected createManagedBackingRepository to rethrow the failure");
  }

  @Test
  public void replaceLocalesUpdatesBackingRepositoryLocalesFromExplicitSelection()
      throws Exception {
    Repository repository = new Repository();
    repository.setName("glossary-core-glossary");

    com.box.l10n.mojito.entity.Locale rootLocaleValue = new com.box.l10n.mojito.entity.Locale();
    rootLocaleValue.setBcp47Tag("en-US");
    RepositoryLocale rootLocale = new RepositoryLocale(repository, rootLocaleValue, false, null);
    repository.getRepositoryLocales().add(rootLocale);

    com.box.l10n.mojito.entity.Locale frLocale = new com.box.l10n.mojito.entity.Locale();
    frLocale.setBcp47Tag("fr-FR");
    com.box.l10n.mojito.entity.Locale deLocale = new com.box.l10n.mojito.entity.Locale();
    deLocale.setBcp47Tag("de-DE");

    Glossary glossary = new Glossary();
    glossary.setName("Core Glossary");
    glossary.setBackingRepository(repository);

    when(repositoryLocaleRepository.findByRepositoryAndParentLocaleIsNull(repository))
        .thenReturn(rootLocale);
    when(localeService.findByBcp47Tag("fr-FR")).thenReturn(frLocale);
    when(localeService.findByBcp47Tag("de-DE")).thenReturn(deLocale);
    when(localeService.findByBcp47Tag("en-US")).thenReturn(rootLocaleValue);

    glossaryStorageService.replaceLocales(glossary, List.of("fr-FR", "en-US", "de-DE", "fr-FR"));

    ArgumentCaptor<Set<RepositoryLocale>> localeCaptor = ArgumentCaptor.forClass(Set.class);
    verify(repositoryService).updateRepositoryLocales(eq(repository), localeCaptor.capture());
    verify(repositoryLocaleRepository, never())
        .deleteByRepositoryAndParentLocaleIsNotNull(repository);

    Set<RepositoryLocale> replacedLocales = localeCaptor.getValue();
    assertTrue(
        replacedLocales.stream()
            .map(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
            .allMatch(tag -> tag.equals("fr-FR") || tag.equals("de-DE")));
    assertTrue(
        replacedLocales.stream()
            .allMatch(
                repositoryLocale ->
                    repositoryLocale.getRepository() == repository
                        && repositoryLocale.getParentLocale() == null));
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void replaceLocalesClearsBackingRepositoryLocalesWhenSelectionIsEmpty() throws Exception {
    Repository repository = new Repository();
    repository.setName("glossary-core-glossary");

    com.box.l10n.mojito.entity.Locale rootLocaleValue = new com.box.l10n.mojito.entity.Locale();
    rootLocaleValue.setBcp47Tag("en-US");
    RepositoryLocale rootLocale = new RepositoryLocale(repository, rootLocaleValue, false, null);
    RepositoryLocale oldTargetLocale =
        new RepositoryLocale(repository, new com.box.l10n.mojito.entity.Locale(), true, rootLocale);
    repository.getRepositoryLocales().add(rootLocale);
    repository.getRepositoryLocales().add(oldTargetLocale);

    Glossary glossary = new Glossary();
    glossary.setName("Core Glossary");
    glossary.setBackingRepository(repository);

    when(repositoryLocaleRepository.findByRepositoryAndParentLocaleIsNull(repository))
        .thenReturn(rootLocale);

    glossaryStorageService.replaceLocales(glossary, List.of());

    verify(repositoryLocaleRepository).deleteByRepositoryAndParentLocaleIsNotNull(repository);
    verify(repositoryService, never()).updateRepositoryLocales(eq(repository), any());
    assertEquals(1, repository.getRepositoryLocales().size());
    assertTrue(repository.getRepositoryLocales().contains(rootLocale));
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void renameManagedBackingRepositoryOnlyRenamesRepository() throws Exception {
    Repository repository = new Repository();
    repository.setName("glossary-old");

    Glossary glossary = new Glossary();
    glossary.setName("Core Glossary");
    glossary.setBackingRepository(repository);

    glossaryStorageService.renameManagedBackingRepository(glossary, " glossary-new ");

    verify(repositoryService).renameRepository(repository, "glossary-new");
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void renameManagedBackingRepositoryRollsBackTransaction() {
    Glossary glossary = new Glossary();
    glossary.setName("Core Glossary");

    try {
      glossaryStorageService.renameManagedBackingRepository(glossary, "glossary-new");
    } catch (IllegalStateException e) {
      assertEquals("Backing repository is missing for glossary Core Glossary", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected renameManagedBackingRepository to rethrow the failure");
  }
}
