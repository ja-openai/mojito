package com.box.l10n.mojito.service.assetExtraction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetContent;
import com.box.l10n.mojito.entity.AssetExtraction;
import com.box.l10n.mojito.entity.AssetExtractionByBranch;
import com.box.l10n.mojito.entity.AssetTextUnit;
import com.box.l10n.mojito.entity.Branch;
import com.box.l10n.mojito.entity.PluralForm;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.box.l10n.mojito.localtm.merger.MultiBranchState;
import com.google.common.collect.ImmutableList;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

public class AssetExtractionServiceTransactionTest {

  private final PlatformTransactionManager transactionManager =
      Mockito.mock(PlatformTransactionManager.class);
  private final TransactionStatus transactionStatus = Mockito.mock(TransactionStatus.class);
  private final TestAssetExtractionService assetExtractionService =
      new TestAssetExtractionService();

  @Before
  public void setUp() {
    assetExtractionService.transactionManager = transactionManager;
    when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
  }

  @Test
  public void createAssetExtractionCommitsTransaction() {
    assetExtractionService.createAssetExtraction(new Asset(), new PollableTask());

    verify(transactionManager).commit(transactionStatus);
    verify(transactionManager, never()).rollback(transactionStatus);
  }

  @Test
  public void updateAssetExtractionWithStateRollsBackTransactionOnRuntimeException() {
    RuntimeException failure = new RuntimeException("update failed");
    assetExtractionService.failure = failure;

    assertThatThrownBy(
            () ->
                assetExtractionService.updateAssetExtractionWithStateInTx(
                    new AssetExtraction(), null, null, null))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  @Test
  public void createAssetTextUnitRollsBackTransactionOnError() {
    AssertionError failure = new AssertionError("create text unit failed");
    assetExtractionService.error = failure;

    assertThatThrownBy(
            () ->
                assetExtractionService.createAssetTextUnit(
                    1L, "name", "content", "comment", null, null, false, Set.of(), null))
        .isSameAs(failure);

    verify(transactionManager).rollback(transactionStatus);
    verify(transactionManager, never()).commit(transactionStatus);
  }

  private static class TestAssetExtractionService extends AssetExtractionService {

    private RuntimeException failure;
    private Error error;

    @Override
    MultiBranchState updateAssetExtractionWithStateNoTx(
        AssetExtraction assetExtraction,
        MultiBranchState currentState,
        Modifications modifications,
        AssetContentMd5s assetContentMd5s) {
      throwIfConfigured();
      return currentState;
    }

    @Override
    AssetExtraction createLastSuccessfulAssetExtractionInAssetNoTx(Asset asset) {
      throwIfConfigured();
      return new AssetExtraction();
    }

    @Override
    ImmutableList<BranchStateTextUnit> createTmTextUnitsNoTx(
        Asset asset, ImmutableList<BranchStateTextUnit> textUnits, User createdByUser) {
      throwIfConfigured();
      return textUnits;
    }

    @Override
    AssetExtractionByBranch createAssetExtractionForBranchNoTx(AssetContent assetContent) {
      throwIfConfigured();
      return new AssetExtractionByBranch();
    }

    @Override
    void deleteAssetBranchNoTx(
        Asset asset,
        Modifications modifications,
        MultiBranchState withBranchRemoved,
        AssetExtractionByBranch assetExtractionByBranch) {
      throwIfConfigured();
    }

    @Override
    AssetExtraction createAssetExtractionNoTx(Asset asset, PollableTask pollableTask) {
      throwIfConfigured();
      return new AssetExtraction();
    }

    @Override
    AssetTextUnit createAssetTextUnitNoTx(
        Long assetExtractionId,
        String name,
        String content,
        String comment,
        PluralForm pluralForm,
        String pluralFormOther,
        boolean doNotTranslate,
        Set<String> usages,
        Branch branch) {
      throwIfConfigured();
      return new AssetTextUnit();
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
