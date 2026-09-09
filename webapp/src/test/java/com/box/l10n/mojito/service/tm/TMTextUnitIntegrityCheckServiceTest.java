package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.PluralForm;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckException;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.TextUnitIntegrityChecker;
import com.google.common.collect.Sets;
import java.util.HashSet;
import java.util.Optional;
import org.hibernate.LazyInitializationException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * @author wyau
 */
@RunWith(MockitoJUnitRunner.class)
public class TMTextUnitIntegrityCheckServiceTest {

  @InjectMocks TMTextUnitIntegrityCheckService integrityCheckService;

  @Mock IntegrityCheckerFactory integrityCheckerFactory;

  @Mock TMTextUnitRepository tmTextUnitRepository;

  @Mock PluralIntegrityCheckerRelaxer pluralIntegrityCheckerRelaxer;

  @Test
  public void nonWebCallKeepsPluralContextInsideATransaction() {
    Asset asset = Mockito.mock(Asset.class);
    TMTextUnit unit = Mockito.mock(TMTextUnit.class);
    PluralForm plural = Mockito.mock(PluralForm.class);
    TextUnitIntegrityChecker checker = Mockito.mock(TextUnitIntegrityChecker.class);
    IntegrityCheckException failure = new IntegrityCheckException("Missing required placeholder");
    Mockito.when(tmTextUnitRepository.findById(42L)).thenReturn(Optional.of(unit));
    Mockito.when(unit.getAsset()).thenReturn(asset);
    Mockito.when(unit.getContent()).thenReturn("{count} items");
    Mockito.when(unit.getPluralForm()).thenReturn(plural);
    Mockito.when(integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Sets.newHashSet(checker));
    Mockito.doThrow(failure).when(checker).check("{count} items", "articles");
    Mockito.when(plural.getName())
        .thenAnswer(
            invocation -> {
              if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new LazyInitializationException(
                    "Plural context needs the service transaction");
              }
              return "other";
            });

    // Apply Spring's real annotation-driven transaction advice without a servlet/session.
    ProxyFactory proxy = new ProxyFactory(integrityCheckService);
    proxy.addAdvice(
        new TransactionInterceptor(
            new TestTransactionManager(), new AnnotationTransactionAttributeSource()));
    TMTextUnitIntegrityCheckService service = (TMTextUnitIntegrityCheckService) proxy.getProxy();
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertSame(
        failure,
        assertThrows(
            IntegrityCheckException.class,
            () -> service.checkTMTextUnitIntegrity(42L, "articles")));
    Mockito.verify(pluralIntegrityCheckerRelaxer)
        .shouldRelaxIntegrityCheck("{count} items", "articles", "other", checker);
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
  }

  private static class TestTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  @Test
  public void testCheckTMTextUnitIntegrityWillRunThroughIfAssetHasNoIntegrityChecker() {
    Asset asset = Mockito.mock(Asset.class);
    TMTextUnit tmTextUnit = Mockito.mock(TMTextUnit.class);

    Mockito.when(tmTextUnit.getAsset()).thenReturn(asset);
    Mockito.when(tmTextUnitRepository.findById(Mockito.anyLong()))
        .thenReturn(Optional.of(tmTextUnit));
    Mockito.when(integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(new HashSet<TextUnitIntegrityChecker>());

    integrityCheckService.checkTMTextUnitIntegrity(1L, "string to check");

    Mockito.verify(integrityCheckerFactory, Mockito.times(1)).getTextUnitCheckers(asset);
  }

  @Test
  public void testCheckTMTextUnitIntegrityWillRunThroughAndCheck() {
    Asset asset = Mockito.mock(Asset.class);
    TMTextUnit tmTextUnit = Mockito.mock(TMTextUnit.class);
    Mockito.when(tmTextUnit.getContent()).thenReturn("some content");
    TextUnitIntegrityChecker checker = Mockito.mock(TextUnitIntegrityChecker.class);

    Mockito.when(tmTextUnit.getAsset()).thenReturn(asset);
    Mockito.when(tmTextUnitRepository.findById(Mockito.anyLong()))
        .thenReturn(Optional.of(tmTextUnit));
    Mockito.when(integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Sets.newHashSet(checker));

    integrityCheckService.checkTMTextUnitIntegrity(1L, "string to check");

    Mockito.verify(checker, Mockito.times(1)).check(Mockito.anyString(), Mockito.anyString());
  }

  @Test(expected = IntegrityCheckException.class)
  public void testCheckTMTextUnitIntegrityWillThrowWhenCheckFails() {
    Asset asset = Mockito.mock(Asset.class);
    TMTextUnit tmTextUnit = Mockito.mock(TMTextUnit.class);
    Mockito.when(tmTextUnit.getContent()).thenReturn("some content");
    TextUnitIntegrityChecker checker = Mockito.mock(TextUnitIntegrityChecker.class);

    Mockito.when(tmTextUnit.getAsset()).thenReturn(asset);
    Mockito.when(tmTextUnitRepository.findById(Mockito.anyLong()))
        .thenReturn(Optional.of(tmTextUnit));
    Mockito.when(integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Sets.newHashSet(checker));
    Mockito.doThrow(new IntegrityCheckException("bad"))
        .when(checker)
        .check(Mockito.anyString(), Mockito.anyString());

    integrityCheckService.checkTMTextUnitIntegrity(1L, "string to check");

    Mockito.verify(checker, Mockito.times(1)).check(Mockito.anyString(), Mockito.anyString());
  }
}
