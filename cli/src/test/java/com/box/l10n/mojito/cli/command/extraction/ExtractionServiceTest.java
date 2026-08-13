package com.box.l10n.mojito.cli.command.extraction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.cli.command.CommandHelper;
import com.box.l10n.mojito.cli.filefinder.FileMatch;
import com.box.l10n.mojito.cli.filefinder.file.FileType;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.box.l10n.mojito.okapi.extractor.AssetExtractor;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ExtractionServiceTest {

  @Mock CommandHelper commandHelper;
  @Mock AssetExtractor assetExtractor;
  @Mock FileMatch sourceFileMatch;
  @Mock FileType fileType;

  @InjectMocks ExtractionService extractionService;

  @Before
  public void setUp() {
    when(sourceFileMatch.getSourcePath()).thenReturn("demo.properties");
    when(sourceFileMatch.getFileType()).thenReturn(fileType);
    when(commandHelper.getFileContentWithXcodePatch(sourceFileMatch))
        .thenReturn("welcome=Harbor\n");
  }

  @Test
  public void explicitPortableSelectionAvoidsOkapiInTheCliProcess() {
    List<AssetExtractorTextUnit> extracted =
        extractionService.getExtractionTextUnitsForSourceFileMatch(
            sourceFileMatch, List.of(LocalizationConverterSelection.PORTABLE_OPTION));

    assertEquals("welcome", extracted.get(0).getName());
    assertEquals("Harbor", extracted.get(0).getSource());
    verifyNoInteractions(assetExtractor);
  }

  @Test
  public void backendPropertyAlsoSelectsPortableLocalCliExtraction() {
    extractionService.portableConverter = true;

    List<AssetExtractorTextUnit> extracted =
        extractionService.getExtractionTextUnitsForSourceFileMatch(sourceFileMatch, null);

    assertEquals("welcome", extracted.get(0).getName());
    assertEquals("Harbor", extracted.get(0).getSource());
    verifyNoInteractions(assetExtractor);
  }

  @Test
  public void okapiRemainsTheDefaultForLocalCliExtraction() throws Exception {
    List<AssetExtractorTextUnit> expected = List.of(new AssetExtractorTextUnit());
    when(assetExtractor.getAssetExtractorTextUnitsForAsset(
            "demo.properties", "welcome=Harbor\n", null, null))
        .thenReturn(expected);

    assertSame(
        expected,
        extractionService.getExtractionTextUnitsForSourceFileMatch(sourceFileMatch, null));
  }
}
