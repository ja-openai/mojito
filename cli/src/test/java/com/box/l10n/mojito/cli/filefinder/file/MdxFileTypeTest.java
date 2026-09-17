package com.box.l10n.mojito.cli.filefinder.file;

import static com.box.l10n.mojito.cli.filefinder.FilePattern.BASE_NAME;
import static com.box.l10n.mojito.cli.filefinder.FilePattern.LOCALE;
import static com.box.l10n.mojito.cli.filefinder.FilePattern.PARENT_PATH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.cli.command.CommandHelper;
import com.box.l10n.mojito.cli.command.FileTypeConverter;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection.Mode;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.Test;

public class MdxFileTypeTest {
  @Test
  public void discoversNestedDocumentsAndUsesLocaleTargetNames() {
    MdxFileType type = new MdxFileType();
    Matcher source = type.getSourceFilePattern().getPattern().matcher("content/help/invite.mdx");
    assertTrue(source.matches());
    assertEquals("content/help/", source.group(PARENT_PATH));
    assertEquals("invite", source.group(BASE_NAME));

    Matcher target =
        type.getTargetFilePattern().getPattern().matcher("content/help/invite_fr-FR.mdx");
    assertTrue(target.matches());
    assertEquals("fr-FR", target.group(LOCALE));
    assertEquals("invite", target.group(BASE_NAME));
  }

  @Test
  public void commandSelectionUsesPortableWithoutChangingOtherFormatDefaults() {
    FileType type = new FileTypeConverter().convert("MDX");
    assertTrue(type instanceof MdxFileType);
    CommandHelper helper = new CommandHelper();
    assertEquals(
        List.of(LocalizationConverterSelection.PORTABLE_OPTION),
        helper.getFilterOptionsOrDefaults(type, null, Mode.OKAPI, false));
    assertEquals(
        List.of(LocalizationConverterSelection.OKAPI_OPTION),
        helper.getFilterOptionsOrDefaults(type, null, Mode.OKAPI, true));
  }
}
