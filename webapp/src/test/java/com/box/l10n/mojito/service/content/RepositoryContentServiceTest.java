package com.box.l10n.mojito.service.content;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.entity.TMTextUnitVariant.Status;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.review.*;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class RepositoryContentServiceTest {
  private final UserService users = mock(UserService.class);
  private final RepositoryRepository repositories = mock(RepositoryRepository.class);
  private final RepositoryLocaleRepository locales = mock(RepositoryLocaleRepository.class);
  private final RepositoryContentQueries queries = mock(RepositoryContentQueries.class);
  private final AssetExtractionByBranchRepository templates =
      mock(AssetExtractionByBranchRepository.class);
  private final AssetContentBlobStorage blobs = mock(AssetContentBlobStorage.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ReviewProjectRepository projects = mock(ReviewProjectRepository.class);
  private final ReviewProjectService projectService = mock(ReviewProjectService.class);
  private final ReviewProjectTextUnitRepository projectRows =
      mock(ReviewProjectTextUnitRepository.class);
  private final ReviewProjectDocumentService renderer =
      new ReviewProjectDocumentService(
          projects, projectService, projectRows, templates, blobs, transactions);
  private final RepositoryContentService service =
      new RepositoryContentService(users, repositories, locales, queries, renderer, transactions);
  private final Repository repository = new Repository();
  private final AtomicBoolean inTransaction = new AtomicBoolean();

  @Before
  public void setUp() {
    when(users.isCurrentUserAdmin()).thenReturn(true);
    repository.setId(1L);
    Locale english = locale(10L, "en");
    repository.setSourceLocale(english);
    when(repositories.findNoGraphById(1L)).thenReturn(Optional.of(repository));
    when(queries.branches(1L, 101))
        .thenReturn(
            List.of(
                new RepositoryContentIndex.Branch(3L, null),
                new RepositoryContentIndex.Branch(4L, "")));
    when(queries.defaultBranches(1L))
        .thenReturn(List.of(new RepositoryContentIndex.Branch(3L, null)));
    when(queries.branch(1L, 3L)).thenReturn(new RepositoryContentIndex.Branch(3L, null));
    when(queries.branch(1L, 4L)).thenReturn(new RepositoryContentIndex.Branch(4L, ""));
    for (Locale locale : List.of(english, locale(11L, "fr-FR"))) {
      RepositoryLocale configured = new RepositoryLocale();
      configured.setLocale(locale);
      when(locales.findByRepositoryAndLocale_Bcp47Tag(repository, locale.getBcp47Tag()))
          .thenReturn(configured);
    }
    when(transactions.getTransaction(any()))
        .thenAnswer(
            invocation -> {
              assertThat(inTransaction.getAndSet(true)).isFalse();
              return new SimpleTransactionStatus();
            });
    doAnswer(
            invocation -> {
              inTransaction.set(false);
              return null;
            })
        .when(transactions)
        .commit(any());
    doAnswer(
            invocation -> {
              inTransaction.set(false);
              return null;
            })
        .when(transactions)
        .rollback(any());
  }

  @Test
  public void compositionOnlyRootUsesSavedIncludedTargetsWithoutAnyProject() {
    root("import Child from './child.mdx'\n\n<Child />\n<Child />\n");
    var child = template(20L, "child.mdx", "{/* mojito-id: title */}\n# Welcome\n");
    when(templates.findDocumentTemplate(1L, 3L, "child.mdx")).thenReturn(Optional.of(child));
    when(queries.textUnits(eq(child), eq(11L), eq(List.of("title")), anyInt()))
        .thenReturn(
            List.of(
                new RepositoryContentTextUnit(
                    20L, "title", "Welcome", 200L, 300L, "Bienvenue", Status.APPROVED)));
    var preview = service.preview(1L, 10L, null, "fr-FR");
    assertThat(preview.warnings()).isEmpty();
    assertThat(preview.document().branchId()).isEqualTo(3L);
    assertThat(
            preview.document().blocks().stream()
                .filter(ReviewProjectDocumentView.Block::translatable))
        .hasSize(2)
        .allSatisfy(
            block -> {
              assertThat(block.mappingStatus()).isEqualTo(MappingStatus.MATCHED);
              assertThat(block.tmTextUnitId()).isEqualTo(200L);
              assertThat(block.reviewProjectTextUnitId()).isNull();
              assertThat(block.targetContent()).isEqualTo("Bienvenue");
              assertThat(block.tmTextUnitVariantId()).isEqualTo(300L);
              assertThat(block.targetStatus()).isEqualTo(Status.APPROVED);
            });
    verifyNoInteractions(projects, projectService, projectRows);
  }

  @Test
  public void sourceMismatchAndAmbiguousMatchesStayReadOnly() {
    var template = root("{/* mojito-id: title */}\n# Welcome\n");
    when(queries.textUnits(eq(template), eq(11L), anyList(), anyInt()))
        .thenReturn(
            List.of(
                new RepositoryContentTextUnit(
                    10L, "title", "Old welcome", 100L, 300L, "Ancien", Status.APPROVED)));
    var stale = service.preview(1L, 10L, null, "fr-FR").document().blocks().getFirst();
    assertThat(stale.mappingStatus()).isEqualTo(MappingStatus.SOURCE_CHANGED);
    assertThat(stale.tmTextUnitId()).isNull();
    assertThat(stale.targetContent()).isNull();
    var row = new RepositoryContentTextUnit(10L, "title", "Welcome", 100L, null, null, null);
    when(queries.textUnits(eq(template), eq(11L), anyList(), anyInt()))
        .thenReturn(List.of(row, row));
    assertThat(
            service.preview(1L, 10L, null, "fr-FR").document().blocks().getFirst().tmTextUnitId())
        .isNull();
  }

  @Test
  public void sourceLocaleUsesSourceAndMissingTargetStillHasRealEditableId() {
    var template = root("{/* mojito-id: title */}\n# Welcome\n");
    when(queries.textUnits(eq(template), anyLong(), anyList(), anyInt()))
        .thenReturn(
            List.of(
                new RepositoryContentTextUnit(10L, "title", "Welcome", 100L, null, null, null)));
    var source = service.preview(1L, 10L, null, null).document().blocks().getFirst();
    assertThat(source.targetContent()).isEqualTo("Welcome");
    assertThat(source.tmTextUnitVariantId()).isNull();
    var target = service.preview(1L, 10L, null, "fr-FR").document().blocks().getFirst();
    assertThat(target.mappingStatus()).isEqualTo(MappingStatus.MATCHED);
    assertThat(target.tmTextUnitId()).isEqualTo(100L);
    assertThat(target.targetContent()).isNull();
  }

  @Test
  public void localesAndNewSavedTargetsShareOnlyTheImmutableSourceCache() {
    var template = root("{/* mojito-id: title */}\n# Welcome\n");
    when(queries.textUnits(eq(template), eq(10L), anyList(), anyInt()))
        .thenReturn(
            List.of(
                new RepositoryContentTextUnit(10L, "title", "Welcome", 100L, null, null, null)));
    when(queries.textUnits(eq(template), eq(11L), anyList(), anyInt()))
        .thenReturn(
            List.of(
                new RepositoryContentTextUnit(
                    10L, "title", "Welcome", 100L, 300L, "Bienvenue", Status.APPROVED)),
            List.of(
                new RepositoryContentTextUnit(
                    10L, "title", "Welcome", 100L, 301L, "Bonjour", Status.APPROVED)));

    assertThat(service.preview(1L, 10L, null, "en").document().blocks().getFirst().targetContent())
        .isEqualTo("Welcome");
    assertThat(
            service.preview(1L, 10L, null, "fr-FR").document().blocks().getFirst().targetContent())
        .isEqualTo("Bienvenue");
    assertThat(
            service.preview(1L, 10L, null, "fr-FR").document().blocks().getFirst().targetContent())
        .isEqualTo("Bonjour");
    verify(blobs).get(10L, 3L, template.contentMd5(), false);
    verify(users, times(3)).isCurrentUserAdmin();
    verify(queries, times(3)).find(1L, 3L, 10L);
    verify(queries, times(2)).textUnits(eq(template), eq(11L), anyList(), anyInt());
  }

  @Test
  public void listingSearchesBeforePagingWithoutReadingBlobsAndDistinguishesEmptyBranchName() {
    var template = new ReviewProjectDocumentTemplate(10L, "page.mdx", 1L, 3L, null, "hash");
    when(queries.list(1L, 3L, new RepositoryContentQueries.Filter("a_%", "contains", ""), 4, 2))
        .thenReturn(List.of(template, template));
    var index = service.list(1L, null, " A_% ", 4, 1);
    assertThat(index.branchId()).isEqualTo(3L);
    assertThat(index.hasMore()).isTrue();
    assertThat(index.assets()).hasSize(1);
    assertThat(index.branches())
        .extracting(RepositoryContentIndex.Branch::name)
        .containsExactly(null, "");
    assertThat(service.list(1L, 4L, null, 0, 100).branchId()).isEqualTo(4L);
    verifyNoInteractions(blobs);
  }

  @Test
  public void rejectsUnauthorizedBeforeAnyDatabaseOrBlobAccess() {
    when(users.isCurrentUserAdmin()).thenReturn(false);
    assertThatThrownBy(() -> service.list(1L, null, null, 0, 100))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.preview(1L, 10L, null, null))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(repositories, queries, blobs);
  }

  @Test
  public void projectManagersCannotBrowseContentEvenThoughTheyMayUseOtherTranslationApis() {
    when(users.isCurrentUserAdmin()).thenReturn(false);
    when(users.isCurrentUserAdminOrPm()).thenReturn(true);
    when(users.isCurrentUserPm()).thenReturn(true);
    assertThatThrownBy(() -> service.list(1L, null, null, 0, 100))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> service.list(1L, null, null, 0, 100, "cursor", null, null, "prefix", ""))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.directories(1L, null, "", null, 50))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.preview(1L, 10L, null, null))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(repositories, queries, locales, blobs, transactions);
  }

  @Test
  public void rejectsWrongBranchAssetLocaleAndInvalidPagination() {
    assertThatThrownBy(() -> service.preview(1L, 10L, 999L, "fr-FR"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service.preview(1L, 10L, null, "de-DE"))
        .isInstanceOf(ResponseStatusException.class);
    when(queries.find(1L, 3L, 10L))
        .thenReturn(new ReviewProjectDocumentTemplate(10L, "page.mdx", 2L, 3L, null, "hash"));
    assertThatThrownBy(() -> service.preview(1L, 10L, null, "fr-FR"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service.list(1L, null, null, -1, 100))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> service.list(1L, null, "x".repeat(256), 0, 100))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(blobs);
  }

  @Test
  public void noDefaultIsEmptyAndAmbiguousDefaultRequiresExplicitSelection() {
    when(queries.defaultBranches(1L)).thenReturn(List.of());
    assertThat(service.list(1L, null, null, 0, 100).assets()).isEmpty();
    when(queries.defaultBranches(1L))
        .thenReturn(
            List.of(
                new RepositoryContentIndex.Branch(3L, null),
                new RepositoryContentIndex.Branch(4L, null)));
    assertThatThrownBy(() -> service.list(1L, null, null, 0, 100))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
  }

  @Test
  public void unavailableSourceHasWarningAndNoSavedTargetQuery() {
    when(queries.find(1L, 3L, 10L))
        .thenReturn(new ReviewProjectDocumentTemplate(10L, "page.mdx", 1L, 3L, null, null));
    var preview = service.preview(1L, 10L, null, "fr-FR");
    assertThat(preview.document()).isNull();
    assertThat(preview.warnings()).isNotEmpty();
    verify(queries, never()).textUnits(any(), anyLong(), anyList(), anyInt());
    verifyNoInteractions(blobs);
  }

  @Test
  public void repeatedTargetsCannotAmplifyResponsePastGlobalBudget() {
    root("import Child from './child.mdx'\n\n" + "<Child />\n".repeat(8));
    var child = template(20L, "child.mdx", "{/* mojito-id: title */}\n# Welcome\n");
    when(templates.findDocumentTemplate(1L, 3L, "child.mdx")).thenReturn(Optional.of(child));
    when(queries.textUnits(eq(child), eq(11L), anyList(), anyInt()))
        .thenReturn(
            List.of(
                new RepositoryContentTextUnit(
                    20L, "title", "Welcome", 200L, 300L, "x".repeat(900_000), Status.APPROVED)));
    var preview = service.preview(1L, 10L, null, "fr-FR");
    assertThat(
            preview.document().blocks().stream()
                .mapToInt(
                    block -> block.targetContent() == null ? 0 : block.targetContent().length())
                .sum())
        .isLessThanOrEqualTo(5_000_000);
    assertThat(preview.warnings()).anyMatch(warning -> warning.contains("text size limit"));
  }

  private ReviewProjectDocumentTemplate root(String content) {
    var template = template(10L, "page.mdx", content);
    when(queries.find(1L, 3L, 10L)).thenReturn(template);
    return template;
  }

  private ReviewProjectDocumentTemplate template(Long assetId, String path, String content) {
    var template =
        new ReviewProjectDocumentTemplate(assetId, path, 1L, 3L, null, DigestUtils.md5Hex(content));
    when(blobs.get(assetId, 3L, template.contentMd5(), false))
        .thenAnswer(
            invocation -> {
              assertThat(inTransaction).isFalse();
              return Optional.of(content);
            });
    return template;
  }

  private static Locale locale(Long id, String tag) {
    Locale locale = new Locale();
    locale.setId(id);
    locale.setBcp47Tag(tag);
    return locale;
  }
}
