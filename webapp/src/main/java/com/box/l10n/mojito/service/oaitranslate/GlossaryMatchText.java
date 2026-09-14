package com.box.l10n.mojito.service.oaitranslate;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text used for lexical glossary matching, with offsets back to the original source. */
record GlossaryMatchText(String text, int[] sourceOffsets) {

  private static final Pattern TAG =
      Pattern.compile(
          "</([A-Za-z][A-Za-z0-9:._-]*)\\s*>"
              + "|<([A-Za-z][A-Za-z0-9:._-]*)"
              + "(?:\\s+[A-Za-z_:][A-Za-z0-9:._-]*"
              + "(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?)*+\\s*/?>");

  private static final Set<String> BARRIER_TAGS =
      Set.of(
          "address",
          "article",
          "aside",
          "blockquote",
          "br",
          "dd",
          "details",
          "dialog",
          "div",
          "dl",
          "dt",
          "fieldset",
          "figcaption",
          "figure",
          "footer",
          "form",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "header",
          "hgroup",
          "hr",
          "img",
          "input",
          "li",
          "main",
          "nav",
          "ol",
          "p",
          "pre",
          "section",
          "table",
          "tbody",
          "td",
          "tfoot",
          "th",
          "thead",
          "tr",
          "ul");

  static GlossaryMatchText from(String source) {
    Matcher tags = TAG.matcher(source);
    if (!tags.find()) {
      return new GlossaryMatchText(source, null);
    }

    StringBuilder text = new StringBuilder(source.length());
    int[] sourceOffsets = new int[source.length()];
    int position = 0;
    do {
      while (position < tags.start()) {
        sourceOffsets[text.length()] = position;
        text.append(source.charAt(position++));
      }
      String tagName = tags.group(1) != null ? tags.group(1) : tags.group(2);
      if (source.charAt(tags.end() - 2) == '/'
          || BARRIER_TAGS.contains(tagName.toLowerCase(Locale.ROOT))) {
        // A block break or standalone placeholder must not join neighboring phrase fragments.
        sourceOffsets[text.length()] = tags.start();
        text.append('\0');
      }
      position = tags.end();
    } while (tags.find());
    while (position < source.length()) {
      sourceOffsets[text.length()] = position;
      text.append(source.charAt(position++));
    }
    return new GlossaryMatchText(text.toString(), sourceOffsets);
  }

  int sourceStartIndex(int startIndex) {
    return sourceOffsets == null ? startIndex : sourceOffsets[startIndex];
  }

  int sourceEndIndex(int endIndex) {
    return sourceOffsets == null ? endIndex : sourceOffsets[endIndex - 1] + 1;
  }
}
