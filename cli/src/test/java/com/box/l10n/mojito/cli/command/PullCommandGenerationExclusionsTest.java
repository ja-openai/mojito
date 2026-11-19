package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.LocaleMappingHelper;
import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.cli.filefinder.FileMatch;
import com.box.l10n.mojito.cli.filefinder.file.FileType;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.client.AssetClient;
import com.box.l10n.mojito.rest.client.PollableTaskClient;
import com.box.l10n.mojito.rest.entity.Asset;
import com.box.l10n.mojito.rest.entity.Locale;
import com.box.l10n.mojito.rest.entity.PollableTask;
import com.box.l10n.mojito.rest.entity.Repository;
import com.box.l10n.mojito.rest.entity.RepositoryLocale;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The existing parallel protocol can omit outputs without changing CLI behavior or arguments. */
public class PullCommandGenerationExclusionsTest {

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private PullCommand command;
  private Path output;

  @Before
  public void setUp() throws Exception {
    command = new PullCommand();
    command.commandHelper = spy(new CommandHelper());
    command.commandHelper.pollableTaskClient = mock(PollableTaskClient.class);
    command.assetClient = mock(AssetClient.class);
    command.consoleWriter = mock(ConsoleWriter.class, RETURNS_SELF);
    command.objectMapper = new ObjectMapper();
    command.localeMappingHelper = new LocaleMappingHelper();
    command.repositoryParam = "example";
    command.isParallel = true;
    command.localeMappingParam = "fr-FR:fr,he-IL:he";
    output = temporary.newFolder("output").toPath();
    command.sourceDirectoryParam = temporary.getRoot().toString();
    command.targetDirectoryParam = output.toString();

    RepositoryLocale root = locale(1L, "en", null);
    Repository repository = new Repository();
    repository.setId(7L);
    repository.setRepositoryLocales(Set.of(root, locale(2L, "fr", root), locale(3L, "he", root)));
    doReturn(repository).when(command.commandHelper).findRepositoryByName("example");

    Path sourcePath = temporary.newFile("messages.json").toPath();
    Files.writeString(sourcePath, "{}");
    FileMatch source = mock(FileMatch.class);
    when(source.getFileType()).thenReturn(mock(FileType.class));
    when(source.getPath()).thenReturn(sourcePath);
    when(source.getSourcePath()).thenReturn("messages.json");
    when(source.getTargetPath(any())).thenAnswer(invocation -> invocation.getArgument(0) + ".json");
    doReturn(new ArrayList<>(List.of(source)))
        .when(command.commandHelper)
        .getSourceFileMatches(any(), any(), any(), any(), any(), any());
    doReturn(List.of())
        .when(command.commandHelper)
        .getFilterOptionsOrDefaults(any(), any(), any(), anyBoolean());
    doReturn("messages.json")
        .when(command.commandHelper)
        .getMappedSourcePath(any(), eq("messages.json"));
    doReturn("{}").when(command.commandHelper).getFileContentWithXcodePatch(source);
    doNothing().when(command.commandHelper).waitForPollableTaskSilencedOutput(31L);
    Asset asset = new Asset();
    asset.setId(11L);
    when(command.assetClient.getAssetByPathAndRepositoryId("messages.json", 7L)).thenReturn(asset);
    PollableTask parent = new PollableTask();
    parent.setId(31L);
    when(command.assetClient.getLocalizedAssetForContentParallel(
            eq(11L),
            eq("{}"),
            anyList(),
            anyMap(),
            isNull(),
            eq(List.of()),
            eq(command.status),
            eq(command.inheritanceMode),
            isNull(),
            eq(false),
            eq(List.of())))
        .thenAnswer(
            invocation -> {
              List<RepositoryLocale> requested = invocation.getArgument(2);
              assertThat(requested)
                  .extracting(locale -> locale.getLocale().getBcp47Tag())
                  .containsExactlyInAnyOrder("fr", "he");
              Map<RepositoryLocale, List<String>> mappings = invocation.getArgument(3);
              assertThat(mappings.values())
                  .containsExactlyInAnyOrder(List.of("fr-FR"), List.of("he-IL"));
              return parent;
            });
  }

  @Test
  public void serverCanOmitAnExplicitlyMappedLocaleWithoutWritingItsFile() throws Exception {
    when(command.commandHelper.pollableTaskClient.getPollableTaskOutput(31L))
        .thenReturn("{\"generateLocalizedAssetJobIds\":{\"fr-FR\":32}}");
    when(command.commandHelper.pollableTaskClient.getPollableTaskOutput(32L))
        .thenReturn("{\"bcp47Tag\":\"fr-FR\",\"content\":\"{}\"}");

    command.execute();

    assertThat(command.skipEmptyOutput).isFalse();
    assertThat(Files.readString(output.resolve("fr-FR.json"))).isEqualTo("{}");
    assertThat(output.resolve("he-IL.json")).doesNotExist();
    verify(command.commandHelper).findRepositoryByName("example");
  }

  @Test
  public void serverCanOmitAllRequestedLocalesWithoutWritingFiles() throws Exception {
    when(command.commandHelper.pollableTaskClient.getPollableTaskOutput(31L))
        .thenReturn("{\"generateLocalizedAssetJobIds\":{}}");

    command.execute();

    try (var files = Files.list(output)) {
      assertThat(files).isEmpty();
    }
  }

  private RepositoryLocale locale(Long id, String tag, RepositoryLocale parent) {
    Locale locale = new Locale();
    locale.setId(id);
    locale.setBcp47Tag(tag);
    RepositoryLocale result = new RepositoryLocale();
    result.setLocale(locale);
    result.setParentLocale(parent);
    return result;
  }
}
