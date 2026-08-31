package com.box.l10n.mojito.service.tm.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.FormatJsTranslationIntegrityChecker;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.IntegrityCheckerFactory;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.PluralIntegrityCheckerRelaxer;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService.IntegrityChecksType;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class TextUnitBatchImporterServiceLogRedactionTest {

  @Test
  public void integrityFailureLogDoesNotContainTranslationContent() {
    String sensitiveSource = "Contact secret-source@example.com with {name}";
    String sensitiveTarget = "Contact secret-target@example.com";
    Asset asset = new Asset();
    TextUnitDTO current = new TextUnitDTO();
    current.setTmTextUnitId(123L);
    current.setLocaleId(456L);
    current.setSource(sensitiveSource);
    current.setTarget("Existing target");
    current.setStatus(TMTextUnitVariant.Status.APPROVED);
    current.setIncludedInLocalizedFile(true);
    TextUnitForBatchMatcherImport candidate = new TextUnitForBatchMatcherImport();
    candidate.setCurrentTextUnit(current);
    candidate.setContent(sensitiveTarget);

    TextUnitBatchImporterService service = new TextUnitBatchImporterService();
    service.integrityCheckerFactory = mock(IntegrityCheckerFactory.class);
    service.pluralIntegrityCheckerRelaxer = mock(PluralIntegrityCheckerRelaxer.class);
    when(service.integrityCheckerFactory.getTextUnitCheckers(asset))
        .thenReturn(Set.of(new FormatJsTranslationIntegrityChecker()));

    Logger logger = (Logger) LoggerFactory.getLogger(TextUnitBatchImporterService.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.INFO);
    try {
      service.applyIntegrityChecks(
          asset, List.of(candidate), IntegrityChecksType.ALWAYS_USE_INTEGRITY_CHECKER_STATUS);
    } finally {
      logger.setLevel(previousLevel);
      logger.detachAppender(appender);
      appender.stop();
    }

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(messages)
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains(
                        "Integrity check failed",
                        "tmTextUnitId=123",
                        "localeId=456",
                        "checker=FormatJsTranslationIntegrityChecker"));
    assertThat(messages)
        .allSatisfy(
            message ->
                assertThat(message)
                    .doesNotContain(
                        sensitiveSource,
                        sensitiveTarget,
                        "secret-source@example.com",
                        "secret-target@example.com"));
  }
}
