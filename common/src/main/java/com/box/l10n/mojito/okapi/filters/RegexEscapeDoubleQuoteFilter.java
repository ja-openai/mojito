package com.box.l10n.mojito.okapi.filters;

import com.box.l10n.mojito.okapi.TextUnitUtils;
import net.sf.okapi.common.Event;
import net.sf.okapi.common.EventType;
import net.sf.okapi.common.encoder.EncoderManager;
import net.sf.okapi.common.resource.TextUnit;
import net.sf.okapi.filters.regex.RegexFilter;

/**
 * @author jyi
 */
public class RegexEscapeDoubleQuoteFilter extends RegexFilter {

  UnescapeUtils unescapeUtils;

  TextUnitUtils textUnitUtils;

  public RegexEscapeDoubleQuoteFilter() {
    this(new UnescapeUtils(), new TextUnitUtils());
  }

  public RegexEscapeDoubleQuoteFilter(UnescapeUtils unescapeUtils, TextUnitUtils textUnitUtils) {
    this.unescapeUtils = unescapeUtils;
    this.textUnitUtils = textUnitUtils;
  }

  @Override
  public Event next() {
    Event event = super.next();
    if (event.getEventType() == EventType.TEXT_UNIT) {
      // if source has escaped double-quotes, unescape
      TextUnit textUnit = (TextUnit) event.getTextUnit();
      String sourceString = textUnitUtils.getSourceAsString(textUnit);
      String unescapedSourceString = unescapeUtils.unescape(sourceString);
      textUnitUtils.replaceSourceString(textUnit, unescapedSourceString);
    }
    return event;
  }

  @Override
  public EncoderManager getEncoderManager() {
    EncoderManager encoderManager = super.getEncoderManager();
    if (encoderManager == null) {
      encoderManager = new EncoderManager();
    }
    encoderManager.setMapping(getMimeType(), "com.box.l10n.mojito.okapi.filters.SimpleEncoder");
    return encoderManager;
  }
}
