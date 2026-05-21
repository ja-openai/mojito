package com.box.l10n.mojito.service.branch;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.BranchStatistic;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class BranchStatisticServiceTransactionTest {

  @Test
  public void updateBranchStatisticInTxCommitsTransaction() {
    BranchStatisticService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    Branch branch = branch();
    BranchStatistic branchStatistic = branchStatistic(branch);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.branchStatisticRepository.findByBranch(branch)).thenReturn(branchStatistic);
    when(service.branchStatisticRepository.save(branchStatistic)).thenReturn(branchStatistic);
    when(service.branchTextUnitStatisticRepository.findTmTextUnitIds(20L)).thenReturn(List.of());

    service.updateBranchStatisticInTx(branch, ImmutableMap.of());

    verify(service.branchStatisticRepository).save(branchStatistic);
    verify(service.branchTextUnitStatisticRepository)
        .deleteByBranchStatisticBranchIdAndTmTextUnitIdIn(10L, Set.of());
    verify(service.transactionManager).commit(transaction);
    verify(service.transactionManager, never()).rollback(transaction);
  }

  @Test
  public void updateBranchStatisticInTxRollsBackTransaction() {
    BranchStatisticService service = service();
    TransactionStatus transaction = mock(TransactionStatus.class);
    Branch branch = branch();
    BranchStatistic branchStatistic = branchStatistic(branch);
    when(service.transactionManager.getTransaction(any())).thenReturn(transaction);
    when(service.branchStatisticRepository.findByBranch(branch)).thenReturn(branchStatistic);
    when(service.branchStatisticRepository.save(branchStatistic))
        .thenThrow(new IllegalStateException("failed"));

    try {
      service.updateBranchStatisticInTx(branch, ImmutableMap.of());
    } catch (IllegalStateException e) {
      assertEquals("failed", e.getMessage());
      verify(service.transactionManager).rollback(transaction);
      verify(service.transactionManager, never()).commit(transaction);
      return;
    }

    throw new AssertionError("Expected updateBranchStatisticInTx to rethrow the save failure");
  }

  private BranchStatisticService service() {
    BranchStatisticService service = new BranchStatisticService();
    service.branchStatisticRepository = mock(BranchStatisticRepository.class);
    service.branchTextUnitStatisticRepository = mock(BranchTextUnitStatisticRepository.class);
    service.transactionManager = mock(PlatformTransactionManager.class);
    return service;
  }

  private Branch branch() {
    Branch branch = new Branch();
    branch.setId(10L);
    branch.setName("feature");
    return branch;
  }

  private BranchStatistic branchStatistic(Branch branch) {
    BranchStatistic branchStatistic = new BranchStatistic();
    branchStatistic.setId(20L);
    branchStatistic.setBranch(branch);
    return branchStatistic;
  }
}
