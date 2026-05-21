package com.box.l10n.mojito.service.glossary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class TermIndexExplorerServiceTransactionTest {

  @Test
  public void getStatusCommitsReadOnlyTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    UserService userService = mock(UserService.class);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    TermIndexOccurrenceRepository occurrenceRepository = mock(TermIndexOccurrenceRepository.class);
    when(occurrenceRepository.findDistinctExtractionMethods()).thenReturn(List.of("AI"));
    TermIndexExplorerService service =
        service(
            userService,
            transactionManager,
            occurrenceRepository,
            mock(TermIndexRepositoryCursorRepository.class));

    TermIndexExplorerService.StatusView status = service.getStatus(List.of(), null);

    assertEquals(List.of("AI"), status.extractionMethods());
    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertTrue(transactionDefinitionCaptor.getValue().isReadOnly());
    verify(transactionManager).commit(transaction);
    verify(transactionManager, never()).rollback(transaction);
  }

  @Test
  public void getStatusRollsBackTransaction() {
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    TransactionStatus transaction = mock(TransactionStatus.class);
    when(transactionManager.getTransaction(any())).thenReturn(transaction);
    UserService userService = mock(UserService.class);
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    TermIndexOccurrenceRepository occurrenceRepository = mock(TermIndexOccurrenceRepository.class);
    TermIndexRepositoryCursorRepository cursorRepository =
        mock(TermIndexRepositoryCursorRepository.class);
    when(cursorRepository.findForExplorer(eq(true), eq(List.of(-1L))))
        .thenThrow(new IllegalStateException("failed"));
    TermIndexExplorerService service =
        service(userService, transactionManager, occurrenceRepository, cursorRepository);

    try {
      service.getStatus(List.of(), null);
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(transactionManager).rollback(transaction);
      verify(transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected getStatus to rethrow the repository failure");
  }

  private TermIndexExplorerService service(
      UserService userService,
      PlatformTransactionManager transactionManager,
      TermIndexOccurrenceRepository occurrenceRepository,
      TermIndexRepositoryCursorRepository cursorRepository) {
    return new TermIndexExplorerService(
        userService,
        mock(TermIndexExtractedTermRepository.class),
        mock(TermIndexCandidateRepository.class),
        occurrenceRepository,
        cursorRepository,
        mock(TermIndexRefreshRunRepository.class),
        mock(TermIndexAutomationRunRepository.class),
        transactionManager);
  }
}
