package com.box.l10n.mojito.rest.textunit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

public class TextUnitWSTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestTextUnitWS textUnitWS = new TestTextUnitWS();

  @Before
  public void setUp() {
    textUnitWS.transactionManager = transactionManager;
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void addTextUnitCommitsTransaction() {
    textUnitWS.addTextUnit(new TextUnitDTO());

    ArgumentCaptor<TransactionDefinition> transactionDefinitionCaptor =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(transactionDefinitionCaptor.capture());
    assertThat(transactionDefinitionCaptor.getValue().isReadOnly()).isFalse();
    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void addTextUnitRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("add text unit failed");
    textUnitWS.failure = failure;

    assertThatThrownBy(() -> textUnitWS.addTextUnit(new TextUnitDTO())).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void addTextUnitRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("add text unit error");
    textUnitWS.error = failure;

    assertThatThrownBy(() -> textUnitWS.addTextUnit(new TextUnitDTO())).isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestTextUnitWS extends TextUnitWS {

    private RuntimeException failure;
    private Error error;

    @Override
    TextUnitDTO addTextUnitNoTx(TextUnitDTO textUnitDTO) {
      if (failure != null) {
        throw failure;
      }
      if (error != null) {
        throw error;
      }
      return textUnitDTO;
    }
  }
}
