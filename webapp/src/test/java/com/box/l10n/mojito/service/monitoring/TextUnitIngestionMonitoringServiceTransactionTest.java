package com.box.l10n.mojito.service.monitoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.monitoring.MonitoringTextUnitIngestionState;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class TextUnitIngestionMonitoringServiceTransactionTest {

  @Test
  public void recomputeMissingDaysCommitsTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    MonitoringTextUnitIngestionStateRepository stateRepository =
        mock(MonitoringTextUnitIngestionStateRepository.class);
    MonitoringTextUnitIngestionState state = new MonitoringTextUnitIngestionState();
    state.setId(TextUnitIngestionMonitoringService.STATE_ROW_ID);
    state.setLatestComputedDay(LocalDate.now(ZoneOffset.UTC));
    when(stateRepository.findById(TextUnitIngestionMonitoringService.STATE_ROW_ID))
        .thenReturn(Optional.of(state));
    TextUnitIngestionMonitoringService service = service(stateRepository, transactionManager);

    TextUnitIngestionMonitoringService.IngestionRecomputeResult result =
        service.recomputeMissingDays();

    assertEquals(state.getLatestComputedDay(), result.getLatestComputedDayBefore());
    verify(stateRepository).save(state);
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void recomputeMissingDaysRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    MonitoringTextUnitIngestionStateRepository stateRepository =
        mock(MonitoringTextUnitIngestionStateRepository.class);
    when(stateRepository.findById(TextUnitIngestionMonitoringService.STATE_ROW_ID))
        .thenThrow(new IllegalStateException("failed"));
    TextUnitIngestionMonitoringService service = service(stateRepository, transactionManager);

    try {
      service.recomputeMissingDays();
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected recomputeMissingDays to rethrow the state failure");
  }

  @Test
  public void getSnapshotCommitsReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    MonitoringTextUnitIngestionStateRepository stateRepository =
        mock(MonitoringTextUnitIngestionStateRepository.class);
    MonitoringTextUnitIngestionDailyRepository dailyRepository =
        mock(MonitoringTextUnitIngestionDailyRepository.class);
    MonitoringTextUnitIngestionState state = new MonitoringTextUnitIngestionState();
    state.setId(TextUnitIngestionMonitoringService.STATE_ROW_ID);
    state.setLatestComputedDay(LocalDate.of(2026, 5, 20));
    when(stateRepository.findById(TextUnitIngestionMonitoringService.STATE_ROW_ID))
        .thenReturn(Optional.of(state));
    when(dailyRepository.findAllOrdered()).thenReturn(List.of());
    TextUnitIngestionMonitoringService service =
        new TextUnitIngestionMonitoringService(
            mock(JdbcTemplate.class), dailyRepository, stateRepository, transactionManager);

    TextUnitIngestionMonitoringService.IngestionSnapshot snapshot =
        service.getSnapshot(
            TextUnitIngestionMonitoringService.IngestionGroupBy.DAY, false, null, null);

    assertEquals(LocalDate.of(2026, 5, 20), snapshot.getLatestComputedDay());
    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void getSnapshotRollsBackReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    MonitoringTextUnitIngestionStateRepository stateRepository =
        mock(MonitoringTextUnitIngestionStateRepository.class);
    when(stateRepository.findById(TextUnitIngestionMonitoringService.STATE_ROW_ID))
        .thenThrow(new IllegalStateException("failed"));
    TextUnitIngestionMonitoringService service = service(stateRepository, transactionManager);

    try {
      service.getSnapshot(
          TextUnitIngestionMonitoringService.IngestionGroupBy.DAY, false, null, null);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected getSnapshot to rethrow the state failure");
  }

  private TextUnitIngestionMonitoringService service(
      MonitoringTextUnitIngestionStateRepository stateRepository,
      PlatformTransactionManager transactionManager) {
    return new TextUnitIngestionMonitoringService(
        mock(JdbcTemplate.class),
        mock(MonitoringTextUnitIngestionDailyRepository.class),
        stateRepository,
        transactionManager);
  }
}
