package com.box.l10n.mojito.service.assetExtraction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetContent;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.localtm.merger.BranchStateTextUnit;
import com.box.l10n.mojito.okapi.extractor.AssetExtractor;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;

public class AssetExtractionServicePortableSelectionTest {

  private static final String ANDROID_STRINGS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<resources>\n"
          + "  <string name=\"Test\" description=\"Test label\">You must test your changes</string>\n"
          + "</resources>";

  @Test
  public void portableMarkerIsStrippedBeforeParsingAssetContent() throws Exception {
    AssetExtractionService assetExtractionService = new AssetExtractionService();

    List<AssetExtractorTextUnit> textUnits =
        assetExtractionService.getExtractorTextUnitsForAssetContent(
            assetContent("path/to/fake/res/strings.xml", ANDROID_STRINGS),
            List.of(LocalizationConverterSelection.PORTABLE_OPTION),
            null);

    assertEquals(1, textUnits.size());
    assertEquals("Test", textUnits.get(0).getName());
    assertEquals("You must test your changes", textUnits.get(0).getSource());
    assertEquals("Test label", textUnits.get(0).getComments());
  }

  @Test
  public void backendDefaultSelectsPortableWithoutChangingFilterOptions() throws Exception {
    AssetExtractionService assetExtractionService = new AssetExtractionService();
    assetExtractionService.portableConverter = true;

    List<AssetExtractorTextUnit> textUnits =
        assetExtractionService.getExtractorTextUnitsForAssetContent(
            assetContent("path/to/fake/res/strings.xml", ANDROID_STRINGS), null, null);

    assertEquals(1, textUnits.size());
    assertEquals("Test", textUnits.get(0).getName());
    assertEquals("You must test your changes", textUnits.get(0).getSource());
    assertEquals("Test label", textUnits.get(0).getComments());
  }

  @Test
  public void portableJsonDescriptionsContainDecodedJsonCharacters() throws Exception {
    AssetExtractionService assetExtractionService = new AssetExtractionService();
    String content =
        "{\"quoted\":{\"defaultMessage\":\"Hello\",\"description\":\"A \\\"quoted\\\" label\\nnext line\"}}";

    List<AssetExtractorTextUnit> textUnits =
        assetExtractionService.getExtractorTextUnitsForAssetContent(
            assetContent("messages.json", content),
            List.of(
                LocalizationConverterSelection.PORTABLE_OPTION,
                "noteKeyPattern=description",
                "extractAllPairs=false",
                "exceptions=defaultMessage",
                "removeKeySuffix=/defaultMessage"),
            null);

    assertEquals(1, textUnits.size());
    assertEquals("A \"quoted\" label\nnext line", textUnits.getFirst().getComments());
  }

  @Test
  public void legacyJsonCommentMigrationRequiresExactNameSourceAndEscapedComment() {
    AssetExtractionService assetExtractionService = new AssetExtractionService();
    assetExtractionService.objectMapper = new ObjectMapper();
    BranchStateTextUnit corrected =
        BranchStateTextUnit.builder()
            .tmTextUnitId(20L)
            .name("harbor.label")
            .source("Open harbor")
            .comments("A \"quoted\" note\nsecond line")
            .build();
    TextUnitDTO legacy =
        usedTextUnit(10L, "harbor.label", "Open harbor", "A \\\"quoted\\\" note\\nsecond line");

    ImmutableList<TextUnitDTOMatch> matches =
        assetExtractionService.getLegacyJsonCommentMigrationMatches(
            ImmutableList.of(corrected), ImmutableList.of(legacy));

    assertEquals(1, matches.size());
    assertEquals(Long.valueOf(10L), matches.getFirst().match().getTmTextUnitId());
    assertFalse(matches.getFirst().translationNeededIfUniqueMatch());
  }

