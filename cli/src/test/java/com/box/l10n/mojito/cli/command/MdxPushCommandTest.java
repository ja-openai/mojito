package com.box.l10n.mojito.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.cli.filefinder.FileMatch;
import com.box.l10n.mojito.cli.filefinder.file.MdxFileType;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection.Mode;
import com.box.l10n.mojito.rest.entity.Repository;
import com.box.l10n.mojito.rest.entity.SourceAsset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/** Exercises the upload boundary without an external server or database. */
public class MdxPushCommandTest {
  @Test
  public void sendsOriginalDocumentAndPortableSelectionToTheExistingAssetEndpoint()
      throws Exception {
    String source =
        "import { Callout } from './components';\n\n"
            + "{/* mojito-id: invite.title */}\n# Invite your team\n\n"
            + "<Callout>\nShare **ideas** and [projects](/projects).\n</Callout>\n";
    MdxFileType type = new MdxFileType();
    FileMatch match = mock(FileMatch.class);
    when(match.getFileType()).thenReturn(type);
    when(match.getSourcePath()).thenReturn("help/invite.mdx");
    Repository repository = new Repository();
    repository.setId(42L);
    CommandHelper helper = mock(CommandHelper.class);
    when(helper.findRepositoryByName("content")).thenReturn(repository);
    when(helper.getSourceFileMatches(any(), any(), any(), any(), any(), any()))
        .thenReturn(new ArrayList<>(List.of(match)));
    when(helper.getFileContentWithXcodePatch(match)).thenReturn(source);
    when(helper.getMappedSourcePath(null, "help/invite.mdx")).thenReturn("help/invite.mdx");
    when(helper.getFilterOptionsOrDefaults(type, null, Mode.OKAPI, false)).thenCallRealMethod();
    when(helper.getFilterOptionsOrDefaults(type, null)).thenCallRealMethod();

    PushService push = mock(PushService.class);
    List<SourceAsset> uploads = new ArrayList<>();
    doAnswer(
            invocation -> {
              Stream<SourceAsset> stream = invocation.getArgument(1);
              uploads.addAll(stream.toList());
              return null;
            })
        .when(push)
        .push(eq(repository), any(), eq("content-review"), any());
    PushCommand command = new PushCommand();
    command.commandHelper = helper;
    command.pushService = push;
    command.consoleWriter = mock(ConsoleWriter.class, RETURNS_SELF);
    command.repositoryParam = "content";
    command.branchName = "content-review";
    command.execute();

    assertEquals(1, uploads.size());
    SourceAsset uploaded = uploads.get(0);
    assertEquals(source, uploaded.getContent());
    assertEquals("help/invite.mdx", uploaded.getPath());
    assertEquals("content-review", uploaded.getBranch());
    assertEquals(Long.valueOf(42), uploaded.getRepositoryId());
    assertFalse(uploaded.isExtractedContent());
    assertNull(uploaded.getFilterConfigIdOverride());
    assertEquals(
        List.of(LocalizationConverterSelection.PORTABLE_OPTION), uploaded.getFilterOptions());
  }
}
