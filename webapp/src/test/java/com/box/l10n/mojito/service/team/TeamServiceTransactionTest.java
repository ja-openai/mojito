package com.box.l10n.mojito.service.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Team;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskRunner;
import com.box.l10n.mojito.service.review.ReviewProjectAssignmentHistoryRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.security.user.UserLocaleRepository;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.slack.SlackClients;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class TeamServiceTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestTeamService teamService = new TestTeamService(transactionManager);

  @Before
  public void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void findAllCommitsReadOnlyTransaction() {
    teamService.findAll();

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isTrue();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void createTeamCommitsTransaction() {
    teamService.createTeam("Team");

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isFalse();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void getLocalePoolsCommitsReadOnlyTransaction() {
    teamService.getLocalePools(1L);

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isTrue();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void replacePmPoolCommitsTransaction() {
    teamService.replacePmPool(1L, List.of(2L));

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isFalse();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void deleteTeamRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("delete failed");
    teamService.failure = failure;

    assertThatThrownBy(() -> teamService.deleteTeam(1L)).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void setUserTeamAssignmentsRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("assignment failed");
    teamService.error = failure;

    assertThatThrownBy(() -> teamService.setUserTeamAssignments(1L, 2L, 3L)).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void replaceLocalePoolsRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("locale pool failed");
    teamService.failure = failure;

    assertThatThrownBy(() -> teamService.replaceLocalePools(1L, List.of())).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestTeamService extends TeamService {

    private RuntimeException failure;
    private Error error;

    TestTeamService(PlatformTransactionManager transactionManager) {
      super(
          Mockito.mock(TeamRepository.class),
          Mockito.mock(TeamUserRepository.class),
          Mockito.mock(TeamLocalePoolRepository.class),
          Mockito.mock(TeamPmPoolRepository.class),
          Mockito.mock(TeamSlackUserMappingRepository.class),
          Mockito.mock(UserRepository.class),
          Mockito.mock(UserLocaleRepository.class),
          Mockito.mock(LocaleService.class),
          Mockito.mock(ReviewProjectRepository.class),
          Mockito.mock(ReviewProjectAssignmentHistoryRepository.class),
          Mockito.mock(com.box.l10n.mojito.security.AuditorAwareImpl.class),
          Mockito.mock(SlackClients.class),
          Mockito.mock(PollableTaskBlobStorage.class),
          Mockito.mock(PollableTaskRunner.class),
          transactionManager);
    }

    @Override
    List<Team> findAllNoTx() {
      throwIfConfigured();
      return List.of();
    }

    @Override
    Team createTeamNoTx(String rawName) {
      throwIfConfigured();
      return new Team();
    }

    @Override
    void deleteTeamNoTx(Long teamId) {
      throwIfConfigured();
    }

    @Override
    void setUserTeamAssignmentsNoTx(Long userId, Long pmTeamId, Long translatorTeamId) {
      throwIfConfigured();
    }

    @Override
    List<LocalePoolEntry> getLocalePoolsNoTx(Long teamId) {
      throwIfConfigured();
      return List.of();
    }

    @Override
    void replacePmPoolNoTx(Long teamId, List<Long> userIds) {
      throwIfConfigured();
    }

    @Override
    void replaceLocalePoolsNoTx(Long teamId, List<LocalePoolEntry> entries) {
      throwIfConfigured();
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
