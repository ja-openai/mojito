package com.box.l10n.mojito.rest;

import com.box.l10n.mojito.Application;
import com.box.l10n.mojito.factory.XliffDataFactory;
import com.box.l10n.mojito.rest.annotation.WithDefaultTestUser;
import com.box.l10n.mojito.rest.client.LocaleClient;
import com.box.l10n.mojito.rest.client.exception.LocaleNotFoundException;
import com.box.l10n.mojito.rest.entity.RepositoryLocale;
import com.box.l10n.mojito.rest.resttemplate.AuthenticatedRestTemplate;
import com.box.l10n.mojito.rest.resttemplate.ResttemplateConfig;
import com.box.l10n.mojito.xml.XmlParsingConfiguration;
import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.Assert;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.aspectj.AnnotationBeanConfigurerAspect;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;

/**
 * Base class for WS integration tests. Creates an in-memory instance of tomcat and setup the REST
 * client to use the port that was bound during container initialization.
 *
 * @author jaurambault
 */
@RunWith(SpringRunner.class)
@SpringBootTest(
    classes = Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@WithDefaultTestUser
public class WSTestBase {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(WSTestBase.class);

  @Autowired protected AuthenticatedRestTemplate authenticatedRestTemplate;

  @Autowired protected XliffDataFactory xliffDataFactory;

  @Autowired protected LocaleClient localeClient;

  @Autowired ResttemplateConfig resttemplateConfig;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private ApplicationContext applicationContext;

  @LocalServerPort int port;

  @Before
  public void useCurrentContextForAspects() {
    // AspectJ aspects are singletons, while tests with different bean overrides have separate
    // cached Spring contexts. Rebind the aspects before using this context's services.
    AnnotationTransactionAspect.aspectOf().setTransactionManager(transactionManager);
    AnnotationBeanConfigurerAspect.aspectOf()
        .setBeanFactory(applicationContext.getAutowireCapableBeanFactory());
  }

  @PostConstruct
  public void setPort() {
    logger.debug("Saving port number = {}", port);
    resttemplateConfig.setPort(port);

    XmlParsingConfiguration.disableXPathLimits();
  }

  /**
   * Returns a list of {@link RepositoryLocale}s whose locales correspond to the given tags
   *
   * @param bcp47Tags
   * @return
   */
  protected Set<RepositoryLocale> getRepositoryLocales(List<String> bcp47Tags) {

    Set<RepositoryLocale> repositoryLocales = new HashSet<>();

    for (String bcp47Tag : bcp47Tags) {
      try {
        RepositoryLocale repositoryLocale = new RepositoryLocale();
        repositoryLocale.setLocale(localeClient.getLocaleByBcp47Tag(bcp47Tag));
        repositoryLocales.add(repositoryLocale);
      } catch (LocaleNotFoundException e) {
        logger.error("Locale not found for BCP47 tag: {}. Skipping it.", bcp47Tag);
      }
    }

    return repositoryLocales;
  }

  /**
   * Wait until a condition is true with timeout.
   *
   * @param failMessage
   * @param condition
   * @throws InterruptedException
   */
  protected void waitForCondition(String failMessage, Supplier<Boolean> condition)
      throws InterruptedException {
    waitForCondition(failMessage, condition, 30, 100);
  }

  protected void waitForCondition(
      String failMessage,
      Supplier<Boolean> condition,
      int maxNumberAttempt,
      int milisecondSleepTime)
      throws InterruptedException {
    int numberAttempt = 0;
    while (true) {
      numberAttempt++;

      boolean res;

      try {
        res = condition.get();
      } catch (Throwable t) {
        res = false;
      }

      if (res) {
        break;
      } else if (numberAttempt > maxNumberAttempt) {
        Assert.fail(failMessage);
      }
      Thread.sleep(numberAttempt * milisecondSleepTime);
    }
  }
}
