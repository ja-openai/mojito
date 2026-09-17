package com.box.l10n.mojito.service.assetcontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.AssetContent;
import com.box.l10n.mojito.entity.Branch;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;

public class AssetContentServiceBlobTest {
  private final AssetContentService service = new AssetContentService();
  private final AssetContentRepository metadata = mock(AssetContentRepository.class);
  private final AssetContentBlobStorage blobs = mock(AssetContentBlobStorage.class);

  @Before
  public void setUp() {
    service.assetContentRepository = metadata;
    service.assetContentBlobStorage = blobs;
    when(metadata.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void mdxSourcePayloadIsUploadedBeforeOnlyMetadataIsSaved() {
    String source = "# Welcome\n\nA full document.\n";
    String md5 = DigestUtils.md5Hex(source);
    AssetContent stored = service.createAssetContent(asset("page.mdx"), source, false, branch());

    assertThat(stored.getContent()).isEmpty();
    assertThat(stored.getContentMd5()).isEqualTo(md5);
    var order = inOrder(blobs, metadata);
    order.verify(blobs).put(7L, 3L, md5, false, source);
    order.verify(metadata).save(stored);
  }

  @Test
  public void catalogOnlyMdxPayloadAlsoNeverEntersDatabaseContent() {
    String catalog = "[{\"name\":\"title\",\"source\":\"Welcome\"}]";
    AssetContent stored = service.createAssetContent(asset("PAGE.MDX"), catalog, true, branch());

    assertThat(stored.getContent()).isEmpty();
    assertThat(stored.isExtractedContent()).isTrue();
    verify(blobs).put(7L, 3L, DigestUtils.md5Hex(catalog), true, catalog);
  }

  @Test
  public void failedBlobUploadDoesNotPersistPayloadOrMetadata() {
    doThrow(new IllegalStateException("storage unavailable"))
        .when(blobs)
        .put(7L, 3L, DigestUtils.md5Hex("# Title"), false, "# Title");

    assertThatThrownBy(
            () -> service.createAssetContent(asset("page.mdx"), "# Title", false, branch()))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(metadata);
  }

  @Test
  public void sourceReadDerivesBlobKeyFromExistingMetadata() {
    AssetContent stored = externalMetadata("# Title");
    when(blobs.get(7L, 3L, stored.getContentMd5(), false)).thenReturn(Optional.of("# Title"));
    assertThat(service.readContent(stored)).isEqualTo("# Title");
    verify(blobs).get(7L, 3L, stored.getContentMd5(), false);
  }

  @Test
  public void missingBlobDoesNotFallbackToLegacyDatabasePayload() {
    AssetContent stored = externalMetadata("# Title");
    stored.setContent("Legacy database copy");
    when(blobs.get(7L, 3L, stored.getContentMd5(), false)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.readContent(stored))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  public void catalogReadUsesSeparateConvention() {
    AssetContent stored = externalMetadata("[]");
    stored.setExtractedContent(true);
    when(blobs.get(7L, 3L, stored.getContentMd5(), true)).thenReturn(Optional.of("[]"));
    assertThat(service.readContent(stored)).isEqualTo("[]");
    verify(blobs).get(7L, 3L, stored.getContentMd5(), true);
  }

  @Test
  public void existingNonMdxStorageAndReadsRemainCompatible() {
    String content = "{\"title\":\"Welcome\"}";
    AssetContent stored =
        service.createAssetContent(asset("messages.json"), content, false, branch());

    assertThat(stored.getContent()).isEqualTo(content);
    assertThat(service.readContent(stored)).isEqualTo(content);
    verifyNoInteractions(blobs);
  }

  @Test
  public void mf2CatalogPayloadUsesTheSameExternalConventionAndNeverFallsBackToDatabase() {
    String content = "{\"calendar.date\":\"On {$date :date}\"}";
    AssetContent stored =
        service.createAssetContent(asset("messages.mf2.json"), content, false, branch());
    assertThat(stored.getContent()).isEmpty();
    verify(blobs).put(7L, 3L, DigestUtils.md5Hex(content), false, content);
    when(blobs.get(7L, 3L, stored.getContentMd5(), false)).thenReturn(Optional.of(content));
    assertThat(service.readContent(stored)).isEqualTo(content);
    stored.setContent("Legacy database payload");
    when(blobs.get(7L, 3L, stored.getContentMd5(), false)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.readContent(stored)).isInstanceOf(IllegalStateException.class);
  }

  private AssetContent externalMetadata(String source) {
    AssetContent stored = new AssetContent();
    stored.setAsset(asset("page.mdx"));
    stored.setContent("");
    stored.setBranch(branch());
    stored.setContentMd5(DigestUtils.md5Hex(source));
    return stored;
  }

  private Asset asset(String path) {
    Asset asset = new Asset();
    asset.setId(7L);
    asset.setPath(path);
    return asset;
  }

  private Branch branch() {
    Branch branch = new Branch();
    branch.setId(3L);
    return branch;
  }
}
