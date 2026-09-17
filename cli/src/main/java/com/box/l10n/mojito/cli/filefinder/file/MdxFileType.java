package com.box.l10n.mojito.cli.filefinder.file;

import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import java.util.List;

/** MDX documents use the portable, source-preserving document converter. */
public class MdxFileType extends LocaleInNameFileType {
  public MdxFileType() {
    this.sourceFileExtension = "mdx";
    this.defaultFilterOptions = List.of(LocalizationConverterSelection.PORTABLE_OPTION);
  }
}
