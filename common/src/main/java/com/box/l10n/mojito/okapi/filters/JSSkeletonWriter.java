package com.box.l10n.mojito.okapi.filters;

import net.sf.okapi.common.resource.ITextUnit;
import net.sf.okapi.common.resource.Property;
import net.sf.okapi.common.skeleton.GenericSkeletonWriter;

/**
 * @author jyi
 */
public class JSSkeletonWriter extends GenericSkeletonWriter {

  UnescapeUtils unescapeUtils;

  public JSSkeletonWriter() {
    this(new UnescapeUtils());
  }

  public JSSkeletonWriter(UnescapeUtils unescapeUtils) {
    this.unescapeUtils = unescapeUtils;
  }

  @Override
  public String processTextUnit(ITextUnit resource) {
    String string = super.processTextUnit(resource);
    Property template = resource.getProperty("template");
    if (template == null) {
      // unescape backquote when the string is double-quoted
      string = unescapeUtils.replaceEscapedBackquotes(string);

    } else {
      // unescape newline when the string is template string
      string = unescapeUtils.replaceEscapedLineFeed(string);
    }
    return string;
  }
}
