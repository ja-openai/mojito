package com.box.l10n.mojito.service.glossary;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryManagementServiceTest {

  @Mock GlossaryRepository glossaryRepository;
  @Mock com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  @Mock GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  @Mock GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  @Mock GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository;
  @Mock GlossaryStorageService glossaryStorageService;
  @Mock UserService userService;
  @Mock PlatformTransactionManager transactionManager;
  @Mock TransactionStatus transaction;

  GlossaryManagementService glossaryManagementService;

  @Before
  public void setUp() {
    glossaryManagementService =
        new GlossaryManagementService(
            glossaryRepository,
            repositoryRepository,
            glossaryTermMetadataRepository,
            glossaryTermEvidenceRepository,
            glossaryTermTranslationProposalRepository,
            glossaryStorageService,
            userService,
            transactionManager);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
  }

  @Test
  public void deleteGlossarySoftDeletesBackingRepository() {
    Repository backingRepository = new Repository();
    backingRepository.setName("glossary-core");

    Glossary glossary = new Glossary();
    glossary.setId(1L);
    glossary.setBackingRepository(backingRepository);

    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(1L)).thenReturn(Optional.of(glossary));

    glossaryManagementService.deleteGlossary(1L);

    verify(glossaryTermTranslationProposalRepository).deleteByGlossaryId(1L);
    verify(glossaryTermEvidenceRepository).deleteByGlossaryId(1L);
    verify(glossaryTermMetadataRepository).deleteByGlossaryId(1L);
    verify(glossaryStorageService).deleteManagedBackingRepository(glossary);
    verify(glossaryRepository).delete(glossary);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void deleteGlossaryRollsBackTransaction() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(1L))
        .thenThrow(new IllegalStateException("failed"));

    try {
      glossaryManagementService.deleteGlossary(1L);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected deleteGlossary to rethrow the repository failure");
  }

  @Test
  public void updateGlossaryRenamesBackingRepositoryWhenRequested() {
    Repository backingRepository = new Repository();
    backingRepository.setName("glossary-old");

    Glossary glossary = new Glossary();
    glossary.setId(1L);
    glossary.setName("Old glossary");
    glossary.setBackingRepository(backingRepository);

    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(userService.isCurrentUserTranslationRole()).thenReturn(true);
    when(glossaryRepository.findByIdWithBindings(1L)).thenReturn(Optional.of(glossary));
    when(glossaryRepository.findByNameIgnoreCase("New glossary")).thenReturn(Optional.empty());

    glossaryManagementService.updateGlossary(
        1L,
        "New glossary",
        null,
        true,
        0,
        Glossary.SCOPE_MODE_GLOBAL,
        List.of(),
        List.of(),
        List.of(),
        "glossary-new");

    verify(glossaryStorageService).renameManagedBackingRepository(glossary, "glossary-new");
    verify(glossaryStorageService, never()).replaceLocales(glossary, List.of());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }
}
