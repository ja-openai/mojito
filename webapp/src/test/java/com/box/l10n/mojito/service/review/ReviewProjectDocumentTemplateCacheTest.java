package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

public class ReviewProjectDocumentTemplateCacheTest {
  private static final String SOURCE = "{/* mojito-id: title */}\n# Welcome\n";
  private final AssetContentBlobStorage blobs = mock(AssetContentBlobStorage.class);
  private final ReviewProjectDocumentTemplateCache cache =
      new ReviewProjectDocumentTemplateCache(blobs);

  @Test
  public void reusesParsedSourceAndSeparatesRepositoryBranchAssetAndHash() {
    var original = template(1L, 2L, 3L, SOURCE);
    var first = cache.get(original);
    assertThat(first.document()).isNotNull();
    assertThat(cache.get(original)).isSameAs(first);
    assertThat(cache.get(template(9L, 2L, 3L, SOURCE))).isNotSameAs(first);
    assertThat(cache.get(template(1L, 9L, 3L, SOURCE))).isNotSameAs(first);
    assertThat(cache.get(template(1L, 2L, 9L, SOURCE))).isNotSameAs(first);
    var changed = cache.get(template(1L, 2L, 3L, SOURCE.replace("Welcome", "Hello")));
    assertThat(changed.document().blocks().getFirst().source()).isEqualTo("Hello");
    assertThat(cache.get(original)).isSameAs(first);
    verify(blobs, times(5)).get(anyLong(), anyLong(), anyString(), eq(false));
  }

  @Test
  public void retriesMissingAndCorruptPayloadsUntilAValidSourceIsAvailable() {
    var template = template(1L, 2L, 3L, SOURCE);
    when(blobs.get(3L, 2L, template.contentMd5(), false))
        .thenReturn(Optional.empty(), Optional.of("wrong payload"), Optional.of(SOURCE));
    assertThat(cache.get(template).document()).isNull();
    assertThat(cache.get(template).document()).isNull();
    var valid = cache.get(template);
    assertThat(valid.document()).isNotNull();
    assertThat(cache.get(template)).isSameAs(valid);
    verify(blobs, times(3)).get(3L, 2L, template.contentMd5(), false);
  }

  @Test
  public void retriesStorageErrorsWithoutRetainingThem() {
    var template = template(1L, 2L, 3L, SOURCE);
    when(blobs.get(3L, 2L, template.contentMd5(), false))
        .thenThrow(new IllegalStateException("temporarily unavailable"))
        .thenReturn(Optional.of(SOURCE));
    assertThatThrownBy(() -> cache.get(template)).isInstanceOf(IllegalStateException.class);
    assertThat(cache.get(template).document()).isNotNull();
    verify(blobs, times(2)).get(3L, 2L, template.contentMd5(), false);
  }

  @Test
  public void unsupportedAndOversizedSourcesAreNotCachedAndKeepTheirBudgetCost() {
    for (String source : List.of("{dynamicExpression}\n", "x".repeat(1_000_001))) {
      var template = template(1L, 2L, 3L, source);
      for (int attempt = 0; attempt < 2; attempt++) {
        var result = cache.get(template);
        assertThat(result.document()).isNull();
        assertThat(result.warning()).isNotEmpty();
        assertThat(result.sourceCharacters()).isEqualTo(source.length());
      }
      verify(blobs, times(2)).get(3L, 2L, template.contentMd5(), false);
    }
  }

  @Test
  public void invalidTemplateIdentityDoesNotReadStorage() {
    for (var template :
        List.of(
            new ReviewProjectDocumentTemplate(3L, "page.mdx", 1L, 2L, "main", null),
            new ReviewProjectDocumentTemplate(3L, "page.mdx", 1L, 2L, "main", "stale"),
            new ReviewProjectDocumentTemplate(
                3L, "page.mdx", null, 2L, "main", DigestUtils.md5Hex(SOURCE)))) {
      assertThat(cache.get(template).document()).isNull();
    }
    verifyNoInteractions(blobs);
  }

  @Test
  public void concurrentReadersShareOneSuccessfulBlobReadAndParse() throws Exception {
    var template = template(1L, 2L, 3L, SOURCE);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(blobs.get(3L, 2L, template.contentMd5(), false))
        .thenAnswer(
            ignored -> {
              started.countDown();
              assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
              return Optional.of(SOURCE);
            });
    try (var executor = Executors.newFixedThreadPool(4)) {
      List<Future<ReviewProjectDocumentTemplateCache.LoadedTemplate>> readers = new ArrayList<>();
      try {
        readers.add(executor.submit(() -> cache.get(template)));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 3; index++) {
          readers.add(executor.submit(() -> cache.get(template)));
        }
      } finally {
        release.countDown();
      }
      var first = readers.getFirst().get(5, TimeUnit.SECONDS);
      for (var reader : readers) assertThat(reader.get(5, TimeUnit.SECONDS)).isSameAs(first);
    }
    verify(blobs).get(3L, 2L, template.contentMd5(), false);
  }

  private ReviewProjectDocumentTemplate template(
      Long repositoryId, Long branchId, Long assetId, String source) {
    String hash = DigestUtils.md5Hex(source);
    when(blobs.get(assetId, branchId, hash, false)).thenReturn(Optional.of(source));
    return new ReviewProjectDocumentTemplate(
        assetId, "page.mdx", repositoryId, branchId, "main", hash);
  }
}
