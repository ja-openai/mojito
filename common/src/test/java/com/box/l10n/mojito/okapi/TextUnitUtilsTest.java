package com.box.l10n.mojito.okapi;

import static org.junit.Assert.*;

import com.box.l10n.mojito.okapi.filters.AndroidAutoDetectAnchorTagsAnnotation;
import net.sf.okapi.common.resource.Code;
import net.sf.okapi.common.resource.TextContainer;
import net.sf.okapi.common.resource.TextFragment;
import net.sf.okapi.common.resource.TextUnit;
import org.assertj.core.api.Assertions;
import org.junit.Test;

public class TextUnitUtilsTest {

  @Test
  public void getSourceAsCodedHtml() {
    TextUnitUtils textUnitUtils = new TextUnitUtils();
    TextUnit textUnit = getTestTextUnit();
    String sourceAsCodedHtml = textUnitUtils.getSourceAsCodedHtml(textUnit);
    Assertions.assertThat(sourceAsCodedHtml).isEqualTo("Hi <br id='p1'/>!");
  }

  @Test
  public void fromCodedHTML() {
    TextUnitUtils textUnitUtils = new TextUnitUtils();
    TextUnit textUnit = getTestTextUnit();
    TextFragment translationTextFragment =
        textUnitUtils.fromCodedHTML(textUnit, "<br id='p1'/>, bonjour !");
    Assertions.assertThat(translationTextFragment.toText()).isEqualTo("{username}, bonjour !");
  }

  @Test
  public void createTargetTextContainerRestoresAutoDetectedAnchorCodes() {
    TextUnitUtils textUnitUtils = new TextUnitUtils();
    TextUnit textUnit = textUnitWithAnchorCodes();
    textUnit.setAnnotation(
        AndroidAutoDetectAnchorTagsAnnotation.from(textUnit.getSource().getFirstContent()));

    TextContainer target =
        textUnitUtils.createTargetTextContainer(
            textUnit, "Lire <a href=\"https://example.com\">les conditions</a>");

    Assertions.assertThat(target.toString())
        .isEqualTo("Lire <a href=\"https://example.com\">les conditions</a>");
    Assertions.assertThat(target.getFirstContent().getCodes()).hasSize(2);
  }

  @Test
  public void createTargetTextContainerFailsClosedWhenAnchorChanges() {
    TextUnitUtils textUnitUtils = new TextUnitUtils();
    TextUnit textUnit = textUnitWithAnchorCodes();
    textUnit.setAnnotation(
        AndroidAutoDetectAnchorTagsAnnotation.from(textUnit.getSource().getFirstContent()));

    TextContainer target =
        textUnitUtils.createTargetTextContainer(
            textUnit, "Lire <a href=\"https://other.example.com\">les conditions</a>");

    Assertions.assertThat(target.getFirstContent().getCodes()).isEmpty();
  }

  private static TextUnit textUnitWithAnchorCodes() {
    TextFragment textFragment = new TextFragment();
    Code opening = new Code(TextFragment.TagType.OPENING, "a", "<a href=\"https://example.com\">");
    opening.setId(1);
    Code closing = new Code(TextFragment.TagType.CLOSING, "a", "</a>");
    closing.setId(1);
    textFragment.append("Read ").append(opening).append("terms").append(closing);
    TextUnit textUnit = new TextUnit();
    textUnit.setSourceContent(textFragment);
    return textUnit;
  }

  private static TextUnit getTestTextUnit() {
    TextFragment textFragment = new TextFragment();
    textFragment
        .append("Hi ")
        .append(new Code(TextFragment.TagType.PLACEHOLDER, "MF", "{username}"))
        .append("!");
    TextUnit textUnit = new TextUnit();
    textUnit.setSourceContent(textFragment);
    return textUnit;
  }
}
