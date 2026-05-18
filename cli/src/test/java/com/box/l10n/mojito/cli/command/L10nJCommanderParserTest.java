package com.box.l10n.mojito.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.cli.filefinder.file.AndroidStringsFileType;
import org.junit.Test;

public class L10nJCommanderParserTest extends CLITestBase {

  @Test
  public void pushAcceptsSpaceSeparatedFileType() {
    L10nJCommander l10nJCommander = getL10nJCommander();

    l10nJCommander.jCommander.parse("push", "-r", "android", "-ft", "ANDROID_STRINGS");

    PushCommand pushCommand = l10nJCommander.getCommand(PushCommand.class);
    assertEquals("android", pushCommand.repositoryParam);
    assertEquals(1, pushCommand.fileTypes.size());
    assertTrue(pushCommand.fileTypes.get(0) instanceof AndroidStringsFileType);
  }

  @Test
  public void pushAcceptsSpaceSeparatedFileTypeBeforeAnotherOption() {
    L10nJCommander l10nJCommander = getL10nJCommander();

    l10nJCommander.jCommander.parse("push", "-r", "android", "-ft", "ANDROID_STRINGS", "-sl", "en");

    PushCommand pushCommand = l10nJCommander.getCommand(PushCommand.class);
    assertEquals(1, pushCommand.fileTypes.size());
    assertTrue(pushCommand.fileTypes.get(0) instanceof AndroidStringsFileType);
    assertEquals("en", pushCommand.sourceLocale);
  }
}
