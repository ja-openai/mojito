package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CsvLocalizationFormatTest {

  @Test
  public void csvPreservesTrailingColumnsAndQuotesTranslationWhenRequired() {
    byte[] source =
        "id,Original,Old,Translator note,untouched\r\n".getBytes(StandardCharsets.UTF_8);
    byte[] localized =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.CSV, source, Map.of("id", "Bonjour, \"ami\""), List.of(), false);

    assertEquals(
        "id,Original,\"Bonjour, \"\"ami\"\"\",Translator note,untouched\r\n",
        new String(localized, StandardCharsets.UTF_8));
  }

  @Test
  public void missingTargetColumnIsInsertedWithoutChangingExistingBytes() {
    byte[] source = "Greeting only".getBytes(StandardCharsets.UTF_8);
    byte[] localized =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.CSV_ADOBE_MAGENTO,
            source,
            Map.of("Greeting only", "Bonjour"),
            List.of(),
            false);

    assertEquals("Greeting only,Bonjour", new String(localized, StandardCharsets.UTF_8));
  }

  @Test
  public void utf8BomAndUntouchedRowsSurviveMagentoLocalization() {
    byte[] content = "First,First\r\nSecond,Second".getBytes(StandardCharsets.UTF_8);
    byte[] source = new byte[content.length + 3];
    source[0] = (byte) 0xef;
    source[1] = (byte) 0xbb;
    source[2] = (byte) 0xbf;
    System.arraycopy(content, 0, source, 3, content.length);

    byte[] localized =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.CSV_ADOBE_MAGENTO,
            source,
            Map.of("First", "Premier"),
            List.of(),
            false);

    byte[] expected = "First,Premier\r\nSecond,Second".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(
        new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, java.util.Arrays.copyOf(localized, 3));
    assertArrayEquals(expected, java.util.Arrays.copyOfRange(localized, 3, localized.length));
  }

  @Test
  public void invalidQuotingAndDuplicateIdsHaveStableErrors() {
    assertEquals(
        "INVALID_CSV",
        assertThrows(
                LocalizationParseException.class,
                () ->
                    LocalizationFileConverters.parse(
                        LocalizationFileFormat.CSV, "id,\"never closed"))
            .code());
    assertEquals(
        "DUPLICATE_MESSAGE_ID",
        assertThrows(
                LocalizationParseException.class,
                () ->
                    LocalizationFileConverters.parse(
                        LocalizationFileFormat.CSV_ADOBE_MAGENTO, "Repeat,one\nRepeat,two"))
            .code());
  }

  @Test
  public void normalizedWritersRoundTripQuotedIdsSourcesAndComments() {
    for (LocalizationFileFormat format :
        List.of(LocalizationFileFormat.CSV, LocalizationFileFormat.CSV_ADOBE_MAGENTO)) {
      String source =
          format == LocalizationFileFormat.CSV
              ? "entry,\"Say \"\"hello\"\"\",unused,\"Translator note\"\n"
              : "\"Say \"\"hello\"\"\",unused\n";
      LocalizationCatalog original = LocalizationFileConverters.parse(format, source);
      LocalizationCatalog reparsed =
          LocalizationFileConverters.parse(
              format, LocalizationFileConverters.write(format, original));

      assertEquals(original.messages(), reparsed.messages());
    }
  }

  @Test
  public void importsLocalizedTargetColumnsWithoutChangingStableIdsOrComments() {
    LocalizationCatalog csv =
        LocalizationFileConverters.parseForMojitoImport(
            LocalizationFileFormat.CSV,
            ("id,Original,\"Bonjour, ami\",Translator note\n"
                    + "missing,Untranslated\n"
                    + "empty,Original,,Ignored\n")
                .getBytes(StandardCharsets.UTF_8),
            List.of("targetComment=Vendor reviewed"),
            "fr-FR",
            false);
    assertEquals(1, csv.messages().size());
    assertEquals("Bonjour, ami", csv.messages().get("id").defaultMessage());
    assertEquals("Translator note", csv.messages().get("id").description());
    assertEquals("Vendor reviewed", csv.messages().get("id").metadata().get("mojitoTargetComment"));

    LocalizationCatalog magento =
        LocalizationFileConverters.parseForMojitoImport(
            LocalizationFileFormat.CSV_ADOBE_MAGENTO,
            "\"Hello, friend\",\"Bonjour, ami\"\n".getBytes(StandardCharsets.UTF_8),
            List.of(),
            "fr-FR",
            false);
    assertEquals("Bonjour, ami", magento.messages().get("\"Hello, friend\"").defaultMessage());
  }
}
