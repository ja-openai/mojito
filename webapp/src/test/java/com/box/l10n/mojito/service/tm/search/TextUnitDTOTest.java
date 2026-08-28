package com.box.l10n.mojito.service.tm.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class TextUnitDTOTest {

  @Test
  public void infersMf2MessageFormatFromStrictDeclarations() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();

    textUnitDTO.setSource(".input {$name :string}\n{{Hello {$name}}}");

    assertThat(textUnitDTO.getMessageFormat()).isEqualTo("MF2");
  }

  @Test
  public void infersMf2MessageFormatFromBomPrefixedMatchDeclaration() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();

    textUnitDTO.setSource("\uFEFF.match $count\n* {{Files}}");

    assertThat(textUnitDTO.getMessageFormat()).isEqualTo("MF2");
  }

  @Test
  public void doesNotInferMf2FromIcuOrDocumentationText() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();

    textUnitDTO.setSource("{count, plural, one {One file} other {# files}}");
    assertThat(textUnitDTO.getMessageFormat()).isNull();

    textUnitDTO.setSource("Use .input {$count} in the docs.");
    assertThat(textUnitDTO.getMessageFormat()).isNull();

    textUnitDTO.setSource("Instructions:\n.input {$count}\nCopy that example.");
    assertThat(textUnitDTO.getMessageFormat()).isNull();
  }

  @Test
  public void infersMf2FromTheSourceAssetPathWhenTheMessageIsPlain() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();

    textUnitDTO.setSource("Hello");
    textUnitDTO.setAssetPath("catalogs/messages.MF2");

    assertThat(textUnitDTO.getMessageFormat()).isEqualTo("MF2");
  }

  @Test
  public void doesNotInferMf2FromSimilarAssetSuffixes() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();

    textUnitDTO.setSource("Hello");
    textUnitDTO.setAssetPath("catalogs/messages.mf2.json");
    assertThat(textUnitDTO.getMessageFormat()).isNull();

    textUnitDTO.setAssetPath("catalogs/messages.mf20");
    assertThat(textUnitDTO.getMessageFormat()).isNull();
  }

  @Test
  public void normalizesExplicitMessageFormatMetadata() {
    TextUnitDTO textUnitDTO = new TextUnitDTO();

    textUnitDTO.setMessageFormat(" mf2 ");
    assertThat(textUnitDTO.getMessageFormat()).isEqualTo("MF2");

    textUnitDTO.setMessageFormat("icu");
    assertThat(textUnitDTO.getMessageFormat()).isNull();
  }
}
