package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.fileformat.LocalizationParseException;
import com.box.l10n.mojito.fileformat.MdxDocument;
import com.box.l10n.mojito.fileformat.MdxPreviewMessage;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Immutable source parsing only; authorization, current branch membership and targets stay live.
 */
final class ReviewProjectDocumentTemplateCache {
  static final long MAXIMUM_WEIGHT = 32L * 1024 * 1024;
  static final int MINIMUM_ENTRY_WEIGHT = 32 * 1024;
  private static final Pattern CONTENT_MD5 = Pattern.compile("[0-9a-f]{32}");
  private static final String UNAVAILABLE = "source template is unavailable or out of date.";

  private final AssetContentBlobStorage blobs;
  private final Cache<Key, LoadedTemplate> cache;

  ReviewProjectDocumentTemplateCache(AssetContentBlobStorage blobs) {
    this.blobs = blobs;
    cache =
        Caffeine.newBuilder()
            .maximumWeight(MAXIMUM_WEIGHT)
            // Account for retained source, block strings and parser objects. The minimum also
            // bounds tiny documents to at most 1,024 entries; this is not an exact heap measure.
            .weigher(
                (Key key, LoadedTemplate value) ->
                    Math.max(
                        MINIMUM_ENTRY_WEIGHT,
                        4 * value.sourceCharacters()
                            + 256
                                * (value.document() == null
                                    ? value.catalog().size()
                                    : value.document().blocks().size())
                            + 256
                                * (value.document() == null ? 0 : value.document().imports().size())
                            + 256))
            .expireAfterAccess(Duration.ofMinutes(15))
            .build();
  }

  LoadedTemplate get(ReviewProjectDocumentTemplate template) {
    if (template.repositoryId() == null
        || template.repositoryId() <= 0
        || template.assetId() == null
        || template.assetId() <= 0
        || template.branchId() == null
        || template.branchId() <= 0
        || template.contentMd5() == null
        || !CONTENT_MD5.matcher(template.contentMd5()).matches()) {
      return new LoadedTemplate(null, 0, UNAVAILABLE);
    }
    Key key =
        new Key(
            template.repositoryId(),
            template.branchId(),
            template.assetId(),
            template.contentMd5(),
            template.assetPath().toLowerCase(Locale.ROOT).endsWith(".mf2.json"));
    AtomicReference<LoadedTemplate> unavailable = new AtomicReference<>();
    LoadedTemplate cached =
        cache.get(
            key,
            ignored -> {
              LoadedTemplate loaded = load(key);
              if (loaded.document() == null && loaded.catalog() == null) {
                unavailable.set(loaded);
                // Null results and thrown storage errors are never retained by Caffeine.
                return null;
              }
              return loaded;
            });
    return cached == null ? unavailable.get() : cached;
  }

  private LoadedTemplate load(Key key) {
    String content = blobs.get(key.assetId(), key.branchId(), key.contentMd5(), false).orElse(null);
    if (content == null) return new LoadedTemplate(null, 0, UNAVAILABLE);
    int characters = content.length();
    if (characters > ReviewProjectDocumentService.MAX_TEMPLATE_CHARACTERS
        || !Objects.equals(key.contentMd5(), DigestUtils.md5Hex(content))) {
      return new LoadedTemplate(null, characters, UNAVAILABLE);
    }
    try {
      if (key.catalog()) {
        return new LoadedTemplate(null, characters, null, MdxPreviewMessage.parseCatalog(content));
      }
      return new LoadedTemplate(
          MdxDocument.parse(content.getBytes(StandardCharsets.UTF_8)), characters, null);
    } catch (LocalizationParseException unsupported) {
      return new LoadedTemplate(
          null,
          characters,
          key.catalog()
              ? "source template uses unsupported catalog syntax."
              : "source template uses unsupported MDX syntax.");
    }
  }

  record LoadedTemplate(
      MdxDocument document, int sourceCharacters, String warning, Map<String, String> catalog) {
    LoadedTemplate(MdxDocument document, int sourceCharacters, String warning) {
      this(document, sourceCharacters, warning, null);
    }
  }

  private record Key(
      Long repositoryId, Long branchId, Long assetId, String contentMd5, boolean catalog) {}
}
