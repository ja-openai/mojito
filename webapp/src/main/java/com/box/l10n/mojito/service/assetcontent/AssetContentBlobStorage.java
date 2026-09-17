package com.box.l10n.mojito.service.assetcontent;

import static com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage.Prefix.ASSET_CONTENT;

import com.box.l10n.mojito.service.blobstorage.BlobStorageConfigurationProperties;
import com.box.l10n.mojito.service.blobstorage.BlobStorageType;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.StructuredBlobStorage;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

/**
 * Permanently retained source payloads, addressed by content and never written to database blobs.
 */
@Service
public class AssetContentBlobStorage {

  private static final Pattern CONTENT_MD5 = Pattern.compile("[0-9a-f]{32}");

  private final StructuredBlobStorage blobs;
  private final BlobStorageConfigurationProperties configuration;

  public AssetContentBlobStorage(
      StructuredBlobStorage blobs, BlobStorageConfigurationProperties configuration) {
    this.blobs = Objects.requireNonNull(blobs);
    this.configuration = Objects.requireNonNull(configuration);
  }

  /** Writes the payload before its existing SQL metadata is published for extraction. */
  public void put(
      Long assetId, Long branchId, String contentMd5, boolean extractedContent, String content) {
    requireExternalStorage();
    String name = name(assetId, branchId, contentMd5, extractedContent);
    Objects.requireNonNull(content, "content");
    if (!DigestUtils.md5Hex(content).equals(contentMd5)) {
      throw new IllegalArgumentException("Asset content fingerprint does not match its payload");
    }
    // Retries write identical bytes to the same object. A database rollback must not remove an
    // object another successful push may already reference.
    blobs.put(ASSET_CONTENT, name, content, Retention.PERMANENT);
  }

  /** Missing objects remain distinguishable from corrupt payloads and storage failures. */
  public Optional<String> get(
      Long assetId, Long branchId, String contentMd5, boolean extractedContent) {
    requireExternalStorage();
    String name = name(assetId, branchId, contentMd5, extractedContent);
    return blobs
        .getString(ASSET_CONTENT, name)
        .map(
            content -> {
              if (!DigestUtils.md5Hex(content).equals(contentMd5)) {
                throw new IllegalStateException("Asset-content blob fingerprint mismatch");
              }
              return content;
            });
  }

  private String name(Long assetId, Long branchId, String contentMd5, boolean extractedContent) {
    if (assetId == null
        || assetId <= 0
        || branchId == null
        || branchId <= 0
        || contentMd5 == null
        || !CONTENT_MD5.matcher(contentMd5).matches()) {
      throw new IllegalArgumentException(
          "Asset content requires persisted asset and branch IDs and a lowercase MD5");
    }
    return "v1/"
        + assetId
        + "/"
        + branchId
        + "/"
        + (extractedContent ? "catalog/" : "source/")
        + contentMd5;
  }

  private void requireExternalStorage() {
    BlobStorageType route =
        configuration.getStorageTypeForPrefix(ASSET_CONTENT).orElse(configuration.getDefaultType());
    // The migration fallback route also writes exclusively to Azure; failures never retry in DB.
    if (route != BlobStorageType.AZURE
        && route != BlobStorageType.S3
        && route != BlobStorageType.AZURE_WITH_DATABASE_FALLBACK) {
      throw new IllegalStateException(
          "MDX asset content requires external blob storage; configure l10n.blob-storage.routing.prefixes.asset-content as AZURE or S3");
    }
  }
}
