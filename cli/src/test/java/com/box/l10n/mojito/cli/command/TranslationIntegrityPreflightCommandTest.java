package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.rest.client.TextUnitClient;
import com.box.l10n.mojito.rest.client.TextUnitClient.TextUnit;
import com.box.l10n.mojito.rest.entity.IntegrityCheckerType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TranslationIntegrityPreflightCommandTest {

  @Mock TextUnitClient textUnitClient;

  @Mock(answer = RETURNS_SELF)
  ConsoleWriter consoleWriter;

  private TranslationIntegrityPreflightCommand command;

  @BeforeEach
  void setUp() {
    command = new TranslationIntegrityPreflightCommand();
    command.textUnitClient = textUnitClient;
    command.consoleWriter = consoleWriter;
    command.repositoryNameParam = "sample-repository";
    command.assetExtensionParam = ".json";
    command.checkerTypeParam = IntegrityCheckerType.FORMATJS;
    command.maxTextUnitsParam = 5;
    command.maxDetailsParam = 2;
  }

  @Test
  void reportsBoundedTargetRepairAndSourceOutcomesWithoutTextContent() {
    when(textUnitClient.searchTextUnits(any()))
        .thenReturn(
            List.of(
                textUnit(1, "Hello {name}", "Bonjour {name}", "messages.json"),
                textUnit(2, "Hello {name", "Bonjour", "messages.json"),
                textUnit(3, "Hello {name}", "Bonjour", "messages.json"),
                textUnit(4, " Hello {name} ", "Bonjour {name}", "messages.json"),
                textUnit(5, "Hello {name}", "Bonjour {name}", "messages.json.bak"),
                textUnit(6, "Hello {name}", "Bonjour {name}", "later.json")));

    TranslationIntegrityPreflightCommand.PreflightResult result = command.preflight();

    assertThat(result.sampled()).isEqualTo(5);
    assertThat(result.evaluated()).isEqualTo(4);
    assertThat(result.passed()).isEqualTo(1);
    assertThat(result.rejectTarget()).isEqualTo(1);
    assertThat(result.autoRepairTarget()).isEqualTo(1);
    assertThat(result.rejectSource()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.truncated()).isTrue();
    assertThat(result.findings()).hasSize(2);
    assertThat(result.findings())
        .extracting(TranslationIntegrityPreflightCommand.PreflightFinding::tmTextUnitId)
        .containsExactly(3L, 4L);
    assertThat(result.findings())
        .flatExtracting(TranslationIntegrityPreflightCommand.PreflightFinding::diagnosticCodes)
        .doesNotContain("Hello {name}", "Bonjour");

    ArgumentCaptor<TextUnitClient.TextUnitSearchBody> searchCaptor =
        ArgumentCaptor.forClass(TextUnitClient.TextUnitSearchBody.class);
    verify(textUnitClient).searchTextUnits(searchCaptor.capture());
    TextUnitClient.TextUnitSearchBody search = searchCaptor.getValue();
    assertThat(search.getRepositoryNames()).containsExactly("sample-repository");
    assertThat(search.getAssetPath()).isEqualTo("\\.json$");
    assertThat(search.getSearchType()).isEqualTo(TextUnitClient.SearchType.REGEX);
    assertThat(search.getUsedFilter()).isEqualTo(TextUnitClient.UsedFilter.USED);
    assertThat(search.getStatusFilter())
        .isEqualTo(TextUnitClient.StatusFilter.TRANSLATED_AND_NOT_REJECTED);
    assertThat(search.isPluralFormFiltered()).isFalse();
    assertThat(search.getLimit()).isEqualTo(6);
    assertThat(search.getOffset()).isZero();
  }

  @Test
  void returnsAStableNonzeroExitForTargetFindings() {
    command.maxTextUnitsParam = 1;
    when(textUnitClient.searchTextUnits(any()))
        .thenReturn(List.of(textUnit(1, "Hello {name}", "Bonjour", "messages.json")));

    assertThatThrownBy(command::execute)
        .isInstanceOf(CommandWithExitStatusException.class)
        .extracting(exception -> ((CommandWithExitStatusException) exception).getExitCode())
        .isEqualTo(TranslationIntegrityPreflightCommand.FINDINGS_EXIT_CODE);

    ArgumentCaptor<String> outputCaptor = ArgumentCaptor.forClass(String.class);
    verify(consoleWriter, atLeastOnce()).a(outputCaptor.capture());
    assertThat(String.join("", outputCaptor.getAllValues()))
        .doesNotContain("Hello {name}", "Bonjour");
  }

  @Test
  void treatsMissingTargetAsABlockingFinding() {
    command.maxTextUnitsParam = 1;
    when(textUnitClient.searchTextUnits(any()))
        .thenReturn(List.of(textUnit(1, "Hello {name}", null, "messages.json")));

    TranslationIntegrityPreflightCommand.PreflightResult result = command.preflight();

    assertThat(result.evaluated()).isEqualTo(1);
    assertThat(result.rejectTarget()).isEqualTo(1);
    assertThat(result.findings())
        .singleElement()
        .satisfies(
            finding ->
                assertThat(finding.diagnosticCodes()).containsExactly("target:target-missing"));
  }

  @Test
  void refusesAnEmptyScopeInsteadOfReportingSuccess() {
    when(textUnitClient.searchTextUnits(any())).thenReturn(List.of());

    assertThatThrownBy(command::execute)
        .isInstanceOf(CommandException.class)
        .hasMessageContaining("No active translations matched");
  }

  @Test
  void postFiltersUnexpectedExtensionMatchesWithoutHidingALaterValidRow() {
    command.maxTextUnitsParam = 2;
    when(textUnitClient.searchTextUnits(any()))
        .thenReturn(
            List.of(
                textUnit(1, "Hello {name}", "Bonjour {name}", "messages.json.bak"),
                textUnit(2, "Hello {name}", "Bonjour {name}", "messages.json")));

    TranslationIntegrityPreflightCommand.PreflightResult result = command.preflight();

    assertThat(result.sampled()).isEqualTo(2);
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(result.evaluated()).isEqualTo(1);
    assertThat(result.passed()).isEqualTo(1);
  }

  @Test
  void stopsBeforeTheFirstRowWouldExceedTheEvaluationBudget() {
    String oversized =
        "x".repeat((int) TranslationIntegrityPreflightCommand.MAX_SAMPLE_UTF16_CODE_UNITS + 1);
    command.maxTextUnitsParam = 1;
    when(textUnitClient.searchTextUnits(any()))
        .thenReturn(List.of(textUnit(1, oversized, "target", "messages.json")));

    TranslationIntegrityPreflightCommand.PreflightResult result = command.preflight();

    assertThat(result.sampled()).isZero();
    assertThat(result.evaluated()).isZero();
    assertThat(result.sampledUtf16CodeUnits()).isZero();
    assertThat(result.inputBudgetReached()).isTrue();
    assertThat(result.truncated()).isTrue();
  }

  private static TextUnit textUnit(long id, String source, String target, String assetPath) {
    return new TextUnit(
        id,
        id + 100,
        1L,
        "message-" + id,
        source,
        null,
        target,
        "fr-FR",
        null,
        2L,
        3L,
        4L,
        5L,
        TextUnitClient.Status.APPROVED,
        true,
        6L,
        false,
        null,
        null,
        "sample-repository",
        assetPath,
        7L,
        8L,
        false,
        true,
        true,
        null,
        null);
  }
}
