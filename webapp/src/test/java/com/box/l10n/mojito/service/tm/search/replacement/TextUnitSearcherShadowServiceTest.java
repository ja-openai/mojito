package com.box.l10n.mojito.service.tm.search.replacement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.tm.search.TextUnitAndWordCount;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.replacement.hibernatecriteria.HibernateCriteriaTextUnitSearcher;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class TextUnitSearcherShadowServiceTest {

  @Mock HibernateCriteriaTextUnitSearcher hibernateCriteriaTextUnitSearcher;

  private ch.qos.logback.classic.Logger shadowLogger;
  private ch.qos.logback.classic.Level previousShadowLoggerLevel;

  @Before
  public void disableShadowLogger() {
    shadowLogger =
        (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(TextUnitSearcherShadowService.class);
    previousShadowLoggerLevel = shadowLogger.getLevel();
    shadowLogger.setLevel(ch.qos.logback.classic.Level.OFF);
  }

  @After
  public void restoreShadowLogger() {
    shadowLogger.setLevel(previousShadowLoggerLevel);
  }

  @Test
  public void compareSearchThrowsWhenMismatchAndFailOnMismatchIsEnabled() {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    TextUnitSearcherShadowService shadowService =
        new TextUnitSearcherShadowService(hibernateCriteriaTextUnitSearcher, true);
    when(hibernateCriteriaTextUnitSearcher.search(parameters)).thenReturn(List.of(dto(2L)));

    assertThatThrownBy(() -> shadowService.compareSearch(parameters, List.of(dto(1L)), 12L))
        .isInstanceOf(TextUnitSearcherShadowService.TextUnitSearcherShadowException.class)
        .hasMessageContaining("TextUnitSearcher shadow search mismatch");
  }

  @Test
  public void compareSearchDoesNotThrowWhenRetryMatchesAndFailOnMismatchIsEnabled() {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    TextUnitSearcherShadowService shadowService =
        new TextUnitSearcherShadowService(hibernateCriteriaTextUnitSearcher, true);
    when(hibernateCriteriaTextUnitSearcher.search(parameters))
        .thenReturn(List.of(dto(2L)), List.of(dto(2L)));

    assertThatCode(
            () ->
                shadowService.compareSearch(
                    parameters,
                    List.of(dto(1L)),
                    12L,
                    () -> new TextUnitSearcherShadowService.TimedResult<>(List.of(dto(2L)), 8L)))
        .doesNotThrowAnyException();
    verify(hibernateCriteriaTextUnitSearcher, times(2)).search(parameters);
  }

  @Test
  public void compareCountDoesNotThrowWhenMismatchAndFailOnMismatchIsDisabled() {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    TextUnitSearcherShadowService shadowService =
        new TextUnitSearcherShadowService(hibernateCriteriaTextUnitSearcher, false);
    when(hibernateCriteriaTextUnitSearcher.countTextUnitAndWordCount(parameters))
        .thenReturn(new TextUnitAndWordCount(1, 2));

    shadowService.compareCount(parameters, new TextUnitAndWordCount(1, 1), 12L);
  }

  @Test
  public void compareSearchThrowsWhenCriteriaSearchFailsAndFailOnMismatchIsEnabled() {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    TextUnitSearcherShadowService shadowService =
        new TextUnitSearcherShadowService(hibernateCriteriaTextUnitSearcher, true);
    when(hibernateCriteriaTextUnitSearcher.search(parameters))
        .thenThrow(new RuntimeException("bad"));

    assertThatThrownBy(() -> shadowService.compareSearch(parameters, List.of(dto(1L)), 12L))
        .isInstanceOf(TextUnitSearcherShadowService.TextUnitSearcherShadowException.class)
        .hasMessageContaining("failed while running Hibernate Criteria comparison");
  }

  private TextUnitDTO dto(Long id) {
    TextUnitDTO dto = new TextUnitDTO();
    dto.setTmTextUnitId(id);
    dto.setLocaleId(1L);
    dto.setTargetLocale("fr-FR");
    dto.setName("name-" + id);
    dto.setSource("source-" + id);
    return dto;
  }
}
