package com.box.l10n.mojito.service.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

public class ReviewProjectDocumentMf2Test {
  private static final String HEADING = "{/* mojito-id: title */}\n# Page\n\n";
  private static final String REF =
      "<PreviewMessage resource=\"./messages.mf2.json\" name=\"calendar.summary\" args='{\"count\":2}' />\n";
  private final ReviewProjectRepository projects = mock(ReviewProjectRepository.class);
  private final ReviewProjectService access = mock(ReviewProjectService.class);
  private final ReviewProjectTextUnitRepository rows = mock(ReviewProjectTextUnitRepository.class);
  private final AssetExtractionByBranchRepository templates =
      mock(AssetExtractionByBranchRepository.class);
  private final AssetContentBlobStorage blobs = mock(AssetContentBlobStorage.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ReviewProjectDocumentService service =
      new ReviewProjectDocumentService(projects, access, rows, templates, blobs, transactions);

  @Before
  public void setup() {
    when(projects.findById(1L)).thenReturn(Optional.of(new ReviewProject()));
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    projectRows("Hello {$count}");
  }

  @Test
  public void repeatedReferencesMapToCatalogIdentityAndRetainDifferentSamples() {
    root(REF + REF.replace("2}", "5}"));
    var catalog = catalog("{\"calendar.summary\":\"Hello {$count}\"}");
    var view = service.getDocuments(1L);
    assertThat(view.warnings()).isEmpty();
    var doc = view.documents().getFirst();
    assertThat(doc.warnings()).isEmpty();
    var messages = doc.blocks().stream().filter(block -> "mf2".equals(block.type())).toList();
    assertThat(messages)
        .hasSize(2)
        .allSatisfy(
            block -> {
              assertThat(block.assetId()).isEqualTo(20L);
              assertThat(block.assetPath()).isEqualTo("docs/messages.mf2.json");
              assertThat(block.id()).isEqualTo("calendar.summary");
              assertThat(block.mappingStatus()).isEqualTo(MappingStatus.MATCHED);
              assertThat(block.tmTextUnitId()).isEqualTo(200L);
              assertThat(block.reviewProjectTextUnitId()).isEqualTo(202L);
            });
    assertThat(messages.get(0).previewArgs()).isEqualTo(Map.of("count", 2));
    assertThat(messages.get(1).previewArgs()).isEqualTo(Map.of("count", 5));
    assertThat(messages.get(0).occurrenceId()).isNotEqualTo(messages.get(1).occurrenceId());
    verify(blobs).get(20L, 3L, catalog.contentMd5(), false);
  }

  @Test
  public void changedCatalogIsFreshDespiteWarmPageAndNeverMapsOldSource() {
    root(REF);
    var original = catalog("{\"calendar.summary\":\"Hello {$count}\"}");
    service.getDocuments(1L);
    var updated = catalog("{\"calendar.summary\":\"Updated {$count}\"}");
    var block = service.getDocuments(1L).documents().getFirst().blocks().getLast();
    assertThat(block.source()).isEqualTo("Updated {$count}");
    assertThat(block.mappingStatus()).isEqualTo(MappingStatus.SOURCE_CHANGED);
    assertThat(block.reviewProjectTextUnitId()).isNull();
    verify(blobs).get(20L, 3L, original.contentMd5(), false);
    verify(blobs).get(20L, 3L, updated.contentMd5(), false);
    verify(access, times(2)).assertCurrentUserCanReadProject(any());
  }

  @Test
  public void absentWrongScopeAndCorruptCatalogsRemainReadOnly() {
    root(REF);
    var catalog = catalog("{\"calendar.summary\":\"Hello {$count}\"}");
    when(blobs.get(20L, 3L, catalog.contentMd5(), false)).thenReturn(Optional.of("corrupt"));
    assertUnavailable();
    when(templates.findDocumentTemplate(1L, 3L, "docs/messages.mf2.json"))
        .thenReturn(
            Optional.of(
                new ReviewProjectDocumentTemplate(
                    20L, "docs/messages.mf2.json", 2L, 3L, "main", catalog.contentMd5())));
    assertUnavailable();
    when(templates.findDocumentTemplate(1L, 3L, "docs/messages.mf2.json"))
        .thenReturn(Optional.empty());
    assertUnavailable();
    verify(blobs, times(1)).get(20L, 3L, catalog.contentMd5(), false);
  }

  @Test
  public void externalEscapingAndWrongExtensionPathsNeverReadMetadata() {
    for (String path :
        List.of(
            "../../secret.mf2.json",
            "/messages.mf2.json",
            "https://host/messages.mf2.json",
            "./messages.json",
            "./messages%2emf2.json")) {
      root(REF.replace("./messages.mf2.json", path));
      assertUnavailable();
    }
    verify(templates, never()).findDocumentTemplate(any(), any(), any());
  }

  @Test
  public void unmatchedCatalogMessageIsContextAndCountsAgainstExpansionBudget() {
    root(REF.repeat(105));
    catalog("{\"calendar.summary\":\"Other {$count}\"}");
    var doc = service.getDocuments(1L).documents().getFirst();
    assertThat(doc.blocks().stream().filter(block -> "mf2".equals(block.type())))
        .hasSize(ReviewProjectDocumentService.MAX_MODULES - 1)
        .allSatisfy(
            block -> {
              assertThat(block.mappingStatus()).isEqualTo(MappingStatus.SOURCE_CHANGED);
              assertThat(block.reviewProjectTextUnitId()).isNull();
            });
    assertThat(doc.blocks().getLast().moduleWarning()).contains("module limit");
  }

  @Test
  public void repositoryRendererReturnsCatalogSnapshotForExactSavedTargetLookup() {
    var root = root(REF);
    var catalog = catalog("{\"calendar.summary\":\"Hello {$count}\"}");
    var rendered = service.renderRepositoryDocument(root);
    assertThat(rendered.templates()).containsExactly(root, catalog);
    var block = rendered.view().documents().getFirst().blocks().getLast();
    assertThat(block.assetId()).isEqualTo(20L);
    assertThat(block.translatable()).isTrue();
    assertThat(block.previewArgs()).containsEntry("count", 2);
    assertThat(block.reviewProjectTextUnitId()).isNull();
  }

  private void assertUnavailable() {
    var doc = service.getDocuments(1L).documents().getFirst();
    var block = doc.blocks().getLast();
    assertThat(block.translatable()).isFalse();
    assertThat(block.mappingStatus()).isEqualTo(MappingStatus.CONTEXT);
    assertThat(block.reviewProjectTextUnitId()).isNull();
    assertThat(block.moduleWarning()).isNotBlank();
    assertThat(doc.warnings()).isNotEmpty();
  }

  private void projectRows(String catalogSource) {
    when(rows.findDocumentTextUnitsByProjectId(1L, 10_000, PageRequest.of(0, 2_001)))
        .thenReturn(
            List.of(
                new ReviewProjectDocumentTextUnit(
                    101L, 100L, "title", "Page", 10L, "docs/page.mdx", 1L),
                new ReviewProjectDocumentTextUnit(
                    202L,
                    200L,
                    "calendar.summary",
                    catalogSource,
                    20L,
                    "docs/messages.mf2.json",
                    1L)));
  }

  private ReviewProjectDocumentTemplate root(String body) {
    String source = HEADING + body;
    var template =
        new ReviewProjectDocumentTemplate(
            10L, "docs/page.mdx", 1L, 3L, "main", DigestUtils.md5Hex(source));
    when(templates.findDocumentTemplatesByProjectId(eq(1L), any())).thenReturn(List.of(template));
    when(blobs.get(10L, 3L, template.contentMd5(), false)).thenReturn(Optional.of(source));
    return template;
  }

  private ReviewProjectDocumentTemplate catalog(String content) {
    var template =
        new ReviewProjectDocumentTemplate(
            20L, "docs/messages.mf2.json", 1L, 3L, "main", DigestUtils.md5Hex(content));
    when(templates.findDocumentTemplate(1L, 3L, template.assetPath()))
        .thenReturn(Optional.of(template));
    when(blobs.get(20L, 3L, template.contentMd5(), false)).thenReturn(Optional.of(content));
    return template;
  }
}
