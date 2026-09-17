package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import java.util.List;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

public class ReviewProjectDocumentServiceTest {
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
  private final ReviewProject project = new ReviewProject();

  @Before
  public void setUp() {
    project.setId(1L);
    when(projects.findById(1L)).thenReturn(Optional.of(project));
    when(transactions.getTransaction(any())).thenReturn(transaction);
  }

  @Test
  public void authorizationRunsBeforeReadingAnyDocumentContent() {
    doThrow(new AccessDeniedException("denied"))
        .when(projectService)
        .assertCurrentUserCanReadProject(project);
    assertThatThrownBy(() -> service.getDocuments(1L)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(rows, templates, blobs);
  }

  @Test
  public void authorizationStillRunsAfterTemplateCacheIsWarm() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    var template = template("master", "{/* mojito-id: title */}\n# Welcome\n");
    templateResults(List.of(template));
    assertThat(service.getDocuments(1L).documents()).hasSize(1);
    doThrow(new AccessDeniedException("denied"))
        .when(projectService)
        .assertCurrentUserCanReadProject(project);

    assertThatThrownBy(() -> service.getDocuments(1L)).isInstanceOf(AccessDeniedException.class);

    verify(projectService, times(2)).assertCurrentUserCanReadProject(project);
    verify(rows).findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001));
    verify(blobs).get(7L, 3L, template.contentMd5(), false);
  }

  @Test
  public void unknownProjectDoesNotLoadTemplates() {
    assertThatThrownBy(() -> service.getDocuments(999L))
        .isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(projectService, rows, templates, blobs);
  }

  @Test
  public void projectWithoutMdxRowsDoesNotLoadTemplates() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of());
    assertThat(service.getDocuments(1L).documents()).isEmpty();
    verifyNoInteractions(templates, blobs);
  }

  @Test
  public void capsProjectRowsBeforeRenderingAndReportsOmissions() {
    var found = new java.util.ArrayList<ReviewProjectDocumentTextUnit>();
    for (int index = 0; index < 2_001; index++) {
      found.add(row(index + 1L, "row" + index, "Source " + index));
    }
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(found);
    source("master", "{/* mojito-id: row2000 */}\n# Source 2000\n");

    var result = service.getDocuments(1L);

    assertThat(result.warnings())
        .anyMatch(warning -> warning.contains("first 2000 project strings"));
    assertThat(result.documents().getFirst().blocks().getFirst().reviewProjectTextUnitId())
        .isNull();
  }

  @Test
  public void oversizedProjectSourceKeepsItsPageAsReadOnlyContext() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", null)));
    source("master", "{/* mojito-id: title */}\n# Welcome\n");

    var result = service.getDocuments(1L);

    assertThat(result.warnings()).anyMatch(warning -> warning.contains("longer than 10000"));
    assertThat(result.documents().getFirst().blocks().getFirst().reviewProjectTextUnitId())
        .isNull();
  }

  @Test
  public void preservesDocumentOrderAndMatchesSourceAndRowIdentity() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(
            List.of(row(12L, "body", "Read the whole page."), row(11L, "title", "Welcome")));
    source(
        "master",
        "{/* mojito-id: title */}\n# Welcome\n\n{/* mojito-id: body */}\nRead the whole page.\n");

    var document = service.getDocuments(1L).documents().getFirst();

    assertThat(document.assetId()).isEqualTo(7L);
    assertThat(document.branchName()).isEqualTo("master");
    assertThat(document.blocks())
        .extracting(ReviewProjectDocumentView.Block::type)
        .containsExactly("heading", "paragraph");
    assertThat(document.blocks())
        .extracting(ReviewProjectDocumentView.Block::reviewProjectTextUnitId)
        .containsExactly(11L, 12L);
    assertThat(document.blocks())
        .extracting(ReviewProjectDocumentView.Block::tmTextUnitId)
        .containsExactly(111L, 112L);
    assertThat(document.blocks()).allMatch(block -> block.mappingStatus() == MappingStatus.MATCHED);
    assertThat(document.warnings()).isEmpty();
  }

  @Test
  public void changedAndMissingProjectSegmentsStayReadOnly() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Previous heading")));
    source(
        "master",
        "{/* mojito-id: title */}\n# Updated heading\n\n{/* mojito-id: context */}\nExtra context.\n");

    var document = service.getDocuments(1L).documents().getFirst();

    assertThat(document.blocks())
        .extracting(ReviewProjectDocumentView.Block::mappingStatus)
        .containsExactly(MappingStatus.SOURCE_CHANGED, MappingStatus.NOT_IN_PROJECT);
    assertThat(document.blocks()).allMatch(block -> block.reviewProjectTextUnitId() == null);
    assertThat(document.warnings()).hasSize(2);
  }

  @Test
  public void duplicateRowsCannotSelectAnArbitraryReviewIdentity() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome"), row(12L, "title", "Welcome")));
    source("master", "{/* mojito-id: title */}\n# Welcome\n");

    var block = service.getDocuments(1L).documents().getFirst().blocks().getFirst();

    assertThat(block.mappingStatus()).isEqualTo(MappingStatus.SOURCE_CHANGED);
    assertThat(block.reviewProjectTextUnitId()).isNull();
  }

  @Test
  public void branchesRemainSeparateAndDoNotBorrowAnotherBranchsSource() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    templateResults(
        List.of(
            template("master", "{/* mojito-id: title */}\n# Welcome\n"),
            template("feature", "{/* mojito-id: title */}\n# New heading\n")));

    var result = service.getDocuments(1L);

    assertThat(result.documents())
        .extracting(ReviewProjectDocumentView.Document::branchName)
        .containsExactly("master", "feature");
    assertThat(result.documents().get(0).blocks().getFirst().mappingStatus())
        .isEqualTo(MappingStatus.MATCHED);
    assertThat(result.documents().get(1).blocks().getFirst().mappingStatus())
        .isEqualTo(MappingStatus.SOURCE_CHANGED);
  }

  @Test
  public void reportsMissingTemplateWithoutFabricatingDocumentOrder() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    templateResults(List.of());

    var result = service.getDocuments(1L);

    assertThat(result.documents()).isEmpty();
    assertThat(result.warnings()).anyMatch(message -> message.contains("push the original MDX"));
  }

  @Test
  public void doesNotUseTemplateFromADifferentExtractionRepositoryOrAsset() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    var source = template("master", "{/* mojito-id: title */}\n# Welcome\n");
    templateResults(
        List.of(
            new ReviewProjectDocumentTemplate(
                7L, "page.mdx", 9L, 3L, "master", source.contentMd5()),
            new ReviewProjectDocumentTemplate(
                99L, "other.mdx", 2L, 3L, "master", source.contentMd5()),
            new ReviewProjectDocumentTemplate(7L, "page.mdx", 2L, 3L, "master", "stale")));

    assertThat(service.getDocuments(1L).documents()).isEmpty();
  }

  @Test
  public void unsafeTemplateIsNotInterpretedAsExecutableMdx() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    source("master", "{fetch('https://example.com')}\n");

    var result = service.getDocuments(1L);

    assertThat(result.documents()).isEmpty();
    assertThat(result.warnings()).anyMatch(message -> message.contains("unsupported MDX"));
  }

  @Test
  public void catalogOnlyBlobDoesNotStandInForAnOriginalDocument() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    var template = template("master", "{/* mojito-id: title */}\n# Welcome\n");
    templateResults(List.of(template));
    when(blobs.get(7L, 3L, template.contentMd5(), false)).thenReturn(Optional.empty());
    when(blobs.get(7L, 3L, template.contentMd5(), true)).thenReturn(Optional.of("[]"));

    var result = service.getDocuments(1L);

    assertThat(result.documents()).isEmpty();
    assertThat(result.warnings()).anyMatch(message -> message.contains("unavailable"));
  }

  @Test
  public void extractionWithoutSuccessfulContentFingerprintIsNotRead() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    templateResults(
        List.of(new ReviewProjectDocumentTemplate(7L, "page.mdx", 2L, 3L, "master", null)));

    assertThat(service.getDocuments(1L).documents()).isEmpty();
    verifyNoInteractions(blobs);
  }

  @Test
  public void blobNetworkReadStartsAfterMetadataTransactionHasCompleted() {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(List.of(row(11L, "title", "Welcome")));
    var template = template("master", "{/* mojito-id: title */}\n# Welcome\n");
    templateResults(List.of(template));

    service.getDocuments(1L);

    var order = inOrder(templates, transactions, blobs);
    order.verify(templates).findDocumentTemplatesByProjectId(1L, PageRequest.of(0, 101));
    order.verify(transactions).commit(transaction);
    order.verify(blobs).get(7L, 3L, template.contentMd5(), false);
  }

  private ReviewProjectDocumentTextUnit row(long rowId, String name, String source) {
    return new ReviewProjectDocumentTextUnit(rowId, rowId + 100, name, source, 7L, "page.mdx", 2L);
  }

  private ReviewProjectDocumentTemplate template(String branch, String source) {
    String md5 = DigestUtils.md5Hex(source);
    Long branchId = "master".equals(branch) ? 3L : 4L;
    when(blobs.get(7L, branchId, md5, false)).thenReturn(Optional.of(source));
    return new ReviewProjectDocumentTemplate(7L, "page.mdx", 2L, branchId, branch, md5);
  }

  private void source(String branch, String source) {
    templateResults(List.of(template(branch, source)));
  }

  private void templateResults(List<ReviewProjectDocumentTemplate> sources) {
    when(templates.findDocumentTemplatesByProjectId(
            eq(1L), eq(PageRequest.of(0, ReviewProjectDocumentService.MAX_DOCUMENTS + 1))))
        .thenReturn(sources);
  }
}
