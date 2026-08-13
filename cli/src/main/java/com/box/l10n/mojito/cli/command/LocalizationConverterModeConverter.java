package com.box.l10n.mojito.cli.command;

import com.box.l10n.mojito.fileformat.LocalizationConverterSelection.Mode;

public class LocalizationConverterModeConverter extends EnumConverter<Mode> {

  @Override
  protected Class<Mode> getGenericClass() {
    return Mode.class;
  }
}
