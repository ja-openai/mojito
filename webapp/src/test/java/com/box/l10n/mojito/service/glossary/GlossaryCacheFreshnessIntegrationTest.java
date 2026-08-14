package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.oaitranslate.GlossaryService;
import com.box.l10n.mojito.service.repository.RepositoryService;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class GlossaryCacheFreshnessIntegrationTest extends ServiceTestBase {

  @Autowired GlossaryRepository glossaryRepository;
  @Autowired GlossaryManagementService glossaryManagementService;
  @Autowired GlossaryTermService glossaryTermService;
  @Autowired GlossaryService glossaryService;
  @Autowired RepositoryService repositoryService;
  @Autowired PlatformTransactionManager transactionManager;

  @Test
  public void repeatedEditsWithinOneSecondAdvancePersistedGlossaryVersion() throws Exception {
    String name = "glossary-cache-" + UUID.randomUUID();
    Repository backingRepository = repositoryService.createRepository(name);

    Glossary glossary = new Glossary();
    glossary.setName(name);
    glossary.setBackingRepository(backingRepository);
    glossary = glossaryRepository.saveAndFlush(glossary);

    Long glossaryId = glossary.getId();
    ZonedDateTime sameDatabaseTick =
        glossary.getLastModifiedDate().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

    transactionTemplate.executeWithoutResult(
        ignored -> glossaryRepository.advanceLastModifiedDate(glossaryId, sameDatabaseTick));
    ZonedDateTime firstVersion =
        glossaryRepository.findById(glossaryId).orElseThrow().getLastModifiedDate();

    transactionTemplate.executeWithoutResult(
        ignored -> glossaryRepository.advanceLastModifiedDate(glossaryId, sameDatabaseTick));
    ZonedDateTime secondVersion =
        glossaryRepository.findById(glossaryId).orElseThrow().getLastModifiedDate();

    assertThat(firstVersion.toInstant()).isEqualTo(sameDatabaseTick.toInstant());
    assertThat(secondVersion.toInstant()).isEqualTo(firstVersion.plusSeconds(1).toInstant());
  }

  @Test
  public void managedTermEditsImmediatelyReplaceCachedGlossaryTrie() {
    String name = "managed-glossary-cache-" + UUID.randomUUID();
    GlossaryManagementService.GlossaryDetail glossary =
        glossaryManagementService.createGlossary(
            name,
            null,
            true,
            0,
            Glossary.SCOPE_MODE_GLOBAL,
            List.of("fr-FR"),
            List.of(),
            List.of());

    glossaryTermService.upsertTerm(glossary.id(), null, approvedTerm("settings", "Settings"));
    GlossaryService.GlossaryTrie initialTrie =
        glossaryService.loadGlossaryTrieForLocale(name, "fr-FR");

    glossaryTermService.upsertTerm(glossary.id(), null, approvedTerm("workspace", "Workspace"));
    GlossaryService.GlossaryTrie refreshedTrie =
        glossaryService.loadGlossaryTrieForLocale(name, "fr-FR");

    assertThat(refreshedTrie).isNotSameAs(initialTrie);
    assertThat(initialTrie.findMatches("Workspace")).isEmpty();
    assertThat(refreshedTrie.findMatches("Workspace")).hasSize(1);
  }

  private GlossaryTermService.TermUpsertCommand approvedTerm(String termKey, String source) {
    return new GlossaryTermService.TermUpsertCommand(
        termKey,
        source,
        null,
        null,
        null,
        null,
        null,
        GlossaryTermMetadata.STATUS_APPROVED,
        null,
        false,
        false,
        false,
        false,
        null,
        List.of(),
        List.of());
  }
}
