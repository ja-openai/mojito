package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.ModuleStatus;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class ReviewProjectDocumentModulesTest {
  private final ReviewProjectRepository projects = mock(ReviewProjectRepository.class);
  private final ReviewProjectService projectService = mock(ReviewProjectService.class);
  private final ReviewProjectTextUnitRepository rows = mock(ReviewProjectTextUnitRepository.class);
  private final AssetExtractionByBranchRepository templates =
      mock(AssetExtractionByBranchRepository.class);
  private final AssetContentBlobStorage blobs = mock(AssetContentBlobStorage.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
  private final ReviewProjectDocumentService service =
      new ReviewProjectDocumentService(
          projects, projectService, rows, templates, blobs, transactions);

  @Before
  public void setUp() {
    when(projects.findById(1L)).thenReturn(Optional.of(new ReviewProject()));
    when(transactions.getTransaction(any())).thenReturn(transaction);
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(10, 100, "docs/page.mdx", "Page")));
  }

  @Test
  public void nestedBlocksKeepTheirOwnAssetRowsAndRepeatedOccurrenceIdentity() {
    root(
        "import Child from './shared/child.mdx'\n\n"
            + heading("Page")
            + "\n<Child />\n\n<Child />\n");
    child(
        20,
        "docs/shared/child.mdx",
        "import Leaf from '../leaf.mdx'\n\n" + heading("Child") + "\n<Leaf />\n");
    child(30, "docs/leaf.mdx", heading("Leaf"));
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(
            List.of(
                row(10, 100, "docs/page.mdx", "Page"),
                row(20, 200, "docs/shared/child.mdx", "Child"),
                row(30, 300, "docs/leaf.mdx", "Leaf")));

    var document = document();
    var headings =
        document.blocks().stream().filter(block -> block.type().equals("heading")).toList();

    assertThat(headings)
        .extracting(ReviewProjectDocumentView.Block::source)
        .containsExactly("Page", "Child", "Leaf", "Child", "Leaf");
    assertThat(headings)
        .extracting(ReviewProjectDocumentView.Block::reviewProjectTextUnitId)
        .containsExactly(100L, 200L, 300L, 200L, 300L);
    assertThat(headings)
        .extracting(ReviewProjectDocumentView.Block::moduleDepth)
        .containsExactly(0, 1, 2, 1, 2);
    assertThat(document.blocks())
        .extracting(ReviewProjectDocumentView.Block::occurrenceId)
        .doesNotHaveDuplicates();
    assertThat(document.blocks().stream().filter(block -> block.type().equals("component")))
        .allMatch(block -> block.moduleStatus() == ModuleStatus.EXPANDED);
    verify(templates, times(1)).findDocumentTemplate(1L, 3L, "docs/shared/child.mdx");
    assertThat(document.warnings()).isEmpty();
  }

  @Test
  public void cachedParentResolvesUpdatedAndRemovedChildrenOnEveryRequest() {
    var parent = root("import Child from './child.mdx'\n\n" + heading("Page") + "\n<Child />\n");
    var original = child(20, "docs/child.mdx", heading("Original child"));
    assertThat(document().blocks()).anyMatch(block -> block.source().equals("Original child"));
    var updated = child(20, "docs/child.mdx", heading("Updated child"));
    assertThat(document().blocks())
        .anyMatch(block -> block.source().equals("Updated child"))
        .noneMatch(block -> block.source().equals("Original child"));
    when(templates.findDocumentTemplate(1L, 3L, "docs/child.mdx")).thenReturn(Optional.empty());
    assertThat(document().blocks()).noneMatch(block -> block.assetId().equals(20L));
    verify(blobs).get(10L, 3L, parent.contentMd5(), false);
    verify(blobs).get(20L, 3L, original.contentMd5(), false);
    verify(blobs).get(20L, 3L, updated.contentMd5(), false);
    verify(templates, times(3)).findDocumentTemplate(1L, 3L, "docs/child.mdx");
  }

  @Test
  public void previewChoiceExpandsEveryAlternativeWithItsOwnReviewRows() {
    root(
        "import Individual from './individual.mdx'\nimport Team from './team.mdx'\n\n"
            + heading("Page")
            + "\n<PreviewChoice>\n<Individual />\n<Team />\n</PreviewChoice>\n");
    child(20, "docs/individual.mdx", heading("Individual"));
    child(30, "docs/team.mdx", heading("Team"));
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(
            List.of(
                row(10, 100, "docs/page.mdx", "Page"),
                row(20, 200, "docs/individual.mdx", "Individual"),
                row(30, 300, "docs/team.mdx", "Team")));

    var document = document();

    assertThat(document.warnings()).isEmpty();
    assertThat(document.blocks().stream().filter(ReviewProjectDocumentView.Block::translatable))
        .extracting(ReviewProjectDocumentView.Block::reviewProjectTextUnitId)
        .containsExactly(100L, 200L, 300L);
    assertThat(document.blocks().stream().filter(block -> block.source().contains("PreviewChoice")))
        .hasSize(2)
        .allSatisfy(
            block -> {
              assertThat(block.mappingStatus()).isEqualTo(MappingStatus.CONTEXT);
              assertThat(block.moduleStatus()).isNull();
              assertThat(block.moduleWarning()).isNull();
            });
    assertThat(
            document.blocks().stream()
                .filter(block -> block.moduleStatus() == ModuleStatus.EXPANDED))
        .extracting(ReviewProjectDocumentView.Block::modulePath)
        .containsExactly("docs/individual.mdx", "docs/team.mdx");
  }

  @Test
  public void choicesInsideIncludedModulesStillObeyTheModuleDepthLimit() {
    root("import A from './a.mdx'\n\n" + heading("Page") + "\n<A />\n");
    child(20, "docs/a.mdx", "import B from './b.mdx'\n\n<B />\n");
    child(30, "docs/b.mdx", "import C from './c.mdx'\n\n<C />\n");
    child(
        40,
        "docs/c.mdx",
        "import D from './d.mdx'\nimport E from './e.mdx'\n\n"
            + "<PreviewChoice>\n<D />\n<E />\n</PreviewChoice>\n");

    var document = document();

    assertThat(
            document.blocks().stream()
                .filter(block -> block.moduleStatus() == ModuleStatus.UNAVAILABLE))
        .hasSize(2)
        .allSatisfy(block -> assertThat(block.moduleWarning()).contains("3 levels"));
    assertThat(document.warnings()).noneMatch(warning -> warning.contains("Paired components"));
    verify(templates, never()).findDocumentTemplate(1L, 3L, "docs/d.mdx");
    verify(templates, never()).findDocumentTemplate(1L, 3L, "docs/e.mdx");
  }

  @Test
  public void anIncludedAssetOutsideTheProjectIsSourceOnlyContext() {
    root("import Child from './child.mdx'\n\n" + heading("Page") + "\n<Child />\n");
    child(20, "docs/child.mdx", heading("Page"));

    var child = document().blocks().getLast();

    assertThat(child.assetId()).isEqualTo(20L);
    assertThat(child.assetPath()).isEqualTo("docs/child.mdx");
    assertThat(child.mappingStatus()).isEqualTo(MappingStatus.NOT_IN_PROJECT);
    assertThat(child.reviewProjectTextUnitId()).isNull();
    assertThat(child.tmTextUnitId()).isNull();
  }

  @Test
  public void anIncludedChangedSourceCannotEditAnOlderReviewRow() {
    root("import Child from './child.mdx'\n\n" + heading("Page") + "\n<Child />\n");
    child(20, "docs/child.mdx", heading("Updated child"));
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(
            List.of(
                row(10, 100, "docs/page.mdx", "Page"),
                row(20, 200, "docs/child.mdx", "Previous child")));

    var child = document().blocks().getLast();

    assertThat(child.mappingStatus()).isEqualTo(MappingStatus.SOURCE_CHANGED);
    assertThat(child.reviewProjectTextUnitId()).isNull();
  }

  @Test
  public void ancestorCyclesRemainVisibleWithoutBlockingTheFollowingParagraph() {
    var parent =
        root(
            "import Child from './child.mdx'\n\n"
                + heading("Page")
                + "\n<Child />\n\nFollowing paragraph.\n");
    child(20, "docs/child.mdx", "import Parent from './page.mdx'\n\n<Parent />\n");
    when(templates.findDocumentTemplate(1L, 3L, "docs/page.mdx")).thenReturn(Optional.of(parent));

    var document = document();

    assertThat(document.blocks().get(2).moduleStatus()).isEqualTo(ModuleStatus.UNAVAILABLE);
    assertThat(document.blocks().get(2).moduleWarning()).contains("cycle");
    assertThat(document.blocks().getLast().source()).isEqualTo("Following paragraph.");
    verify(blobs, times(1)).get(10L, 3L, parent.contentMd5(), false);
  }

  @Test
  public void threeNestedModulesAreAllowedAndTheFourthBoundaryExplainsTheLimit() {
    root("import A from './a.mdx'\n\n" + heading("Page") + "\n<A />\n");
    child(20, "docs/a.mdx", "import B from './b.mdx'\n\n<B />\n");
    child(30, "docs/b.mdx", "import C from './c.mdx'\n\n<C />\n");
    child(40, "docs/c.mdx", "import D from './d.mdx'\n\n<D />\n");

    var boundary = document().blocks().getLast();

    assertThat(boundary.moduleDepth()).isEqualTo(3);
    assertThat(boundary.moduleStatus()).isEqualTo(ModuleStatus.UNAVAILABLE);
    assertThat(boundary.moduleWarning()).contains("3 levels");
    verify(templates, never()).findDocumentTemplate(1L, 3L, "docs/d.mdx");
  }

  @Test
  public void missingNamedReactAndPairedComponentsRemainBoundariesWithReadableContent() {
    root(
        "import Missing from './missing.mdx'\nimport Widget from './widget.tsx'\nimport { Named } from './named.mdx'\n"
            + "\n"
            + heading("Page")
            + "\n<Missing />\n\n<Widget />\n\n<Named />\n\n<Missing>\n\nInside wrapper.\n\n</Missing>\n");

    var document = document();

    assertThat(
            document.blocks().stream()
                .filter(block -> block.moduleStatus() == ModuleStatus.UNAVAILABLE))
        .hasSize(4);
    assertThat(document.blocks())
        .extracting(ReviewProjectDocumentView.Block::source)
        .contains("Inside wrapper.", "</Missing>");
    verify(templates, times(1)).findDocumentTemplate(1L, 3L, "docs/missing.mdx");
    verify(templates, never()).findDocumentTemplate(1L, 3L, "docs/widget.tsx");
    verify(templates, never()).findDocumentTemplate(1L, 3L, "docs/named.mdx");
  }

  @Test
  public void metadataCannotBorrowAnotherRepositoryBranchOrCaseDifferentPath() {
    root("import Child from './child.mdx'\n\n" + heading("Page") + "\n<Child />\n");
    for (var wrong :
        List.of(
            new ReviewProjectDocumentTemplate(20L, "docs/child.mdx", 2L, 3L, "main", "md5"),
            new ReviewProjectDocumentTemplate(20L, "docs/child.mdx", 1L, 4L, "other", "md5"),
            new ReviewProjectDocumentTemplate(20L, "docs/Child.mdx", 1L, 3L, "main", "md5"))) {
      when(templates.findDocumentTemplate(1L, 3L, "docs/child.mdx")).thenReturn(Optional.of(wrong));
      assertThat(document().blocks().getLast().moduleStatus()).isEqualTo(ModuleStatus.UNAVAILABLE);
    }
    verify(blobs, never()).get(20L, 3L, "md5", false);
    verify(blobs, never()).get(20L, 4L, "md5", false);
  }

  @Test
  public void childLookupTransactionEndsBeforeBlobReadAndChildFailureStaysLocal() {
    root(
        "import Child from './child.mdx'\n\n"
            + heading("Page")
            + "\n<Child />\n\nStill readable.\n");
    var child = child(20, "docs/child.mdx", heading("Child"));
    when(blobs.get(20L, 3L, child.contentMd5(), false))
        .thenThrow(new IllegalStateException("offline"));

    var document = document();

    assertThat(document.blocks().get(1).moduleWarning()).contains("could not be loaded");
    assertThat(document.blocks().getLast().source()).isEqualTo("Still readable.");
    var order = inOrder(templates, transactions, blobs);
    order.verify(templates).findDocumentTemplate(1L, 3L, "docs/child.mdx");
    order.verify(transactions).commit(transaction);
    order.verify(blobs).get(20L, 3L, child.contentMd5(), false);
  }

  @Test
  public void rootStorageFailureStillPropagatesForRequestRetry() {
    var root = root(heading("Page"));
    when(blobs.get(10L, 3L, root.contentMd5(), false))
        .thenThrow(new IllegalStateException("offline"));
    assertThatThrownBy(() -> service.getDocuments(1L)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void relativePathsNormalizeWithoutEscapingTheRepositoryOrAcceptingUrls() {
    assertThat(
            ReviewProjectDocumentService.resolveModulePath(
                "docs/pages/page.mdx", "../shared/./intro.mdx"))
        .isEqualTo("docs/shared/intro.mdx");
    for (String path :
        List.of(
            "../../../escape.mdx",
            "/absolute.mdx",
            "https://host/file.mdx",
            "./file%20name.mdx",
            "./a?x.mdx",
            "./a#x.mdx",
            "./a\\b.mdx",
            "./widget.tsx",
            "@alias/a.mdx")) {
      assertThat(ReviewProjectDocumentService.resolveModulePath("docs/page.mdx", path)).isNull();
    }
  }

  @Test
  public void repeatedHugeImportSpecifierNeverEntersOutputMetadataOrLookups() {
    String specifier = "./" + "a".repeat(500_000) + ".mdx";
    root(
        "import Child from '"
            + specifier
            + "'\n\n"
            + heading("Page")
            + "\n<Child />\n".repeat(100));

    var document = document();

    var boundaries =
        document.blocks().stream().filter(block -> block.type().equals("component")).toList();
    assertThat(boundaries).hasSize(100);
    assertThat(boundaries)
        .allMatch(
            block ->
                block.moduleStatus() == ModuleStatus.UNAVAILABLE
                    && block.modulePath() == null
                    && block.moduleWarning().length() < 100);
    assertThat(document.warnings()).allMatch(warning -> warning.length() < 150);
    verify(templates, never()).findDocumentTemplate(any(), any(), any());
  }

  private ReviewProjectDocumentView.Document document() {
    return service.getDocuments(1L).documents().getFirst();
  }

  private String heading(String source) {
    return "{/* mojito-id: title */}\n# " + source + "\n";
  }

  private ReviewProjectDocumentTextUnit row(long assetId, long rowId, String path, String source) {
    return new ReviewProjectDocumentTextUnit(rowId, rowId + 1, "title", source, assetId, path, 1L);
  }

  private ReviewProjectDocumentTemplate root(String content) {
    var template = template(10, "docs/page.mdx", content);
    when(templates.findDocumentTemplatesByProjectId(1L, PageRequest.of(0, 101)))
        .thenReturn(List.of(template));
    return template;
  }

  private ReviewProjectDocumentTemplate child(long assetId, String path, String content) {
    var template = template(assetId, path, content);
    when(templates.findDocumentTemplate(1L, 3L, path)).thenReturn(Optional.of(template));
    return template;
  }

  private ReviewProjectDocumentTemplate template(long assetId, String path, String content) {
    String md5 = DigestUtils.md5Hex(content);
    when(blobs.get(assetId, 3L, md5, false)).thenReturn(Optional.of(content));
    return new ReviewProjectDocumentTemplate(assetId, path, 1L, 3L, "main", md5);
  }
}
