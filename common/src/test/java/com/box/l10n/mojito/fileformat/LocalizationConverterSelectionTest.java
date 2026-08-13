package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.box.l10n.mojito.okapi.FilterConfigIdOverride;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LocalizationConverterSelectionTest {

  @Test
  public void usePortableAddsMarkerWithoutDroppingExistingOptions() {
    assertEquals(
        List.of(LocalizationConverterSelection.PORTABLE_OPTION),
        LocalizationConverterSelection.usePortable(null));
    assertEquals(
        List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION),
        LocalizationConverterSelection.usePortable(List.of("escapeMode=legacy")));
  }

  @Test
  public void usePortableDoesNotDuplicateMarker() {
    assertEquals(
        List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION),
        LocalizationConverterSelection.usePortable(
            List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION)));
  }

  @Test
  public void usePortableReturnsImmutableSnapshot() {
    List<String> source = new ArrayList<>(List.of("escapeMode=legacy"));
    List<String> selected = LocalizationConverterSelection.usePortable(source);

    source.add("postProcessIndent=4");

    assertEquals(
        List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION), selected);
    assertImmutable(selected);
  }

  @Test
  public void isPortableOnlyMatchesExplicitMarker() {
    assertFalse(LocalizationConverterSelection.isPortable(null));
    assertFalse(LocalizationConverterSelection.isPortable(List.of("escapeMode=legacy")));
    assertTrue(
        LocalizationConverterSelection.isPortable(
            List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION)));
  }

  @Test
  public void backendDefaultOnlySelectsSupportedPortableFormats() {
    assertFalse(LocalizationConverterSelection.isPortable(null, false, "messages.json"));
    assertFalse(LocalizationConverterSelection.isPortable(null, true, null));
    assertTrue(LocalizationConverterSelection.isPortable(null, true, "Messages.JSON"));
    assertTrue(LocalizationConverterSelection.isPortable(null, true, "res/values/strings.xml"));
    assertFalse(LocalizationConverterSelection.isPortable(null, true, "messages.xliff"));
    assertTrue(
        LocalizationConverterSelection.isPortable(
            List.of(LocalizationConverterSelection.PORTABLE_OPTION), false, "messages.xliff"));
    assertTrue(
        LocalizationConverterSelection.isPortable(
            List.of(LocalizationConverterSelection.PORTABLE_OPTION), false, null));
  }

  @Test
  public void platformOptionsStripPortableMarkerOnly() {
    assertNull(LocalizationConverterSelection.platformOptions(null));
    assertEquals(
        List.of("escapeMode=legacy", "pluralForms=strict"),
        LocalizationConverterSelection.platformOptions(
            List.of(
                "escapeMode=legacy",
                LocalizationConverterSelection.PORTABLE_OPTION,
                "pluralForms=strict")));
  }

  @Test
  public void platformOptionsReturnImmutableSnapshot() {
    List<String> source =
        new ArrayList<>(
            List.of("escapeMode=legacy", LocalizationConverterSelection.PORTABLE_OPTION));
    List<String> platformOptions = LocalizationConverterSelection.platformOptions(source);

    source.add("postProcessIndent=4");

    assertEquals(List.of("escapeMode=legacy"), platformOptions);
    assertImmutable(platformOptions);
  }

  @Test
  public void formatSelectionIsCaseInsensitiveAndIndependentFromOverrides() {
    assertEquals(
        LocalizationFileFormat.FORMATJS_JSON,
        LocalizationConverterSelection.format("Messages.JSON", null));
    assertEquals(
        LocalizationFileFormat.JAVA_PROPERTIES,
        LocalizationConverterSelection.format(
            "messages.properties", FilterConfigIdOverride.PROPERTIES_JAVA));
    assertEquals(
        LocalizationFileFormat.JAVASCRIPT,
        LocalizationConverterSelection.format("Messages.JS", null));
    assertEquals(
        LocalizationFileFormat.TYPESCRIPT,
        LocalizationConverterSelection.format("Messages.TS", null));
    assertEquals(
        LocalizationFileFormat.RESX, LocalizationConverterSelection.format("Messages.RESX", null));
    assertEquals(
        LocalizationFileFormat.RESX, LocalizationConverterSelection.format("Messages.resw", null));
    assertEquals(
        LocalizationFileFormat.XTB, LocalizationConverterSelection.format("Messages.XTB", null));
    assertEquals(
        LocalizationFileFormat.CSV, LocalizationConverterSelection.format("Messages.CSV", null));
    assertEquals(
        LocalizationFileFormat.CSV_ADOBE_MAGENTO,
        LocalizationConverterSelection.format(
            "i18n/en_US.csv", FilterConfigIdOverride.CSV_ADOBE_MAGENTO));
  }

  @Test
  public void unsupportedFormatsIncludeOverrideContextForDiagnostics() {
    try {
      LocalizationConverterSelection.format(
          "messages.unsupported", FilterConfigIdOverride.CSV_ADOBE_MAGENTO);
    } catch (LocalizationParseException unsupported) {
      assertEquals("UNSUPPORTED_PORTABLE_FORMAT", unsupported.code());
      assertTrue(unsupported.getMessage().contains("messages.unsupported"));
      assertTrue(unsupported.getMessage().contains("CSV_ADOBE_MAGENTO"));
      return;
    }
    throw new AssertionError("Expected unsupported portable format");
  }

  @Test
  public void unsupportedFormatsHandleMissingAssetPath() {
    try {
      LocalizationConverterSelection.format(null, null);
    } catch (LocalizationParseException unsupported) {
      assertEquals("UNSUPPORTED_PORTABLE_FORMAT", unsupported.code());
      assertTrue(unsupported.getMessage().contains("null"));
      return;
    }
    throw new AssertionError("Expected unsupported portable format");
  }

  private static void assertImmutable(List<String> options) {
    try {
      options.add("postProcessIndent=4");
    } catch (UnsupportedOperationException expected) {
      return;
    }
    fail("Expected immutable options");
  }
}
