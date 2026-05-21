package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRunner;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class GlossaryTermServiceTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestGlossaryTermService glossaryTermService =
      new TestGlossaryTermService(transactionManager);

  @Before
  public void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void searchTermsCommitsReadOnlyTransaction() {
    glossaryTermService.searchTerms(1L, "query", List.of("fr"), 25);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isTrue();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void upsertTermCommitsTransaction() {
    glossaryTermService.upsertTerm(1L, null, null);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isFalse();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void deleteTermRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("delete failed");
    glossaryTermService.failure = failure;

    assertThatThrownBy(() -> glossaryTermService.deleteTerm(1L, 2L)).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void batchUpdateTermsRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("batch failed");
    glossaryTermService.error = failure;

    assertThatThrownBy(() -> glossaryTermService.batchUpdateTerms(1L, null)).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestGlossaryTermService extends GlossaryTermService {

    private RuntimeException failure;
    private Error error;

    TestGlossaryTermService(PlatformTransactionManager transactionManager) {
      super(
          Mockito.mock(GlossaryRepository.class),
          Mockito.mock(GlossaryStorageService.class),
          Mockito.mock(GlossaryTermMetadataRepository.class),
          Mockito.mock(GlossaryTermEvidenceRepository.class),
          Mockito.mock(GlossaryTermIndexLinkRepository.class),
          Mockito.mock(GlossaryTermTranslationProposalRepository.class),
          Mockito.mock(TermIndexExtractedTermRepository.class),
          Mockito.mock(TermIndexCandidateRepository.class),
          Mockito.mock(GlossaryAiExtractionService.class),
          Mockito.mock(PollableTaskBlobStorage.class),
          Mockito.mock(PollableTaskRunner.class),
          Mockito.mock(TextUnitSearcher.class),
          Mockito.mock(VirtualAssetService.class),
          Mockito.mock(VirtualTextUnitBatchUpdaterService.class),
          Mockito.mock(TextUnitBatchImporterService.class),
          Mockito.mock(TMTextUnitRepository.class),
          Mockito.mock(com.box.l10n.mojito.service.repository.RepositoryRepository.class),
          Mockito.mock(LocaleService.class),
          Mockito.mock(UserService.class),
          transactionManager);
    }

    @Override
    SearchTermsView searchTermsNoTx(
        Long glossaryId,
        String searchQuery,
        SearchField searchField,
        List<String> localeTags,
        Integer limit) {
      throwIfConfigured();
      return new SearchTermsView(List.of(), 0, List.of());
    }

    @Override
    TermView getTermNoTx(Long glossaryId, Long tmTextUnitId, List<String> localeTags) {
      throwIfConfigured();
      return null;
    }

    @Override
    WorkspaceSummaryView getWorkspaceSummaryNoTx(Long glossaryId) {
      throwIfConfigured();
      return null;
    }

    @Override
    TermView upsertTermNoTx(Long glossaryId, Long tmTextUnitId, TermUpsertCommand command) {
      throwIfConfigured();
      return null;
    }

    @Override
    TermView appendTermEvidenceNoTx(
        Long glossaryId, Long tmTextUnitId, List<EvidenceInput> evidenceInputs) {
      throwIfConfigured();
      return null;
    }

    @Override
    void deleteTermNoTx(Long glossaryId, Long tmTextUnitId) {
      throwIfConfigured();
    }

    @Override
    BatchUpdateResult batchUpdateTermsNoTx(Long glossaryId, BatchUpdateCommand command) {
      throwIfConfigured();
      return null;
    }

    @Override
    ExtractionView extractCandidatesNoTx(Long glossaryId, ExtractionCommand command) {
      throwIfConfigured();
      return null;
    }

    @Override
    TranslationProposalView submitTranslationProposalNoTx(
        Long glossaryId, Long tmTextUnitId, TranslationProposalCommand command) {
      throwIfConfigured();
      return null;
    }

    @Override
    ProposalSearchView searchTranslationProposalsNoTx(
        Long glossaryId, String status, Integer limit) {
      throwIfConfigured();
      return null;
    }

    @Override
    TranslationProposalView decideTranslationProposalNoTx(
        Long glossaryId, Long proposalId, TranslationProposalDecisionCommand command) {
      throwIfConfigured();
      return null;
    }

    private void throwIfConfigured() {
      if (failure != null) {
        throw failure;
      }
      if (error != null) {
        throw error;
      }
    }
  }
}
