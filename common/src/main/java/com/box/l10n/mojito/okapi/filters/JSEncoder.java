package com.box.l10n.mojito.okapi.filters;

import net.sf.okapi.common.encoder.EncoderContext;

/**
 * @author jyi
 */
public class JSEncoder extends SimpleEncoder {

  @Override
  public String encode(char value, EncoderContext context) {
    if (value == '`') {
      return "\\`";
    }
    return super.encode(value, context);
  }
}
