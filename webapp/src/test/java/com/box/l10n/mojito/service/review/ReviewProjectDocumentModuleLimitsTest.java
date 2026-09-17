package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class ReviewProjectDocumentModuleLimitsTest {
  private static final String TITLE = "{/* mojito-id: title */}\n# Welcome\n\n";

  private final ReviewProjectRepository projects = mock(ReviewProjectRepository.class);
  private final ReviewProjectService projectService = mock(ReviewProjectService.class);
  private final ReviewProjectTextUnitRepository rows = mock(ReviewProjectTextUnitRepository.class);
  private final AssetExtractionByBranchRepository templates =
      mock(AssetExtractionByBranchRepository.class);
  private final AssetContentBlobStorage blobs = mock(AssetContentBlobStorage.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ReviewProjectDocumentService service =
      new ReviewProjectDocumentService(
          projects, projectService, rows, templates, blobs, transactions);

  @Before
  public void setUp() {
    ReviewProject project = new ReviewProject();
    project.setId(1L);
    when(projects.findById(1L)).thenReturn(Optional.of(project));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @Test
  public void missingModuleLookupsStillConsumeTheGlobalModuleBudget() {
    int references = ReviewProjectDocumentService.MAX_MODULES + 20;
    roots(template(7L, "page.mdx", imports(references) + TITLE + components(references)));
    when(templates.findDocumentTemplate(eq(2L), eq(3L), anyString())).thenReturn(Optional.empty());

    var result = service.getDocuments(1L);

    verify(templates, times(ReviewProjectDocumentService.MAX_MODULES - 1))
        .findDocumentTemplate(eq(2L), eq(3L), anyString());
    assertThat(blocks(result).filter(block -> "component".equals(block.type())).count())
        .isEqualTo(references);
    assertThat(result.documents().getFirst().warnings())
        .anyMatch(warning -> warning.contains("module or block limit"));
    verify(blobs, times(1)).get(anyLong(), anyLong(), anyString(), eq(false));
  }

  @Test
  public void emittedBlockBudgetIsSharedByAllRootDocuments() {
    StringBuilder source = new StringBuilder(TITLE);
    for (int index = 0; index < 1_200; index++) {
      source.append("Paragraph ").append(index).append(".\n\n");
    }
    roots(
        template(7L, "first.mdx", source.toString()),
        template(8L, "second.mdx", source.toString()));

    var result = service.getDocuments(1L);

    assertThat(result.documents()).hasSize(2);
    assertThat(result.documents().getFirst().blocks()).hasSize(1_201);
    assertThat(blocks(result).count()).isEqualTo(ReviewProjectDocumentService.MAX_EXPANDED_BLOCKS);
    assertThat(result.documents().get(1).warnings())
        .anyMatch(warning -> warning.contains("block limit"));
  }

  @Test
  public void rootExcludedByGlobalBudgetDoesNotRecommendPushingItsExistingTemplate() {
    StringBuilder source = new StringBuilder(TITLE);
    for (int index = 0; index < ReviewProjectDocumentService.MAX_EXPANDED_BLOCKS; index++) {
      source.append("Paragraph ").append(index).append(".\n\n");
    }
    var excluded = template(8L, "second.mdx", TITLE);
    roots(template(7L, "first.mdx", source.toString()), excluded);

    var result = service.getDocuments(1L);

    assertThat(result.documents()).hasSize(1);
    assertThat(result.warnings()).anyMatch(warning -> warning.contains("block limit"));
    assertThat(result.warnings())
        .noneMatch(warning -> warning.contains("push the original MDX file"));
    verify(blobs, never()).get(8L, 3L, excluded.contentMd5(), false);
  }

  @Test
  public void cachedSiblingModulesCountAgainTowardTheGlobalRenderedTextBudget() {
    String childSource = "x".repeat(900_000);
    var child = template(9L, "module.mdx", childSource);
    when(templates.findDocumentTemplate(2L, 3L, "module.mdx")).thenReturn(Optional.of(child));
    String parent = "import Module from './module.mdx'\n\n" + TITLE + "<Module />\n\n".repeat(3);
    roots(template(7L, "first.mdx", parent), template(8L, "second.mdx", parent));

    var result = service.getDocuments(1L);

    assertThat(result.documents()).hasSize(2);
    assertThat(blocks(result).filter(block -> block.assetId().equals(9L)).count()).isEqualTo(5);
    assertThat(blocks(result).mapToLong(block -> block.source().length()).sum())
        .isLessThanOrEqualTo(ReviewProjectDocumentService.MAX_EMITTED_CHARACTERS);
    assertThat(result.documents().get(1).warnings())
        .anyMatch(warning -> warning.contains("rendered text size limit"));
    verify(blobs, times(1)).get(9L, 3L, child.contentMd5(), false);
    verify(templates, times(1)).findDocumentTemplate(2L, 3L, "module.mdx");
  }

  @Test
  public void rejectedOversizedModulesStillConsumeTheLoadedSourceBudget() {
    int references = 10;
    String source = imports(references) + TITLE + components(references);
    String oversized = "x".repeat(ReviewProjectDocumentService.MAX_TEMPLATE_CHARACTERS + 1);
    roots(template(7L, "page.mdx", source));
    for (int index = 0; index < references; index++) {
      var child = template(100L + index, "module" + index + ".mdx", oversized);
      when(templates.findDocumentTemplate(2L, 3L, child.assetPath()))
          .thenReturn(Optional.of(child));
    }

    var result = service.getDocuments(1L);

    int possibleReads =
        (ReviewProjectDocumentService.MAX_TOTAL_TEMPLATE_CHARACTERS
                - source.length()
                + oversized.length()
                - 1)
            / oversized.length();
    verify(blobs, times(1 + possibleReads)).get(anyLong(), anyLong(), anyString(), eq(false));
    verify(blobs, never()).get(eq(100L + possibleReads), anyLong(), anyString(), eq(false));
    assertThat(blocks(result)).allMatch(block -> block.assetId().equals(7L));
    assertThat(result.documents().getFirst().warnings())
        .anyMatch(warning -> warning.contains("total source size limit"));
  }

  @Test
  public void warmTemplatesStillConsumeEachRequestsTotalSourceBudget() {
    var documents =
        Stream.iterate(7L, id -> id + 1)
            .limit(6)
            .map(id -> template(id, "page" + id + ".mdx", TITLE + "x".repeat(900_000)))
            .toArray(ReviewProjectDocumentTemplate[]::new);
    for (var document : documents) {
      assertThat(service.renderRepositoryDocument(document).view().documents()).hasSize(1);
    }
    roots(documents);

    var result = service.getDocuments(1L);

    assertThat(result.documents()).hasSize(5);
    assertThat(result.warnings()).anyMatch(warning -> warning.contains("total source size limit"));
    verify(blobs, times(6)).get(anyLong(), anyLong(), anyString(), eq(false));
  }

  private static Stream<ReviewProjectDocumentView.Block> blocks(ReviewProjectDocumentView result) {
    return result.documents().stream().flatMap(document -> document.blocks().stream());
  }

  private static String imports(int count) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < count; index++) {
      result
          .append("import Module")
          .append(index)
          .append(" from './module")
          .append(index)
          .append(".mdx'\n");
    }
    return result.append('\n').toString();
  }

  private static String components(int count) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < count; index++) {
      result.append("<Module").append(index).append(" />\n\n");
    }
    return result.toString();
  }

  private ReviewProjectDocumentTemplate template(long assetId, String path, String source) {
    String md5 = DigestUtils.md5Hex(source);
    when(blobs.get(assetId, 3L, md5, false)).thenReturn(Optional.of(source));
    return new ReviewProjectDocumentTemplate(assetId, path, 2L, 3L, "master", md5);
  }

  private void roots(ReviewProjectDocumentTemplate... sources) {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(
            Stream.of(sources)
                .map(
                    source ->
                        new ReviewProjectDocumentTextUnit(
                            source.assetId() + 10,
                            source.assetId() + 100,
                            "title",
                            "Welcome",
                            source.assetId(),
                            source.assetPath(),
                            source.repositoryId()))
                .toList());
    when(templates.findDocumentTemplatesByProjectId(
            1L, PageRequest.of(0, ReviewProjectDocumentService.MAX_DOCUMENTS + 1)))
        .thenReturn(List.of(sources));
  }
}
