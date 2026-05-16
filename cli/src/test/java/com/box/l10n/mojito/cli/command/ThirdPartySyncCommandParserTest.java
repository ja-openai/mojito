package com.box.l10n.mojito.cli.command;

import static com.box.l10n.mojito.rest.ThirdPartySyncAction.MAP_TEXTUNIT;
import static com.box.l10n.mojito.rest.ThirdPartySyncAction.PUSH_SCREENSHOT;
import static org.assertj.core.api.Assertions.assertThat;

import com.beust.jcommander.JCommander;
import java.util.List;
import org.junit.Test;

public class ThirdPartySyncCommandParserTest {

  @Test
  public void parsesActionsAndOptionsUntilNextOption() {
    ThirdPartySyncCommand command = new ThirdPartySyncCommand();
    JCommander jCommander = new JCommander();
    jCommander.addCommand(command);

    jCommander.parse(
        "thirdparty-sync",
        "-r",
        "repo",
        "-p",
        "project",
        "-a",
        MAP_TEXTUNIT.name(),
        PUSH_SCREENSHOT.name(),
        "-ps",
        "\\u0032_",
        "-st",
        "%skip_text_pattern",
        "-sa",
        "%skip_asset_pattern%",
        "-t",
        "10",
        "-o",
        "excluded-locales=fr,ga",
        "special-option=value@of%Option");

    assertThat(command.repositoryParam).isEqualTo("repo");
    assertThat(command.thirdPartyProjectId).isEqualTo("project");
    assertThat(command.actions).containsExactly(MAP_TEXTUNIT, PUSH_SCREENSHOT);
    assertThat(command.pluralSeparator).isEqualTo("\\u0032_");
    assertThat(command.skipTextUnitsWithPattern).isEqualTo("%skip_text_pattern");
    assertThat(command.skipAssetsWithPathPattern).isEqualTo("%skip_asset_pattern%");
    assertThat(command.timeoutInSeconds).isEqualTo(10);
    assertThat(command.options)
        .isEqualTo(List.of("excluded-locales=fr,ga", "special-option=value@of%Option"));
  }
}