  @Test
  public void legacyJsonCommentMigrationRejectsChangedSourceAmbiguousAndOrdinaryComments() {
    AssetExtractionService assetExtractionService = new AssetExtractionService();
    assetExtractionService.objectMapper = new ObjectMapper();
    BranchStateTextUnit corrected =
        BranchStateTextUnit.builder()
            .tmTextUnitId(20L)
            .name("harbor.label")
            .source("Open harbor")
            .comments("A \"quoted\" note")
            .build();
    TextUnitDTO changedSource =
        usedTextUnit(10L, "harbor.label", "Closed harbor", "A \\\"quoted\\\" note");
    TextUnitDTO first = usedTextUnit(11L, "harbor.label", "Open harbor", "A \\\"quoted\\\" note");
    TextUnitDTO duplicate =
        usedTextUnit(12L, "harbor.label", "Open harbor", "A \\\"quoted\\\" note");
    BranchStateTextUnit ordinary =
        BranchStateTextUnit.builder()
            .tmTextUnitId(21L)
            .name("ordinary.label")
            .source("Open harbor")
            .comments("Ordinary note")
            .build();

    assertEquals(
        ImmutableList.of(),
        assetExtractionService.getLegacyJsonCommentMigrationMatches(
            ImmutableList.of(corrected), ImmutableList.of(changedSource)));
    assertEquals(
        ImmutableList.of(),
        assetExtractionService.getLegacyJsonCommentMigrationMatches(
            ImmutableList.of(corrected), ImmutableList.of(first, duplicate)));
    assertEquals(
        ImmutableList.of(),
        assetExtractionService.getLegacyJsonCommentMigrationMatches(
            ImmutableList.of(ordinary),
            ImmutableList.of(usedTextUnit(13L, "ordinary.label", "Open harbor", "Ordinary note"))));
  }

  @Test
  public void backendDefaultUnsupportedFormatUsesOkapiExtractor() throws Exception {
    AssetExtractor assetExtractor = mock(AssetExtractor.class);
    when(assetExtractor.getAssetExtractorTextUnitsForAsset(
            "path/to/fake/source.xliff", "<xliff/>", null, null))
        .thenReturn(List.of());

    AssetExtractionService assetExtractionService = new AssetExtractionService();
    assetExtractionService.portableConverter = true;
    assetExtractionService.assetExtractor = assetExtractor;

    assertEquals(
        List.of(),
        assetExtractionService.getExtractorTextUnitsForAssetContent(
            assetContent("path/to/fake/source.xliff", "<xliff/>"), null, null));
    verify(assetExtractor)
        .getAssetExtractorTextUnitsForAsset("path/to/fake/source.xliff", "<xliff/>", null, null);
  }

  @Test
  public void extractedContentBypassesPortableAndOkapiExtraction() throws Exception {
    AssetExtractor assetExtractor = mock(AssetExtractor.class);
    AssetExtractionService assetExtractionService = new AssetExtractionService();
    assetExtractionService.portableConverter = true;
    assetExtractionService.assetExtractor = assetExtractor;
    assetExtractionService.objectMapper = new ObjectMapper();

    AssetExtractorTextUnit extracted = textUnit("Stored", "Already extracted", "Stored comment");
    AssetContent assetContent =
        assetContent(
            "path/to/fake/res/strings.xml",
            assetExtractionService.objectMapper.writeValueAsStringUnchecked(List.of(extracted)));
    assetContent.setExtractedContent(true);

    List<AssetExtractorTextUnit> textUnits =
        assetExtractionService.getExtractorTextUnitsForAssetContent(
            assetContent, List.of(LocalizationConverterSelection.PORTABLE_OPTION), null);

    assertEquals(1, textUnits.size());
    assertEquals("Stored", textUnits.get(0).getName());
    assertEquals("Already extracted", textUnits.get(0).getSource());
    assertEquals("Stored comment", textUnits.get(0).getComments());
    verifyNoInteractions(assetExtractor);
  }

  @Test
  public void explicitPortableUnsupportedFormatFailsBeforeOkapiExtraction() throws Exception {
    AssetExtractionService assetExtractionService = new AssetExtractionService();

    try {
      assetExtractionService.getExtractorTextUnitsForAssetContent(
          assetContent("path/to/fake/source.xliff", "<xliff/>"),
          List.of(LocalizationConverterSelection.PORTABLE_OPTION),
          null);
    } catch (LocalizationParseException unsupported) {
      assertEquals("UNSUPPORTED_PORTABLE_FORMAT", unsupported.code());
      return;
    }
    fail("Expected unsupported portable format");
  }

  private static AssetContent assetContent(String path, String content) {
    Asset asset = new Asset();
    asset.setPath(path);
    AssetContent assetContent = new AssetContent();
    assetContent.setAsset(asset);
    assetContent.setContent(content);
    return assetContent;
  }

  private static AssetExtractorTextUnit textUnit(String name, String source, String comments) {
    AssetExtractorTextUnit textUnit = new AssetExtractorTextUnit();
    textUnit.setName(name);
    textUnit.setSource(source);
    textUnit.setComments(comments);
    return textUnit;
  }

  private static TextUnitDTO usedTextUnit(Long id, String name, String source, String comment) {
    TextUnitDTO textUnit = new TextUnitDTO();
    textUnit.setTmTextUnitId(id);
    textUnit.setName(name);
    textUnit.setSource(source);
    textUnit.setComment(comment);
    textUnit.setAssetExtractionId(1L);
    textUnit.setLastSuccessfulAssetExtractionId(1L);
    return textUnit;
  }
}
