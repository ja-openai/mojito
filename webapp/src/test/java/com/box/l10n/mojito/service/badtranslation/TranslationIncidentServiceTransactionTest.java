package com.box.l10n.mojito.service.badtranslation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.utils.ServerConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class TranslationIncidentServiceTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestTranslationIncidentService translationIncidentService =
      new TestTranslationIncidentService(transactionManager);

  @Before
  public void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void getIncidentsCommitsReadOnlyTransaction() {
    TranslationIncidentService.IncidentPage result =
        translationIncidentService.getIncidents(
            TranslationIncidentStatus.OPEN, "query", LocalDate.now(), LocalDate.now(), 0, 10);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isTrue();
    assertThat(result).isSameAs(translationIncidentService.incidentPage);
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void createIncidentCommitsTransaction() {
    translationIncidentService.createIncident(
        new TranslationIncidentService.CreateIncidentRequest(
            "string.id", "fr-FR", "repo", "bad translation", null));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isFalse();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void rejectIncidentRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("reject failed");
    translationIncidentService.failure = failure;

    assertThatThrownBy(
            () ->
                translationIncidentService.rejectIncident(
                    17L, new TranslationIncidentService.RejectIncidentRequest("operator note")))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void sendSlackDraftCommitsTransaction() {
    translationIncidentService.sendSlackDraft(17L);

    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateStatusRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("status failed");
    translationIncidentService.error = failure;

    assertThatThrownBy(
            () ->
                translationIncidentService.updateStatus(
                    17L,
                    new TranslationIncidentService.UpdateStatusRequest(
                        TranslationIncidentStatus.CLOSED)))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestTranslationIncidentService extends TranslationIncidentService {

    private final IncidentPage incidentPage =
        new IncidentPage(List.of(), 0, 10, 0, 0, false, false);
    private RuntimeException failure;
    private Error error;

    TestTranslationIncidentService(PlatformTransactionManager transactionManager) {
      super(
          Mockito.mock(TranslationIncidentRepository.class),
          Mockito.mock(BadTranslationLookupService.class),
          Mockito.mock(BadTranslationReviewProjectService.class),
          Mockito.mock(BadTranslationSlackService.class),
          Mockito.mock(BadTranslationSlackMessageComposer.class),
          Mockito.mock(BadTranslationMutationService.class),
          Mockito.mock(TMTextUnitVariantRepository.class),
          Mockito.mock(UserService.class),
          Mockito.mock(AuditorAwareImpl.class),
          Mockito.mock(ServerConfig.class),
          ObjectMapper.withNoFailOnUnknownProperties(),
          transactionManager);
    }

    @Override
    IncidentPage getIncidentsNoTx(
        TranslationIncidentStatus status,
        String query,
        LocalDate createdAfter,
        LocalDate createdBefore,
        int page,
        int size) {
      throwIfConfigured();
      return incidentPage;
    }

    @Override
    IncidentDetail getIncidentNoTx(Long incidentId) {
      throwIfConfigured();
      return null;
    }

    @Override
    IncidentDetail createIncidentNoTx(CreateIncidentRequest request) {
      throwIfConfigured();
      return null;
    }

    @Override
    IncidentDetail rejectIncidentNoTx(Long incidentId, RejectIncidentRequest request) {
      throwIfConfigured();
      return null;
    }

    @Override
    IncidentDetail sendSlackDraftNoTx(Long incidentId) {
      throwIfConfigured();
      return null;
    }

    @Override
    IncidentDetail updateStatusNoTx(Long incidentId, UpdateStatusRequest request) {
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
