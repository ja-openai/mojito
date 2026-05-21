package com.box.l10n.mojito.service.glossary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.quartz.QuartzPollableTaskScheduler;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class GlossaryTermIndexCurationServiceTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestGlossaryTermIndexCurationService curationService =
      new TestGlossaryTermIndexCurationService(transactionManager);

  @Before
  public void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void searchSuggestionsCommitsReadOnlyTransaction() {
    curationService.searchSuggestions(1L, null);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isTrue();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void acceptSuggestionCommitsTransaction() {
    curationService.acceptSuggestion(1L, 2L, null);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isFalse();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void ignoreSuggestionRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("ignore failed");
    curationService.failure = failure;

    assertThatThrownBy(() -> curationService.ignoreSuggestion(1L, 2L, null)).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void updateCandidateReviewRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("review failed");
    curationService.error = failure;

    assertThatThrownBy(() -> curationService.updateCandidateReview(2L, null)).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void seedTermsCommitsTransaction() {
    curationService.seedTerms(null);

    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void exportCandidatesCommitsReadOnlyTransaction() {
    curationService.exportCandidatesForGlossary(1L, null);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isTrue();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void linkGlossaryTermsRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("link failed");
    curationService.failure = failure;

    assertThatThrownBy(() -> curationService.linkGlossaryTermsToCandidates(1L, null))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestGlossaryTermIndexCurationService
      extends GlossaryTermIndexCurationService {

    private RuntimeException failure;
    private Error error;

    TestGlossaryTermIndexCurationService(PlatformTransactionManager transactionManager) {
      super(
          Mockito.mock(GlossaryRepository.class),
          Mockito.mock(com.box.l10n.mojito.service.repository.RepositoryRepository.class),
          Mockito.mock(GlossaryTermMetadataRepository.class),
          Mockito.mock(GlossaryTermIndexLinkRepository.class),
          Mockito.mock(GlossaryTermIndexDecisionRepository.class),
          Mockito.mock(TermIndexExtractedTermRepository.class),
          Mockito.mock(TermIndexOccurrenceRepository.class),
          Mockito.mock(TermIndexCandidateRepository.class),
          Mockito.mock(TermIndexAutomationRunRepository.class),
          Mockito.mock(GlossaryTermService.class),
          Mockito.mock(GlossaryAiExtractionService.class),
          Mockito.mock(TextUnitSearcher.class),
          Mockito.mock(UserService.class),
          ObjectMapper.withNoFailOnUnknownProperties(),
          Mockito.mock(QuartzPollableTaskScheduler.class),
          Mockito.mock(PollableTaskService.class),
          transactionManager);
    }

    @Override
    SuggestionSearchView searchSuggestionsNoTx(Long glossaryId, SuggestionSearchCommand command) {
      throwIfConfigured();
      return new SuggestionSearchView(List.of(), 0);
    }

    @Override
    GlossaryTermService.TermView acceptSuggestionNoTx(
        Long glossaryId, Long termIndexCandidateId, AcceptSuggestionCommand command) {
      throwIfConfigured();
      return null;
    }

    @Override
    void ignoreSuggestionNoTx(
        Long glossaryId, Long termIndexCandidateId, IgnoreSuggestionCommand command) {
      throwIfConfigured();
    }

    @Override
    CandidateReviewView updateCandidateReviewNoTx(
        Long termIndexCandidateId, CandidateReviewCommand command) {
      throwIfConfigured();
      return null;
    }

    @Override
    SeedResult seedTermsNoTx(SeedCommand command) {
      throwIfConfigured();
      return new SeedResult(0, 0, 0, List.of());
    }

    @Override
    SeedResult seedTermsForGlossaryNoTx(Long glossaryId, SeedCommand command) {
      throwIfConfigured();
      return new SeedResult(0, 0, 0, List.of());
    }

    @Override
    GenerateCandidatesResult generateCandidatesForGlossaryNoTx(
        Long glossaryId, GenerateCandidatesCommand command) {
      throwIfConfigured();
      return new GenerateCandidatesResult(0, 0, 0, 0, List.of());
    }

    @Override
    GenerateCandidatesResult generateCandidatesForGlossaryInternalNoTx(
        Long glossaryId, GenerateCandidatesCommand command) {
      throwIfConfigured();
      return new GenerateCandidatesResult(0, 0, 0, 0, List.of());
    }

    @Override
    SeedResult importCandidatesForGlossaryNoTx(Long glossaryId, CandidateImportCommand command) {
      throwIfConfigured();
      return new SeedResult(0, 0, 0, List.of());
    }

    @Override
    CandidateExportResult exportCandidatesForGlossaryNoTx(
        Long glossaryId, CandidateExportCommand command) {
      throwIfConfigured();
      return new CandidateExportResult("json", "candidates.json", "{}", 0);
    }

    @Override
    LinkGlossaryTermsToCandidatesResult linkGlossaryTermsToCandidatesNoTx(
        Long glossaryId, LinkGlossaryTermsToCandidatesCommand command) {
      throwIfConfigured();
      return new LinkGlossaryTermsToCandidatesResult(
          glossaryId, null, false, null, 0, 0, 0, 0, 0, 0, 0, List.of());
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
