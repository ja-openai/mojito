package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class TMServiceLogRedactionTest {

  @Test
  public void variantWriteDebugLogDoesNotContainTranslationContent() {
    String sensitiveTarget = "credential-bearing replacement target";
    TMService service = new TMService();
    service.entityManager = mock(EntityManager.class);
    service.tmTextUnitVariantRepository = mock(TMTextUnitVariantRepository.class);
    when(service.entityManager.getReference(TMTextUnit.class, 6L)).thenReturn(new TMTextUnit());
    when(service.entityManager.getReference(Locale.class, 7L)).thenReturn(new Locale());
    when(service.tmTextUnitVariantRepository.save(any(TMTextUnitVariant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Logger logger = (Logger) LoggerFactory.getLogger(TMService.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      service.addTMTextUnitVariant(
          6L, 7L, sensitiveTarget, null, TMTextUnitVariant.Status.REVIEW_NEEDED, true, null, null);
    } finally {
      logger.setLevel(previousLevel);
      logger.detachAppender(appender);
      appender.stop();
    }

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(messages)
        .anySatisfy(
            message ->
                assertThat(message).contains("Add TMTextUnitVariant", "tmId: 6", "locale id: 7"));
    assertThat(messages).allSatisfy(message -> assertThat(message).doesNotContain(sensitiveTarget));
  }
}
