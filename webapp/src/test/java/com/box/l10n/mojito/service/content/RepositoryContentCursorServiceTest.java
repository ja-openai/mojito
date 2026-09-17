package com.box.l10n.mojito.service.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentService;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class RepositoryContentCursorServiceTest {
  private final UserService users = mock(UserService.class);
  private final RepositoryRepository repositories = mock(RepositoryRepository.class);
  private final RepositoryLocaleRepository locales = mock(RepositoryLocaleRepository.class);
  private final RepositoryContentQueries queries = mock(RepositoryContentQueries.class);
  private final ReviewProjectDocumentService renderer = mock(ReviewProjectDocumentService.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final RepositoryContentService service =
      new RepositoryContentService(users, repositories, locales, queries, renderer, transactions);
  private final RepositoryContentQueries.Filter filter =
      new RepositoryContentQueries.Filter("", "prefix", "");

  @Before
  public void setup() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var locale = new Locale();
    locale.setBcp47Tag("en");
    for (long id : List.of(1L, 2L)) {
      var repository = new Repository();
      repository.setId(id);
      repository.setSourceLocale(locale);
      when(repositories.findNoGraphById(id)).thenReturn(Optional.of(repository));
      when(queries.defaultBranches(id))
          .thenReturn(List.of(new RepositoryContentIndex.Branch(3L, null)));
      when(queries.branches(id, 101))
          .thenReturn(List.of(new RepositoryContentIndex.Branch(3L, null)));
      when(queries.branch(id, 3L)).thenReturn(new RepositoryContentIndex.Branch(3L, null));
      when(queries.branch(id, 4L)).thenReturn(new RepositoryContentIndex.Branch(4L, "feature"));
    }
  }

  @Test
  public void walksBothDirectionsWithBoundedQueriesAndRestoresAscendingOrder() {
    when(queries.seek(1L, 3L, filter, null, false, 3)).thenReturn(rows("a", "b", "c"));
    var first = page(null, null);
    assertPaths(first, "a.mdx", "b.mdx");
    assertThat(first.previousCursor()).isNull();
    assertThat(first.nextCursor()).isNotBlank();
    assertThat(first.offset()).isZero();
    var after = new RepositoryContentCursor("b.mdx", 2);
    when(queries.seek(1L, 3L, filter, after, false, 3)).thenReturn(rows("c", "d", "e"));
    when(queries.seek(1L, 3L, filter, new RepositoryContentCursor("c.mdx", 3), true, 1))
        .thenReturn(rows("b"));
    var second = page(first.nextCursor(), null);
    assertPaths(second, "c.mdx", "d.mdx");
    assertThat(second.previousCursor()).isNotBlank();
    when(queries.seek(1L, 3L, filter, new RepositoryContentCursor("c.mdx", 3), true, 3))
        .thenReturn(rows("b", "a"));
    when(queries.seek(1L, 3L, filter, after, false, 1)).thenReturn(rows("c"));
    var previous = page(null, second.previousCursor());
    assertPaths(previous, "a.mdx", "b.mdx");
    assertThat(previous.previousCursor()).isNull();
    assertThat(previous.nextCursor()).isEqualTo(first.nextCursor());
    verify(queries, times(3)).branches(1L, 101);
    verify(queries, never())
        .list(any(), any(), any(RepositoryContentQueries.Filter.class), anyInt(), anyInt());
    verifyNoInteractions(locales, renderer);
  }

  @Test
  public void emptyPageHasNoInventedCursorsAndOffsetResponseCanTransitionToSeek() {
    when(queries.list(1L, 3L, filter, 100, 3)).thenReturn(rows("c", "d", "e"));
    when(queries.seek(1L, 3L, filter, new RepositoryContentCursor("c.mdx", 3), true, 1))
        .thenReturn(rows("b"));
    var offset = service.list(1L, 3L, null, 100, 2, null, null, null, "prefix", "");
    assertThat(offset.offset()).isEqualTo(100);
    assertThat(offset.nextCursor()).isNotBlank();
    assertThat(offset.previousCursor()).isNotBlank();
    var empty = page(offset.nextCursor(), null);
    assertThat(empty.assets()).isEmpty();
    assertThat(empty.nextCursor()).isNull();
    assertThat(empty.previousCursor()).isNull();
    assertThat(empty.hasMore()).isFalse();
  }

  @Test
  public void cursorRejectsForeignRepositoryBranchAndChangedSearchScope() {
    when(queries.seek(1L, 3L, filter, null, false, 3)).thenReturn(rows("a", "b", "c"));
    String cursor = page(null, null).nextCursor();
    assertBad(() -> service.list(2L, 3L, null, 0, 2, "cursor", cursor, null, "prefix", ""));
    assertBad(() -> service.list(1L, 4L, null, 0, 2, "cursor", cursor, null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, "new", 0, 2, "cursor", cursor, null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 2, "cursor", cursor, null, "exact", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 2, "cursor", cursor, null, "prefix", "docs/"));
    assertBad(() -> service.directories(1L, 3L, "", cursor, 50));
    assertBad(() -> page("", null));
    assertBad(() -> page("malformed!", null));
    assertBad(() -> page("a".repeat(2049), null));
  }

  @Test
  public void directChildCursorsCannotCrossRecursiveScopeAndLegacyCursorsRemainValid() {
    var direct = new RepositoryContentQueries.Filter("", "prefix", "", false);
    when(queries.seek(1L, 3L, filter, null, false, 3)).thenReturn(rows("a", "b", "c"));
    when(queries.seek(1L, 3L, direct, null, false, 3)).thenReturn(rows("a", "b", "c"));
    String recursiveCursor = page(null, null).nextCursor();
    String directCursor =
        service.list(1L, 3L, null, 0, 2, "cursor", null, null, "prefix", "", false).nextCursor();
    assertThat(directCursor).isNotEqualTo(recursiveCursor);
    String legacyScope = RepositoryContentCursor.scope("assets", 1L, 3L, "prefix", "", "");
    assertThat(recursiveCursor)
        .isEqualTo(new RepositoryContentCursor("b.mdx", 2).encode(legacyScope));

    var after = new RepositoryContentCursor("b.mdx", 2);
    when(queries.seek(1L, 3L, direct, after, false, 3)).thenReturn(rows("c"));
    assertPaths(
        service.list(1L, 3L, null, 0, 2, "cursor", directCursor, null, "prefix", "", false),
        "c.mdx");
    assertBad(
        () ->
            service.list(1L, 3L, null, 0, 2, "cursor", recursiveCursor, null, "prefix", "", false));
    assertBad(() -> page(directCursor, null));
    verifyNoInteractions(locales, renderer);
  }

  @Test
  public void directChildListingNormalizesDirectoriesBeforeQueriesAndCursorScope() {
    var direct = new RepositoryContentQueries.Filter("", "prefix", "docs_%!/", false);
    when(queries.list(1L, 3L, direct, 2, 3)).thenReturn(rows("a", "b", "c"));
    var result =
        service.list(1L, 3L, null, 2, 2, "offset", null, null, "prefix", "docs_%!//", false);
    assertPaths(result, "a.mdx", "b.mdx");
    verify(queries).list(1L, 3L, direct, 2, 3);
    when(queries.seek(1L, 3L, direct, new RepositoryContentCursor("b.mdx", 2), false, 3))
        .thenReturn(rows("c"));
    assertPaths(
        service.list(
            1L, 3L, null, 0, 2, "cursor", result.nextCursor(), null, "prefix", "docs_%!", false),
        "c.mdx");
    assertBad(
        () ->
            service.list(
                1L,
                3L,
                null,
                0,
                2,
                "cursor",
                result.nextCursor(),
                null,
                "prefix",
                "docsAAx/",
                false));
  }

  @Test
  public void boundsParametersAndAuthorizesBeforeAccess() {
    assertBad(() -> service.list(1L, 3L, null, 0, 101, "cursor", null, null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 0, "cursor", null, null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 1, 2, "cursor", null, null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 2, "cursor", "a", "b", "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 2, null, "a", null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 2, "unknown", null, null, "prefix", ""));
    assertBad(() -> service.list(1L, 3L, null, 0, 2, "cursor", null, null, "unknown", ""));
    assertBad(() -> service.list(1L, 3L, "a".repeat(256), 0, 2, "cursor", null, null, "exact", ""));
    assertBad(() -> service.directories(1L, 3L, "", null, 51));
    assertBad(() -> service.directories(1L, 3L, "", null, 0));
    for (String directory :
        List.of("/root/", "../", "foo/../", "./foo/", "foo\\bar", "foo\nbar", "a".repeat(255))) {
      assertBad(() -> service.directories(1L, 3L, directory, null, 50));
    }
    when(users.isCurrentUserAdmin()).thenReturn(false);
    assertThatThrownBy(() -> page(null, null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.directories(1L, 3L, "", null, 50))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(repositories, queries, locales, renderer);
  }

  @Test
  public void normalizesDirectoriesAndKeepsTheirIndependentCursorScope() {
    when(queries.directories(1L, 3L, "docs/", null, 3))
        .thenReturn(List.of("docs/a/", "docs/b/", "docs/c/"));
    var page = service.directories(1L, 3L, "docs//", null, 2);
    assertThat(page.directory()).isEqualTo("docs/");
    assertThat(page.directories()).containsExactly("docs/a/", "docs/b/");
    assertThat(page.nextCursor()).isNotBlank();
    when(queries.directories(1L, 3L, "docs/", "docs/b/", 3)).thenReturn(List.of("docs/c/"));
    var next = service.directories(1L, 3L, "docs", page.nextCursor(), 2);
    assertThat(next.directories()).containsExactly("docs/c/");
    assertThat(next.nextCursor()).isNull();
    assertBad(() -> service.directories(1L, 3L, "other/", page.nextCursor(), 2));
    assertBad(() -> service.directories(2L, 3L, "docs/", page.nextCursor(), 2));
    assertBad(() -> service.directories(1L, 4L, "docs/", page.nextCursor(), 2));
    assertBad(() -> page(page.nextCursor(), null));
    verifyNoInteractions(locales, renderer);
  }

  @Test
  public void exactAndPrefixSearchPreserveLiteralWhitespaceWhileContainsKeepsLegacyNormalization() {
    service.list(1L, 3L, " Leading ", 0, 2, "cursor", null, null, "prefix", "");
    service.list(1L, 3L, " Leading ", 0, 2, "cursor", null, null, "exact", "");
    service.list(1L, 3L, " Leading ", 0, 2, "cursor", null, null, "contains", "");
    verify(queries)
        .seek(
            1L, 3L, new RepositoryContentQueries.Filter(" Leading ", "prefix", ""), null, false, 3);
    verify(queries)
        .seek(
            1L, 3L, new RepositoryContentQueries.Filter(" Leading ", "exact", ""), null, false, 3);
    verify(queries)
        .seek(
            1L, 3L, new RepositoryContentQueries.Filter("leading", "contains", ""), null, false, 3);
  }

  @Test
  public void boundsBranchesAndIncludesAnExplicitSelectionOutsideFirstHundred() {
    when(queries.branches(1L, 101))
        .thenReturn(
            LongStream.rangeClosed(10, 110)
                .mapToObj(id -> new RepositoryContentIndex.Branch(id, "branch-" + id))
                .toList());
    var result = page(null, null);
    assertThat(result.branches()).hasSize(101);
    assertThat(result.branches().getLast().id()).isEqualTo(3L);
    assertThat(result.warnings()).singleElement().asString().contains("first 100 branches");
  }

  @Test
  public void defaultOffsetRequestsUseBoundedBranchesAndPreserveContainsSearch() {
    when(queries.branches(1L, 101))
        .thenReturn(
            LongStream.rangeClosed(10, 110)
                .mapToObj(id -> new RepositoryContentIndex.Branch(id, "branch-" + id))
                .toList());
    var contains = new RepositoryContentQueries.Filter("a_%", "contains", "");
    when(queries.list(1L, 3L, contains, 4, 3)).thenReturn(rows("a", "b", "c"));
    when(queries.seek(1L, 3L, contains, new RepositoryContentCursor("a.mdx", 1), true, 1))
        .thenReturn(rows("previous"));

    // No pagination/search-mode parameters: the original endpoint contract.
    var result = service.list(1L, null, " A_% ", 4, 2, null, null, null, null, null);

    assertPaths(result, "a.mdx", "b.mdx");
    assertThat(result.offset()).isEqualTo(4);
    assertThat(result.hasMore()).isTrue();
    assertThat(result.nextCursor()).isNotBlank();
    assertThat(result.previousCursor()).isNotBlank();
    assertThat(result.branches()).hasSize(101);
    assertThat(result.branches().getLast().id()).isEqualTo(3L);
    assertThat(result.warnings()).singleElement().asString().contains("first 100 branches");
    verify(queries).branches(1L, 101);
    verify(queries).list(1L, 3L, contains, 4, 3);
    verifyNoInteractions(locales, renderer);
  }

  @Test
  public void defaultBranchAmbiguityAndMissingExplicitBranchesRemainErrors() {
    when(queries.defaultBranches(1L))
        .thenReturn(
            List.of(
                new RepositoryContentIndex.Branch(3L, null),
                new RepositoryContentIndex.Branch(4L, null)));
    assertThatThrownBy(() -> service.list(1L, null, null, 0, 2, "cursor", null, null, "prefix", ""))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
    assertThatThrownBy(() -> service.directories(1L, 99L, "", null, 50))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    when(queries.defaultBranches(1L)).thenReturn(List.of());
    var empty = service.list(1L, null, null, 0, 2, "cursor", null, null, "prefix", "");
    assertThat(empty.branchId()).isNull();
    assertThat(empty.assets()).isEmpty();
    assertThat(service.directories(1L, null, "", null, 50).directories()).isEmpty();
  }

  private RepositoryContentIndex page(String after, String before) {
    return service.list(1L, 3L, null, 0, 2, "cursor", after, before, "prefix", "");
  }

  private static List<ReviewProjectDocumentTemplate> rows(String... names) {
    return List.of(names).stream()
        .map(
            name ->
                new ReviewProjectDocumentTemplate(
                    (long) name.charAt(0) - 'a' + 1, name + ".mdx", 1L, 3L, null, "hash"))
        .toList();
  }

  private static void assertPaths(RepositoryContentIndex result, String... paths) {
    assertThat(result.assets())
        .extracting(RepositoryContentIndex.Asset::assetPath)
        .containsExactly(paths);
  }

  private static void assertBad(Throwing action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("400");
  }

  private interface Throwing {
    void run();
  }
}
