package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class GettextImportTest {

  @Test
  public void japanesePluralRetainsSourceOwnedTranslationIdentities() {
    String source =
        """
        msgid ""
        msgstr ""
        "Plural-Forms: nplurals=2; plural=(n != 1);\\n"

        msgctxt "vessel"
        msgid "%d boat"
        msgid_plural "%d boats"
        msgstr[0] ""
        msgstr[1] ""
        """;
    String translated =
        """
        msgid ""
        msgstr ""
        "Language: \\n"
        "Plural-Forms: nplurals=1; plural=0;\\n"

        msgctxt "vessel"
        msgid "%d boat"
        msgid_plural "%d boats"
        msgstr[0] "%d 艇"
        """;
    LocalizationCatalog original =
        LocalizationFileConverters.parseForMojito(
            LocalizationFileFormat.GETTEXT_PO, source.getBytes(StandardCharsets.UTF_8), List.of());
    LocalizationCatalog imported =
        LocalizationFileConverters.parseForMojitoImport(
            LocalizationFileFormat.GETTEXT_PO,
            translated.getBytes(StandardCharsets.UTF_8),
            List.of(),
            "ja-JP",
            true);
    List<String> originalIds =
        LocalizationShadowComparator.projectTextUnitsWithIds(original).stream()
            .map(unit -> unit.textUnit().getName())
            .toList();
    List<String> importedIds =
        LocalizationShadowComparator.projectTextUnitsWithIds(imported).stream()
            .map(unit -> unit.textUnit().getName())
            .toList();

    assertEquals(originalIds, importedIds);
    assertEquals("ja-JP", imported.locale());
    assertEquals("{arg0} 艇", imported.messages().get("vessel").variants().get("other"));
  }

  @Test
  public void frenchPluralKeepsEveryCategoryRepresentedByANativeIndex() {
    String translated =
        """
        msgid ""
        msgstr ""
        "Language: \\n"
        "Plural-Forms: nplurals=2; plural=n>1;\\n"

        msgctxt "vessel"
        msgid "%d boat"
        msgid_plural "%d boats"
        msgstr[0] "%d bateau"
        msgstr[1] "%d bateaux"
        """;
    LocalizationCatalog imported =
        LocalizationFileConverters.parseForMojitoImport(
            LocalizationFileFormat.GETTEXT_PO,
            translated.getBytes(StandardCharsets.UTF_8),
            List.of(),
            "fr-FR",
            true);

    assertEquals("{arg0} bateaux", imported.messages().get("vessel").variants().get("other"));
    assertEquals("{arg0} bateaux", imported.messages().get("vessel").variants().get("many"));
  }
}
