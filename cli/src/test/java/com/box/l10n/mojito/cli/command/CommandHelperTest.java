package com.box.l10n.mojito.cli.command;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.box.l10n.mojito.cli.filefinder.file.FileType;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection.Mode;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CommandHelperTest {

  private final CommandHelper commandHelper = new CommandHelper();

  @Test
  public void okapiConverterUsesFilterOptionsOrDefaultsUnchanged() {
    FileType fileType = fileTypeWithDefaultOptions(List.of("escapeMode=legacy"));
    List<String> explicitOptions = List.of("explicit=true");

    assertSame(
        fileType.getDefaultFilterOptions(),
        commandHelper.getFilterOptionsOrDefaults(fileType, null, Mode.OKAPI));
    assertSame(
        explicitOptions,
        commandHelper.getFilterOptionsOrDefaults(fileType, explicitOptions, Mode.OKAPI));
  }

  @Test
  public void explicitOkapiConverterOverridesPortableBackendDefault() {
    FileType fileType = fileTypeWithDefaultOptions(List.of("escapeMode=legacy"));

    assertEquals(
        List.of("escapeMode=legacy", LocalizationConverterSelection.OKAPI_OPTION),
        commandHelper.getFilterOptionsOrDefaults(fileType, null, Mode.OKAPI, true));
  }

  @Test
  public void portableConverterAddsMarkerToDefaultOptionsWithoutMutatingDefaults() {
    List<String> defaultOptions = new ArrayList<>(List.of("escapeMode=legacy"));
    FileType fileType = fileTypeWithDefaultOptions(defaultOptions);

    assertEquals(
        List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION),
        commandHelper.getFilterOptionsOrDefaults(fileType, null, Mode.PORTABLE));
    assertEquals(List.of("escapeMode=legacy"), defaultOptions);
  }

  @Test
  public void portableConverterAddsMarkerToExplicitOptionsWithoutMutatingInput() {
    FileType fileType = fileTypeWithDefaultOptions(List.of("escapeMode=legacy"));
    List<String> explicitOptions = new ArrayList<>(List.of("explicit=true"));

    assertEquals(
        List.of("explicit=true", LocalizationConverterSelection.PORTABLE_OPTION),
        commandHelper.getFilterOptionsOrDefaults(fileType, explicitOptions, Mode.PORTABLE));
    assertEquals(List.of("explicit=true"), explicitOptions);
  }

  @Test
  public void portableConverterDoesNotDuplicateMarker() {
    FileType fileType = fileTypeWithDefaultOptions(List.of("escapeMode=legacy"));
    List<String> explicitOptions =
        List.of("explicit=true", LocalizationConverterSelection.PORTABLE_OPTION);

    assertEquals(
        explicitOptions,
        commandHelper.getFilterOptionsOrDefaults(fileType, explicitOptions, Mode.PORTABLE));
  }

  private static FileType fileTypeWithDefaultOptions(List<String> defaultOptions) {
    FileType fileType = new FileType() {};
    fileType.setDefaultFilterOptions(defaultOptions);
    return fileType;
  }
}
