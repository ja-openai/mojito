package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.cli.filefinder.FileMatch;
import com.box.l10n.mojito.cli.filefinder.file.FileType;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.client.AssetClient;
import com.box.l10n.mojito.rest.entity.Asset;
import com.box.l10n.mojito.rest.entity.Locale;
import com.box.l10n.mojito.rest.entity.PollableTask;
import com.box.l10n.mojito.rest.entity.RepositoryLocale;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.stubbing.OngoingStubbing;

public class PullCommandParallelDiagnosticsTest {

  PullCommand command;
  FileMatch sourceFileMatch;
  Asset asset;
  List<RepositoryLocale> locales;
  Map<RepositoryLocale, List<String>> outputTags;

  @Before
  public void setUp() {
    command = new PullCommand();
    command.assetClient = mock(AssetClient.class);
    command.consoleWriter = mock(ConsoleWriter.class, RETURNS_SELF);
    command.objectMapper = new ObjectMapper();
    command.pullWithNoSourceBranches = List.of();
    sourceFileMatch = mock(FileMatch.class);
    when(sourceFileMatch.getFileType()).thenReturn(mock(FileType.class));
    asset = new Asset();
    asset.setId(11L);
    RepositoryLocale aliasedLocale = new RepositoryLocale();
    RepositoryLocale unmappedLocale = new RepositoryLocale();
    Locale locale = new Locale();
    locale.setBcp47Tag("de-DE");
    unmappedLocale.setLocale(locale);
    locales = List.of(aliasedLocale, unmappedLocale);
    outputTags = Map.of(aliasedLocale, List.of("fr", "fr-FR"));
  }

  @Test
  public void acceptedRequestReportsOnlyIdentityAndExpandedOutputCount() {
    PollableTask parent = new PollableTask();
    parent.setId(31L);
    request().thenReturn(parent);

    assertThat(sendRequest()).isSameAs(parent);

    InOrder output = inOrder(command.consoleWriter);
    output.verify(command.consoleWriter).a("Accepted parallel localize request: parent_task_id=");
    output.verify(command.consoleWriter).a(31L);
    output.verify(command.consoleWriter).a(" asset_id=");
    output.verify(command.consoleWriter).a(11L);
    output.verify(command.consoleWriter).a(" requested_outputs=");
    output.verify(command.consoleWriter).a(3);
    output.verify(command.consoleWriter).a(" requested_output_tags=");
    output.verify(command.consoleWriter).a("[\"de-DE\",\"fr\",\"fr-FR\"]");
    output.verify(command.consoleWriter).println();
    output.verifyNoMoreInteractions();
  }

  @Test
  public void rejectedRequestDoesNotReportAcceptance() {
    RuntimeException rejected = new RuntimeException("Request rejected");
    request().thenThrow(rejected);

    assertThatThrownBy(this::sendRequest).isSameAs(rejected);

    verifyNoInteractions(command.consoleWriter);
  }

  @Test
  public void diagnosticFailureStillReturnsAcceptedParentWithoutResubmitting() {
    PollableTask parent = new PollableTask();
    parent.setId(31L);
    request().thenReturn(parent);
    when(command.consoleWriter.println()).thenThrow(new RuntimeException("Console unavailable"));
    clearInvocations(command.assetClient);

    assertThat(sendRequest()).isSameAs(parent);

    assertThat(mockingDetails(command.assetClient).getInvocations()).hasSize(1);
  }

  private PollableTask sendRequest() {
    return command.getLocalizedAssetBodyParallel(
        sourceFileMatch, locales, outputTags, List.of(), asset, "source content");
  }

  private OngoingStubbing<PollableTask> request() {
    return when(
        command.assetClient.getLocalizedAssetForContentParallel(
            11L,
            "source content",
            locales,
            outputTags,
            null,
            List.of(),
            command.status,
            command.inheritanceMode,
            null,
            false,
            List.of()));
  }
}
