package com.box.l10n.mojito.service.security.user;

import com.box.l10n.mojito.entity.security.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Component
public class UserDeletionService {

  @Autowired UserRepository userRepository;

  @Autowired AuthorityRepository authorityRepository;

  @Autowired UserLocaleRepository userLocaleRepository;

  @Autowired PlatformTransactionManager transactionManager;

  public void hardDeleteUser(Long userId) throws DataIntegrityViolationException {
    DefaultTransactionDefinition transactionDefinition = new DefaultTransactionDefinition();
    transactionDefinition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    TransactionStatus transaction = transactionManager.getTransaction(transactionDefinition);

    try {
      hardDeleteUserInTransaction(userId);
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void hardDeleteUserInTransaction(Long userId) throws DataIntegrityViolationException {
    User user = userRepository.findById(userId).orElse(null);
    if (user == null) {
      return;
    }
    authorityRepository.deleteAll(user.getAuthorities());
    userLocaleRepository.deleteAll(user.getUserLocales());
    userRepository.deleteById(userId);
    userRepository.flush();
  }
}
