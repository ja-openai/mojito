package com.box.l10n.mojito.service.assetintegritychecker;

import com.box.l10n.mojito.entity.AssetIntegrityChecker;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerType;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * @author wyau
 */
@Service
public class AssetIntegrityCheckerService {

  @Autowired AssetIntegrityCheckerRepository assetIntegrityCheckerRepository;

  @Autowired PlatformTransactionManager transactionManager;

  public void addToRepository(
      Repository repository, String assetPath, IntegrityCheckerType integrityCheckerType) {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      addToRepositoryNoTx(repository, assetPath, integrityCheckerType);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void addToRepositoryNoTx(
      Repository repository, String assetPath, IntegrityCheckerType integrityCheckerType) {
    AssetIntegrityChecker assetIntegrityChecker = new AssetIntegrityChecker();
    assetIntegrityChecker.setRepository(repository);
    assetIntegrityChecker.setAssetExtension(FilenameUtils.getExtension(assetPath));
    assetIntegrityChecker.setIntegrityCheckerType(integrityCheckerType);
    assetIntegrityCheckerRepository.save(assetIntegrityChecker);
  }
}
