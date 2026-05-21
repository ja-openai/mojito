package com.box.l10n.mojito.service.sla;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.SlaIncident;
import com.box.l10n.mojito.utils.DateTimeUtils;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class SlaCheckerServiceTransactionTest {

  @Test
  public void closeIncidentsCommitsTransaction() {
    SlaCheckerService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    ZonedDateTime closedDate = ZonedDateTime.parse("2026-05-21T20:12:05Z");
    SlaIncident incident = new SlaIncident();
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.dateTimeUtils.now()).thenReturn(closedDate);
    when(service.slaIncidentRepository.findByClosedDateIsNull()).thenReturn(List.of(incident));

    service.closeIncidents();

    assertEquals(closedDate, incident.getClosedDate());
    verify(service.slaIncidentRepository).saveAll(List.of(incident));
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void closeIncidentsRollsBackTransaction() {
    SlaCheckerService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.dateTimeUtils.now()).thenReturn(ZonedDateTime.parse("2026-05-21T20:12:05Z"));
    when(service.slaIncidentRepository.findByClosedDateIsNull())
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.closeIncidents();
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected closeIncidents to rethrow the repository failure");
  }

  private SlaCheckerService service() {
    SlaCheckerService service = new SlaCheckerService();
    service.slaIncidentRepository = mock(SlaIncidentRepository.class);
    service.dateTimeUtils = mock(DateTimeUtils.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }
}
