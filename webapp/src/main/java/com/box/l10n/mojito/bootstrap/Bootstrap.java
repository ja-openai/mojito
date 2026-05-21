package com.box.l10n.mojito.bootstrap;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.security.UserDetailsImpl;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.ArrayList;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * @author wyau
 */
@Component
public class Bootstrap implements ApplicationListener<ContextRefreshedEvent> {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(Bootstrap.class);

  boolean initialized = false;

  @Autowired BootstrapConfig bootstrapConfig;

  @Autowired UserService userService;

  @Autowired UserRepository userRepository;

  @Autowired UserDetailsService userDetailsService;

  @Autowired PlatformTransactionManager transactionManager;

  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
    if (!initialized) {

      if (bootstrapConfig.isEnabled()) {
        createData();
      }

      initialized = true;
    }
  }

  /**
   * Checks if a system user exists to know if we need to create data or not
   *
   * @return {@code true} if data should be created else {@code false}
   */
  public boolean shouldCreateData() {
    return userRepository.count() == 0;
  }

  /** Create data in the database if none exist. */
  public void createData() {
    if (shouldCreateData()) {
      createSystemUser();
      createDefaultUser();
    } else {
      logger.debug("Data already present in the database, don't create data");
    }
  }

  public void createSystemUser() {
    TransactionStatus transaction =
        transactionManager.getTransaction(new DefaultTransactionDefinition());

    try {
      createSystemUserInTransaction();
      transactionManager.commit(transaction);
    } catch (RuntimeException e) {
      transactionManager.rollback(transaction);
      throw e;
    } catch (Error e) {
      transactionManager.rollback(transaction);
      throw e;
    }
  }

  void createSystemUserInTransaction() {
    logger.info("Creating system user with random password");
    String randomPassword = RandomStringUtils.secure().nextAlphanumeric(15);

    User systemUser =
        userService.createUserWithRole(
            UserService.SYSTEM_USERNAME, randomPassword, Role.ROLE_ADMIN);

    logger.debug("Disabling System user so that it can't be authenticated");
    systemUser.setEnabled(false);
    userRepository.save(systemUser);

    logger.debug("Setting created by user manually because there is no authenticated user context");
    userService.updateCreatedByUserToSystemUser(systemUser);
  }

  public void createDefaultUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    try {
      setAuthenticationToSystemUser();
      userService.createUserWithRole(
          bootstrapConfig.getDefaultUser().getUsername(),
          bootstrapConfig.getDefaultUser().getPassword(),
          Role.ROLE_ADMIN);
    } finally {
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
  }

  private void setAuthenticationToSystemUser() {
    logger.debug("Setting authentication as user: {}", UserService.SYSTEM_USERNAME);
    UserDetailsImpl user =
        (UserDetailsImpl) userDetailsService.loadUserByUsername(UserService.SYSTEM_USERNAME);
    SecurityContext securityContext = SecurityContextHolder.getContext();
    securityContext.setAuthentication(
        new UsernamePasswordAuthenticationToken(user, "", new ArrayList<GrantedAuthority>()));
  }
}
