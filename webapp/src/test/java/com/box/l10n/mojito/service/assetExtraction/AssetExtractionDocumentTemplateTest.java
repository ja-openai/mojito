package com.box.l10n.mojito.service.assetExtraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetContent;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.assetcontent.AssetContentService;
import org.junit.Test;

public class AssetExtractionDocumentTemplateTest {
  private final AssetExtractionService service = new AssetExtractionService();

  @Test
  public void extractorReadsExternalContentThroughAssetContentService() throws Exception {
    AssetContent metadata = metadata();
    service.assetContentService = mock(AssetContentService.class);
    when(service.assetContentService.readContent(metadata))
        .thenReturn("{/* mojito-id: title */}\n# Welcome\n");

    var units = service.getExtractorTextUnitsForAssetContent(metadata, null, null);

    assertThat(units).hasSize(1);
    assertThat(units.getFirst().getName()).isEqualTo("title");
    assertThat(units.getFirst().getSource()).isEqualTo("Welcome");
    verify(service.assetContentService).readContent(metadata);
    assertThat(metadata.getContent()).isEmpty();
  }

  @Test
  public void catalogOnlyExtractionAlsoReadsExternalPayload() throws Exception {
    AssetContent metadata = metadata();
    metadata.setExtractedContent(true);
    service.objectMapper = new ObjectMapper();
    service.assetContentService = mock(AssetContentService.class);
    when(service.assetContentService.readContent(metadata))
        .thenReturn("[{\"name\":\"title\",\"source\":\"Welcome\"}]");

    var units = service.getExtractorTextUnitsForAssetContent(metadata, null, null);

    assertThat(units).hasSize(1);
    assertThat(units.getFirst().getName()).isEqualTo("title");
    assertThat(units.getFirst().getSource()).isEqualTo("Welcome");
    verify(service.assetContentService).readContent(metadata);
    assertThat(metadata.getContent()).isEmpty();
  }

  private AssetContent metadata() {
    Asset asset = new Asset();
    asset.setPath("page.mdx");
    AssetContent content = new AssetContent();
    content.setId(31L);
    content.setAsset(asset);
    content.setContent("");
    return content;
  }
}
