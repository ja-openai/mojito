package com.box.l10n.mojito.test;

import org.springframework.beans.factory.aspectj.AnnotationBeanConfigurerAspect;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.aspectj.AnnotationTransactionAspect;

/** Rebinds Spring's singleton AspectJ test aspects to the context that owns the current test. */
public class AspectJTestContextResetTestExecutionListener extends AbstractTestExecutionListener {

  @Override
  public void prepareTestInstance(TestContext testContext) {
    rebindAspectJContext(testContext);
  }

  @Override
  public void beforeTestMethod(TestContext testContext) {
    rebindAspectJContext(testContext);
  }

  private void rebindAspectJContext(TestContext testContext) {
    AnnotationBeanConfigurerAspect.aspectOf()
        .setBeanFactory(testContext.getApplicationContext().getAutowireCapableBeanFactory());
    AnnotationTransactionAspect transactionAspect = AnnotationTransactionAspect.aspectOf();
    transactionAspect.setTransactionManager(
        testContext.getApplicationContext().getBean(PlatformTransactionManager.class));
    transactionAspect.setBeanFactory(
        testContext.getApplicationContext().getAutowireCapableBeanFactory());
  }
}
