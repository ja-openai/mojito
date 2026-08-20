package com.box.l10n.mojito.service.tm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.okapi.InheritanceMode;
import com.box.l10n.mojito.okapi.Status;
import java.util.List;
import org.junit.Test;

public class TMServicePortableSelectionTest {

  @Test
  public void portableMarkerIsStrippedBeforeGeneratingLocalizedContent() throws Exception {
    Asset asset = new Asset();
    asset.setPath("path/to/fake/res/strings.xml");

    String localized =
        new TMService()
            .generateLocalized(
                asset,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources/>\n",
                repositoryLocale(),
                null,
                null,
                List.of(LocalizationConverterSelection.PORTABLE_OPTION),
                Status.ALL,
                InheritanceMode.USE_PARENT,
                null);

    assertTrue(localized.contains("<resources"));
  }

  @Test
  public void backendDefaultSelectsPortableWithoutChangingFilterOptions() throws Exception {
    Asset asset = new Asset();
    asset.setPath("path/to/fake/res/strings.xml");
    TMService tmService = new TMService();
    tmService.portableConverter = true;

    String localized =
        tmService.generateLocalized(
            asset,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<resources/>\n",
            repositoryLocale(),
            null,
            null,
            null,
            Status.ALL,
            InheritanceMode.USE_PARENT,
            null);

    assertTrue(localized.contains("<resources"));
  }

  @Test
  public void explicitPortableUnsupportedFormatFailsBeforeGeneratingLocalizedContent()
      throws Exception {
    Asset asset = new Asset();
    asset.setPath("path/to/fake/source.xliff");

    try {
      new TMService()
          .generateLocalized(
              asset,
              "<xliff/>",
              repositoryLocale(),
              null,
              null,
              List.of(LocalizationConverterSelection.PORTABLE_OPTION),
              Status.ALL,
              InheritanceMode.USE_PARENT,
              null);
    } catch (LocalizationParseException unsupported) {
      assertEquals("UNSUPPORTED_PORTABLE_FORMAT", unsupported.code());
      return;
    }
    fail("Expected unsupported portable format");
  }

  @Test
  public void sourceLessAugmentationRunsBeforePortableGeneration() throws Exception {
    Asset asset = new Asset();
    asset.setPath("path/to/messages.json");
    String source = "{\"local\":\"Source\"}";
    List<String> filterOptions = List.of(LocalizationConverterSelection.PORTABLE_OPTION);
    List<String> branches = List.of("authoring/checkout");
    NoSourceJsonAugmenter noSourceJsonAugmenter = mock(NoSourceJsonAugmenter.class);
    when(noSourceJsonAugmenter.augment(asset, source, null, filterOptions, branches))
        .thenReturn("{}");
    TMService tmService = new TMService();
    tmService.noSourceJsonAugmenter = noSourceJsonAugmenter;

    String localized =
        tmService.generateLocalized(
            asset,
            source,
            repositoryLocale(),
            null,
            null,
            filterOptions,
            Status.ALL,
            InheritanceMode.USE_PARENT,
            null,
            true,
            branches);

    assertEquals("{}", localized);
    verify(noSourceJsonAugmenter).augment(asset, source, null, filterOptions, branches);
  }

  private static RepositoryLocale repositoryLocale() {
    Locale locale = new Locale();
    locale.setBcp47Tag("fr-FR");
    RepositoryLocale repositoryLocale = new RepositoryLocale();
    repositoryLocale.setLocale(locale);
    return repositoryLocale;
  }
}
