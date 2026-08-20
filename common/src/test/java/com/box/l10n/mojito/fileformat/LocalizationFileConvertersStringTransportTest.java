package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LocalizationFileConvertersStringTransportTest {

  @Test
  public void reconstructsUtf16XmlBytesAfterStringTransport() {
    String source =
        "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"
            + "<resources><string name=\"welcome\">Welcome</string></resources>";

    byte[] encoded =
        LocalizationFileConverters.encodeStringTransport(LocalizationFileFormat.ANDROID, source);

    assertEquals((byte) 0xfe, encoded[0]);
    assertEquals((byte) 0xff, encoded[1]);
    LocalizationCatalog catalog =
        LocalizationFileConverters.parseForMojito(
            LocalizationFileFormat.ANDROID, encoded, List.of());
    assertEquals("Welcome", catalog.messages().get("welcome").defaultMessage());

    byte[] localized =
        LocalizationFileConverters.localizeForMojito(
            LocalizationFileFormat.ANDROID,
            encoded,
            Map.of("welcome", "Bienvenue"),
            List.of(),
            false);
    String transported =
        LocalizationFileConverters.decodeStringTransport(LocalizationFileFormat.ANDROID, localized);
    assertTrue(transported.contains("encoding=\"UTF-16\""));
    assertTrue(transported.contains("Bienvenue"));
  }

  @Test
  public void leavesUtf8StringTransportUnchanged() {
    String source =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><resources><string name=\"x\">é</string></resources>";

    byte[] encoded =
        LocalizationFileConverters.encodeStringTransport(LocalizationFileFormat.ANDROID, source);

    assertEquals(
        source,
        LocalizationFileConverters.decodeStringTransport(LocalizationFileFormat.ANDROID, encoded));
  }
}
