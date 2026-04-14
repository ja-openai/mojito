package com.box.l10n.mojito.service.glossary;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GlossaryManagementServiceTest {

  @Mock GlossaryRepository glossaryRepository;
  @Mock com.box.l10n.mojito.service.repository.RepositoryRepository repositoryRepository;
  @Mock GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  @Mock GlossaryTermEvidenceRepository glossaryTermEvidenceRepository;
  @Mock GlossaryTermTranslationProposalRepository glossaryTermTranslationProposalRepository;
  @Mock GlossaryStorageService glossaryStorageService;
  @Mock UserService userService;

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
            userService);
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
  }
}
