package com.box.l10n.mojito.service.cms;

import static com.box.l10n.mojito.entity.TMTextUnitVariant.Status.APPROVED;
import static com.box.l10n.mojito.entity.TMTextUnitVariant.Status.TRANSLATION_NEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.cms.CmsContentEntry;
import com.box.l10n.mojito.entity.cms.CmsContentEntryVariant;
import com.box.l10n.mojito.entity.cms.CmsContentFieldMapping;
import com.box.l10n.mojito.entity.cms.CmsContentProject;
import com.box.l10n.mojito.entity.cms.CmsContentTypeField;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshot;
import com.box.l10n.mojito.entity.cms.CmsPublishSnapshotSeal;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.security.Role;
import com.box.l10n.mojito.security.UserDetailsImpl;
import com.box.l10n.mojito.service.DBUtils;
import com.box.l10n.mojito.service.asset.AssetRepository;
import com.box.l10n.mojito.service.asset.AssetService;
import com.box.l10n.mojito.service.asset.CmsManagedVirtualAssetMutationException;
import com.box.l10n.mojito.service.asset.VirtualAsset;
import com.box.l10n.mojito.service.asset.VirtualAssetService;
import com.box.l10n.mojito.service.asset.VirtualAssetTextUnit;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionService;
import com.box.l10n.mojito.service.assetExtraction.AssetTextUnitToTMTextUnitRepository;
import com.box.l10n.mojito.service.assetExtraction.ServiceTestBase;
import com.box.l10n.mojito.service.assetcontent.AssetContentService;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.repository.RepositoryService;
import com.box.l10n.mojito.service.security.user.UserRepository;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantMutationLockService;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.test.TestIdWatcher;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.codec.digest.DigestUtils;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.Hibernate;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(
    properties = {
      "l10n.org.multi-quartz.enabled=false",
      "l10n.org.quartz.scheduler.enabled=false",
      "l10n.content-cms.snapshot-signing-key-id=test-v1",
      "l10n.content-cms.snapshot-signing-keys.test-v1=test-content-cms-snapshot-signing-key-0001"
    })
public class CmsContentServiceIntegrationTest extends ServiceTestBase {

  @Autowired CmsContentService cmsContentService;

  @Autowired CmsContentConfigurationProperties contentCmsConfigurationProperties;

  @Autowired RepositoryService repositoryService;

  @Autowired RepositoryRepository repositoryRepository;

  @Autowired LocaleService localeService;

  @Autowired TMService tmService;

  @Autowired TMTextUnitCurrentVariantRepository tmTextUnitCurrentVariantRepository;

  @Autowired
  TMTextUnitCurrentVariantMutationLockService tmTextUnitCurrentVariantMutationLockService;

  @Autowired TMTextUnitCurrentVariantService tmTextUnitCurrentVariantService;

  @Autowired AssetRepository assetRepository;

  @Autowired AssetService assetService;

  @Autowired VirtualAssetService virtualAssetService;

  @Autowired VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService;

  @Autowired AssetContentService assetContentService;

  @Autowired AssetExtractionService assetExtractionService;

  @Autowired AssetTextUnitToTMTextUnitRepository assetTextUnitToTMTextUnitRepository;

  @Autowired CmsContentProjectRepository projectRepository;

  @Autowired CmsContentEntryRepository entryRepository;

  @Autowired CmsContentTypeFieldRepository fieldRepository;

  @Autowired CmsPublishSnapshotRepository snapshotRepository;

  @Autowired CmsPublishSnapshotSealRepository snapshotSealRepository;

  @Autowired CmsSnapshotSigningService snapshotSigningService;

  @Autowired UserRepository userRepository;

  @Autowired UserService userService;

  @Autowired EntityManager entityManager;

  @Autowired DBUtils dbUtils;

  @Autowired TransactionTemplate transactionTemplate;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired ObjectMapper objectMapper;

  @Rule public TestIdWatcher testIdWatcher = new TestIdWatcher();

  @Test
  public void createsDedicatedRepositoryWhenProjectRepositoryIsNotSelected() {
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "autocreated-space", "Autocreated space", null, true, null, null, "BLOB_CDN"));

    Repository repository =
        repositoryRepository.findById(detail.project().repository().id()).orElseThrow();

    assertAdminAudit(detail.project().audit());
    assertThat(detail.project().repository().name()).isEqualTo("cms-autocreated-space");
    assertThat(detail.project().asset().path()).isEqualTo("cms/autocreated-space");
    assertThat(detail.project().sourceLocale()).isEqualTo("en");
    assertThat(repository.getName()).isEqualTo("cms-autocreated-space");
    assertThat(repository.getDescription())
        .isEqualTo("CMS backing repository for Autocreated space");
    assertThat(repository.getHidden()).isFalse();
  }

  @Test
  public void addsTargetLocalesInsideCmsWritingSpace() {
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "localized-space", "Localized space", null, true, null, null, "BLOB_CDN"));
    Long projectId = detail.project().id();

    cmsContentService.addTargetLocales(
        projectId, new CmsContentService.TargetLocalesCommand(List.of("ja-JP", "fr-FR")));
    cmsContentService.addTargetLocales(
        projectId, new CmsContentService.TargetLocalesCommand(List.of("fr-FR")));

    entityManager.clear();
    Repository repository =
        repositoryRepository.findById(detail.project().repository().id()).orElseThrow();
    assertThat(repositoryService.getRepositoryLocalesWithoutRootLocale(repository))
        .extracting(repositoryLocale -> repositoryLocale.getLocale().getBcp47Tag())
        .containsExactlyInAnyOrder("fr-FR", "ja-JP");
    assertThatThrownBy(
            () ->
                cmsContentService.addTargetLocales(
                    projectId, new CmsContentService.TargetLocalesCommand(List.of("en"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not include the source locale");
    assertThatThrownBy(
            () ->
                cmsContentService.addTargetLocales(
                    projectId, new CmsContentService.TargetLocalesCommand(List.of("xx-XX"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown locale: xx-XX");
  }

  @Test
  public void getFieldTranslationOpensConfiguredUntranslatedTargetRow() {
    String projectKey =
        "translation-row-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "Translation row space", null, true, null, null, "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.addTargetLocales(
            projectId, new CmsContentService.TargetLocalesCommand(List.of("fr-FR")));
    detail =
        cmsContentService.createFirstCopyBlock(
            projectId,
            new CmsContentService.FirstCopyBlockCommand(
                "welcome", "Welcome", "Signup email", "headline", "Welcome", "Signup headline"));
    CmsContentService.FieldMappingView mapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);

    TextUnitDTO frenchTranslation = cmsContentService.getFieldTranslation(mapping.id(), "fr-FR");

    assertThat(frenchTranslation.getTmTextUnitId()).isEqualTo(mapping.tmTextUnitId());
    assertThat(frenchTranslation.getTargetLocale()).isEqualTo("fr-FR");
    assertThat(frenchTranslation.getTarget()).isNull();
    assertThat(frenchTranslation.getStatus()).isEqualTo(TRANSLATION_NEEDED);
  }

  @Test
  public void createsMojitoBackedEntryAndPublishesReadyArtifact() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "growth-email", "Growth email", null, true, repository.getId(), null, "BLOB_CDN"));
    assertAdminAudit(detail.project().audit());
    Long projectId = detail.project().id();

    Asset asset = assetRepository.findById(detail.project().asset().id()).orElseThrow();
    assertThat(asset.getLastSuccessfulAssetExtraction()).isNotNull();
    VirtualAssetTextUnit unmanagedTextUnit = new VirtualAssetTextUnit();
    unmanagedTextUnit.setName("outside-cms");
    unmanagedTextUnit.setContent("Outside CMS");
    unmanagedTextUnit.setComment("Outside CMS");
    assertThatThrownBy(
            () -> virtualAssetService.addTextUnits(asset.getId(), List.of(unmanagedTextUnit)))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(
            () ->
                virtualTextUnitBatchUpdaterService.updateTextUnits(
                    asset, List.of(unmanagedTextUnit), false))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(
            () ->
                tmService.addTMTextUnit(
                    repository.getTm().getId(),
                    asset.getId(),
                    "outside-cms",
                    "Outside CMS",
                    "Outside CMS"))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(() -> assetContentService.createAssetContent(asset, "Outside CMS"))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(
            () ->
                assetExtractionService.createAssetTextUnit(
                    asset.getLastSuccessfulAssetExtraction(),
                    "outside-cms",
                    "Outside CMS",
                    "Outside CMS"))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(() -> assetExtractionService.deleteAssetBranch(asset, null))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(() -> assetExtractionService.createAssetExtraction(asset, null))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(
            () ->
                assetExtractionService.markAssetExtractionAsLastSuccessful(
                    asset, asset.getLastSuccessfulAssetExtraction()))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    VirtualAsset cmsVirtualAsset = new VirtualAsset();
    cmsVirtualAsset.setRepositoryId(repository.getId());
    cmsVirtualAsset.setPath(asset.getPath());
    cmsVirtualAsset.setDeleted(Boolean.TRUE);
    assertThatThrownBy(() -> virtualAssetService.createOrUpdateVirtualAsset(cmsVirtualAsset))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(() -> assetService.deleteAsset(asset))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThatThrownBy(
            () -> assetService.asyncDeleteAssetsOfBranch(java.util.Set.of(asset.getId()), 999L))
        .isInstanceOf(CmsManagedVirtualAssetMutationException.class)
        .hasMessageContaining("/api/content-cms endpoints");
    assertThat(assetRepository.findById(asset.getId()).orElseThrow().getDeleted()).isFalse();

    detail =
        cmsContentService.createContentType(
            projectId,
            new CmsContentService.ContentTypeCommand(
                "email",
                "Email",
                null,
                1,
                "{\"type\":\"object\",\"required\":[\"surface\"],\"properties\":{\"surface\":{\"type\":\"string\"}}}"));
    assertAdminAudit(detail.contentTypes().get(0).audit());
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header",
                "Header",
                "Translator context",
                CmsContentTypeField.FieldType.TEXT,
                true,
                true,
                0));
    assertAdminAudit(detail.contentTypes().get(0).fields().get(0).audit());
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId,
                "welcome",
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                "{\"surface\":\"email\"}"));
    assertAdminAudit(detail.entries().get(0).audit());
    assertAdminAudit(detail.entries().get(0).variants().get(0).audit());
    Long entryId = detail.entries().get(0).id();
    Long variantId = detail.entries().get(0).variants().get(0).id();
    Long variantVersion = detail.entries().get(0).variants().get(0).entityVersion();
    detail =
        cmsContentService.updateVariant(
            variantId,
            new CmsContentService.VariantUpdateCommand(
                "Default", null, null, "{\"audience\":\"all\"}", 0, variantVersion));
    assertThat(detail.entries().get(0).variants().get(0).metadataJson())
        .isEqualTo("{\"audience\":\"all\"}");
    assertThat(detail.entries().get(0).variants().get(0).entityVersion())
        .isGreaterThan(variantVersion);
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in welcome email header", null));
    assertAdminAudit(detail.entries().get(0).variants().get(0).fieldMappings().get(0).audit());

    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    assertThat(
            assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
                asset.getLastSuccessfulAssetExtraction().getId(), tmTextUnitId))
        .isPresent();
    CmsContentService.FieldMappingView generatedMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId,
                null,
                null,
                "Updated hello",
                "Updated welcome email header",
                generatedMapping.entityVersion()));
    CmsContentService.FieldMappingView updatedGeneratedMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    Long updatedTmTextUnitId = updatedGeneratedMapping.tmTextUnitId();
    assertThat(updatedGeneratedMapping.id()).isEqualTo(generatedMapping.id());
    assertThat(updatedGeneratedMapping.stringId()).isEqualTo(generatedMapping.stringId());
    assertThat(updatedTmTextUnitId).isNotEqualTo(tmTextUnitId);
    assertThat(
            jdbcTemplate.queryForList(
                "select tm_text_unit_id from cms_content_field_mapping_aud where id = ? order by rev",
                Long.class,
                generatedMapping.id()))
        .containsExactly(tmTextUnitId, updatedTmTextUnitId);
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from revchanges changes
                join cms_content_field_mapping_aud audit on audit.rev = changes.rev
                where audit.id = ?
                  and changes.entityname = ?
                """,
                Long.class,
                generatedMapping.id(),
                CmsContentFieldMapping.class.getName()))
        .isEqualTo(2L);
    Asset updatedAsset = assetRepository.findById(asset.getId()).orElseThrow();
    assertThat(
            assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
                updatedAsset.getLastSuccessfulAssetExtraction().getId(), tmTextUnitId))
        .isEmpty();
    assertThat(
            assetTextUnitToTMTextUnitRepository.findIdByAssetExtractionIdAndTmTextUnitId(
                updatedAsset.getLastSuccessfulAssetExtraction().getId(), updatedTmTextUnitId))
        .isPresent();
    tmTextUnitId = updatedTmTextUnitId;
    generatedMapping = updatedGeneratedMapping;
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId,
                null,
                generatedMapping.stringId(),
                null,
                null,
                generatedMapping.entityVersion()));
    assertThat(detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId())
        .isEqualTo(tmTextUnitId);
    CmsContentService.FieldMappingView remappedMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    detail =
        cmsContentService.unmapFieldMapping(
            remappedMapping.id(),
            new CmsContentService.FieldMappingDeleteCommand(remappedMapping.entityVersion()));
    assertThat(detail.entries().get(0).variants().get(0).fieldMappings()).isEmpty();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, generatedMapping.stringId(), null, null, null));
    assertThat(detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId())
        .isEqualTo(tmTextUnitId);

    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project has no ready entries to publish");

    Long entryVersion = detail.entries().get(0).entityVersion();
    detail =
        cmsContentService.updateEntry(
            entryId,
            new CmsContentService.EntryUpdateCommand(
                "Welcome",
                null,
                CmsContentEntry.Status.READY,
                "{\"surface\":\"email\"}",
                entryVersion));
    assertThat(detail.entries().get(0).status()).isEqualTo(CmsContentEntry.Status.READY);
    assertThat(detail.entries().get(0).entityVersion()).isGreaterThan(entryVersion);

    Locale french = localeService.findByBcp47Tag("fr-FR");
    tmService.addCurrentTMTextUnitVariant(tmTextUnitId, french.getId(), "Bonjour", APPROVED, true);

    CmsContentService.EntryCompletenessView completeness =
        cmsContentService.getEntryCompleteness(entryId, List.of("fr-FR"));
    assertThat(completeness.locales()).allMatch(CmsContentService.LocaleCompleteness::complete);
    CmsContentService.ProjectCompletenessView projectCompleteness =
        cmsContentService.getProjectCompleteness(projectId, List.of("fr-FR"));
    assertThat(projectCompleteness.projectKey()).isEqualTo("growth-email");
    assertThat(projectCompleteness.authoringSha256()).isEqualTo(detail.authoringSha256());
    assertThat(projectCompleteness.publishPackageSha256()).hasSize(64);
    assertThat(projectCompleteness.publishPackageByteSize()).isPositive();
    assertThat(projectCompleteness.localeTags()).containsExactly("en", "fr-FR");
    assertThat(projectCompleteness.complete()).isTrue();
    assertThat(projectCompleteness.locales())
        .allMatch(CmsContentService.LocaleCompleteness::complete);
    assertThat(projectCompleteness.entries())
        .extracting(CmsContentService.EntryCompletenessView::entryKey)
        .containsExactly("welcome");
    CmsContentService.PublishCommand stalePackageCommand =
        new CmsContentService.PublishCommand(
            List.of("fr-FR"),
            projectCompleteness.authoringSha256(),
            projectCompleteness.publishPackageSha256());
    tmService.addCurrentTMTextUnitVariant(tmTextUnitId, french.getId(), "Salut", APPROVED, true);
    assertThatThrownBy(
            () -> publishProject(projectId, stalePackageCommand, "publish-stale-package"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("publish package changed");
    tmService.addCurrentTMTextUnitVariant(tmTextUnitId, french.getId(), "Bonjour", APPROVED, true);

    CmsContentService.PublishCommand publishCommand = publishCommand(projectId, List.of());
    CmsContentService.PublishSnapshotView snapshot =
        publishProject(projectId, publishCommand, "publish-growth-email");
    CmsContentService.PublishSnapshotView retriedSnapshot =
        publishProject(projectId, publishCommand, "publish-growth-email");
    assertThat(retriedSnapshot).isEqualTo(snapshot);
    assertThat(cmsContentService.getProject(projectId).publishSnapshots()).hasSize(1);
    CmsContentService.ProjectDetail postPublishDetail = cmsContentService.getProject(projectId);
    assertThat(postPublishDetail.authoringSha256())
        .isEqualTo(publishCommand.expectedAuthoringSha256());
    assertThat(postPublishDetail.project().entityVersion())
        .isEqualTo(detail.project().entityVersion());
    Long postPublishEntryVersion = postPublishDetail.entries().get(0).entityVersion();
    cmsContentService.updateEntry(
        entryId,
        new CmsContentService.EntryUpdateCommand(
            "Updated welcome",
            null,
            CmsContentEntry.Status.READY,
            "{\"surface\":\"email\"}",
            postPublishEntryVersion));
    assertThatThrownBy(() -> publishProject(projectId, publishCommand, "publish-stale-view"))
        .isInstanceOf(CmsContentConflictException.class)
        .hasMessageContaining("authoring revision changed");
    assertThat(publishProject(projectId, publishCommand, "publish-growth-email"))
        .isEqualTo(snapshot);
    assertThat(snapshot.localeTags()).containsExactly("en", "fr-FR");
    assertThat(snapshot.publishRequestLocaleTags()).isEmpty();
    assertThat(snapshot.publishRequestAuthoringSha256())
        .isEqualTo(publishCommand.expectedAuthoringSha256());
    assertThat(snapshot.publishRequestPackageSha256())
        .isEqualTo(publishCommand.expectedPackageSha256());
    CmsContentService.SnapshotArtifact snapshotArtifact =
        cmsContentService.getSnapshotArtifact("growth-email", snapshot.snapshotVersion());
    CmsContentService.SnapshotDeliveryDescriptor latestSnapshot =
        cmsContentService.getLatestPublishedSnapshotDescriptor("growth-email");
    assertThat(snapshot.artifactSha256()).isEqualTo(snapshotArtifact.artifactSha256());
    assertThat(snapshot.snapshotSigningKeyId()).isEqualTo("test-v1");
    assertThat(snapshot.snapshotSignature()).isEqualTo(snapshotArtifact.snapshotSignature());
    assertThat(snapshot.artifactSignature()).isEqualTo(snapshotArtifact.artifactSignature());
    assertThat(snapshot.createdByUsername()).isEqualTo("admin");
    assertThat(latestSnapshot.formatVersion())
        .isEqualTo("mojito.microCms.snapshot-delivery-descriptor.v1");
    assertThat(latestSnapshot.projectKey()).isEqualTo("growth-email");
    assertThat(latestSnapshot.snapshotVersion()).isEqualTo(snapshot.snapshotVersion());
    assertThat(latestSnapshot.projectHint()).isEqualTo("BLOB_CDN");
    assertThat(latestSnapshot.artifactSha256()).isEqualTo(snapshot.artifactSha256());
    assertThat(latestSnapshot.snapshotSignature()).isEqualTo(snapshot.snapshotSignature());
    assertThat(latestSnapshot.artifactSignature()).isEqualTo(snapshot.artifactSignature());
    assertThat(latestSnapshot.artifactExportPath()).isEqualTo(snapshot.artifactExportPath());
    assertThat(latestSnapshot.publishedAt()).isEqualTo(snapshot.publishedAt());
    CmsContentService.ProjectDetail publishedDetail = cmsContentService.getProject(projectId);
    assertThat(publishedDetail.publishSnapshots())
        .extracting(CmsContentService.PublishSnapshotView::id)
        .containsExactly(snapshot.id());
    assertThat(publishedDetail.publishSnapshots().get(0).artifactExportPath())
        .isEqualTo(snapshot.artifactExportPath());
    assertThat(snapshot.artifactByteSize())
        .isEqualTo((long) snapshotArtifact.artifactJson().getBytes(StandardCharsets.UTF_8).length);
    assertThat(snapshotArtifact.filename()).isEqualTo("growth-email.v1.json");
    assertThat(snapshotArtifact.artifactSha256())
        .isEqualTo(DigestUtils.sha256Hex(snapshotArtifact.artifactJson()));
    JsonNode artifact = objectMapper.readTree(snapshotArtifact.artifactJson());
    assertThat(artifact.path("generatedAt").asText()).isEqualTo(snapshot.publishedAt());
    assertThat(artifact.path("entries").size()).isEqualTo(1);
    assertThat(
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("header")
                .path("values")
                .path("fr-FR")
                .asText())
        .isEqualTo("Bonjour");
  }

  @Test
  public void createsEntryWithInitialGeneratedFieldMappings() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_initial_mapping_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");
    String projectKey =
        "initial-mapping-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "Initial mapping project", null, true, repository.getId(), null, null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long headerFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "cta", "CTA", null, CmsContentTypeField.FieldType.TEXT, true, true, 1));
    Long ctaFieldId =
        detail.contentTypes().get(0).fields().stream()
            .filter(field -> "cta".equals(field.fieldKey()))
            .findFirst()
            .orElseThrow()
            .id();

    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId,
                "welcome",
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                null,
                List.of(
                    new CmsContentService.InitialFieldMappingCommand(
                        headerFieldId, "Welcome back", "Email header"),
                    new CmsContentService.InitialFieldMappingCommand(
                        ctaFieldId, "Start free", "CTA below header"))));

    CmsContentService.VariantView controlVariant = detail.entries().get(0).variants().get(0);
    assertThat(controlVariant.fieldMappings())
        .extracting(
            CmsContentService.FieldMappingView::fieldKey,
            CmsContentService.FieldMappingView::sourceContent,
            CmsContentService.FieldMappingView::sourceComment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("header", "Welcome back", "Email header"),
            org.assertj.core.groups.Tuple.tuple("cta", "Start free", "CTA below header"));

    detail = markEntryReady(detail);
    Locale french = localeService.findByBcp47Tag("fr-FR");
    CmsContentService.FieldMappingView headerMapping = controlVariant.fieldMappings().get(0);
    CmsContentService.FieldMappingView ctaMapping = controlVariant.fieldMappings().get(1);
    tmService.addCurrentTMTextUnitVariant(
        headerMapping.tmTextUnitId(), french.getId(), "Bienvenue", APPROVED, true);
    tmService.addCurrentTMTextUnitVariant(
        ctaMapping.tmTextUnitId(), french.getId(), "Commencer", TRANSLATION_NEEDED, true);

    CmsContentService.EntryCompletenessView completeness =
        cmsContentService.getEntryCompleteness(detail.entries().get(0).id(), List.of("fr-FR"));
    assertThat(completeness.locales().get(1).translationNeededFields()).isEqualTo(1);
    assertThat(completeness.fields())
        .extracting(CmsContentService.FieldCompleteness::fieldKey)
        .containsExactly("header", "cta");
    assertThat(completeness.fields().get(0).locales().get(1).complete()).isTrue();
    assertThat(completeness.fields().get(1).locales().get(1).translationNeededFields())
        .isEqualTo(1);
  }

  @Test
  public void rollsBackEntryWhenInitialFieldMappingFails() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_initial_mapping_rollback_repository"));
    String projectKey =
        "initial-rollback-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey,
                "Initial mapping rollback project",
                null,
                true,
                repository.getId(),
                null,
                null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long emailTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            emailTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long headerFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long bannerTypeId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .id();
    detail =
        cmsContentService.createContentTypeField(
            bannerTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long bodyFieldId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .fields()
            .get(0)
            .id();

    assertThatThrownBy(
            () ->
                cmsContentService.createEntry(
                    projectId,
                    new CmsContentService.EntryCommand(
                        emailTypeId,
                        "welcome",
                        "Welcome",
                        null,
                        CmsContentEntry.Status.DRAFT,
                        null,
                        List.of(
                            new CmsContentService.InitialFieldMappingCommand(
                                headerFieldId, "Welcome back", "Email header"),
                            new CmsContentService.InitialFieldMappingCommand(
                                bodyFieldId, "Banner body", "Banner body")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Field does not belong to the entry content type: " + bodyFieldId);

    entityManager.clear();
    assertThat(entryRepository.findByProjectIdAndEntryKeyIgnoreCase(projectId, "welcome"))
        .isEmpty();
  }

  @Test
  public void makesOneSharedBlockCopyPiecesPrivateWithoutLosingMojitoSources() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_private_copy_pieces_repository"));
    String projectKey =
        "private-pieces-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "Private pieces project", null, true, repository.getId(), null, null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long sharedContentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            sharedContentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long sharedHeaderFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                sharedContentTypeId,
                "welcome",
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                null,
                List.of(
                    new CmsContentService.InitialFieldMappingCommand(
                        sharedHeaderFieldId, "Welcome back", "Welcome email header"))));
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                sharedContentTypeId,
                "follow-up",
                "Follow-up",
                null,
                CmsContentEntry.Status.DRAFT,
                null,
                List.of(
                    new CmsContentService.InitialFieldMappingCommand(
                        sharedHeaderFieldId,
                        "Start your first project",
                        "Follow-up email header"))));

    CmsContentService.EntryView welcomeBeforeFork =
        detail.entries().stream()
            .filter(entry -> "welcome".equals(entry.entryKey()))
            .findFirst()
            .orElseThrow();
    CmsContentService.FieldMappingView welcomeHeaderBeforeFork =
        welcomeBeforeFork.variants().get(0).fieldMappings().get(0);

    detail =
        cmsContentService.makeEntryCopyPiecesPrivate(
            welcomeBeforeFork.id(),
            new CmsContentService.EntryCopyPiecesPrivateCommand(welcomeBeforeFork.entityVersion()));

    CmsContentService.EntryView welcomeAfterFork =
        detail.entries().stream()
            .filter(entry -> "welcome".equals(entry.entryKey()))
            .findFirst()
            .orElseThrow();
    CmsContentService.EntryView followUpAfterFork =
        detail.entries().stream()
            .filter(entry -> "follow-up".equals(entry.entryKey()))
            .findFirst()
            .orElseThrow();
    CmsContentService.ContentTypeView privateContentType =
        detail.contentTypes().stream()
            .filter(
                contentType -> Objects.equals(contentType.id(), welcomeAfterFork.contentTypeId()))
            .findFirst()
            .orElseThrow();
    CmsContentService.FieldMappingView welcomeHeaderAfterFork =
        welcomeAfterFork.variants().get(0).fieldMappings().get(0);

    assertThat(detail.contentTypes())
        .extracting(CmsContentService.ContentTypeView::typeKey)
        .containsExactlyInAnyOrder("email", "email-welcome-private");
    assertThat(welcomeAfterFork.contentTypeId()).isNotEqualTo(followUpAfterFork.contentTypeId());
    assertThat(privateContentType.fields())
        .extracting(CmsContentService.FieldView::fieldKey)
        .containsExactly("header");
    assertThat(welcomeHeaderAfterFork.stringId()).isEqualTo(welcomeHeaderBeforeFork.stringId());
    assertThat(welcomeHeaderAfterFork.tmTextUnitId())
        .isEqualTo(welcomeHeaderBeforeFork.tmTextUnitId());
    assertThat(welcomeHeaderAfterFork.sourceContent()).isEqualTo("Welcome back");
    assertThat(followUpAfterFork.variants().get(0).fieldMappings().get(0).sourceContent())
        .isEqualTo("Start your first project");

    Long privateVariantId = welcomeAfterFork.variants().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            welcomeAfterFork.contentTypeId(),
            new CmsContentService.ContentTypeFieldCommand(
                "cta",
                "CTA",
                "Button copy",
                CmsContentTypeField.FieldType.TEXT,
                true,
                true,
                1,
                new CmsContentService.InitialFieldSourceCommand(
                    privateVariantId, "Start free", "CTA below welcome header")));
    CmsContentService.ContentTypeView welcomePrivateContentType =
        detail.contentTypes().stream()
            .filter(
                contentType -> Objects.equals(contentType.id(), welcomeAfterFork.contentTypeId()))
            .findFirst()
            .orElseThrow();
    CmsContentService.ContentTypeView followUpSharedContentType =
        detail.contentTypes().stream()
            .filter(
                contentType -> Objects.equals(contentType.id(), followUpAfterFork.contentTypeId()))
            .findFirst()
            .orElseThrow();
    assertThat(welcomePrivateContentType.fields())
        .extracting(CmsContentService.FieldView::fieldKey)
        .containsExactly("header", "cta");
    assertThat(followUpSharedContentType.fields())
        .extracting(CmsContentService.FieldView::fieldKey)
        .containsExactly("header");
  }

  @Test
  public void createsFirstCopyBlockAtomically() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_first_copy_block_repository"));
    String projectKey =
        "first-copy-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "First copy project", null, true, repository.getId(), null, null));

    detail =
        cmsContentService.createFirstCopyBlock(
            detail.project().id(),
            new CmsContentService.FirstCopyBlockCommand(
                "welcome-email",
                "Welcome email",
                "Signup confirmation",
                "copy",
                "Welcome to the product",
                "Headline"));

    assertThat(detail.contentTypes())
        .extracting(CmsContentService.ContentTypeView::typeKey)
        .containsExactly("copy-block");
    assertThat(detail.contentTypes().get(0).fields())
        .extracting(CmsContentService.FieldView::fieldKey, CmsContentService.FieldView::name)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("copy", "Copy"));
    assertThat(detail.entries())
        .extracting(CmsContentService.EntryView::entryKey, CmsContentService.EntryView::name)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("welcome-email", "Welcome email"));
    assertThat(detail.entries().get(0).variants().get(0).fieldMappings())
        .extracting(
            CmsContentService.FieldMappingView::fieldKey,
            CmsContentService.FieldMappingView::sourceContent,
            CmsContentService.FieldMappingView::sourceComment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("copy", "Welcome to the product", "Headline"));
  }

  @Test
  public void rollsBackFirstCopyBlockWhenInitialSourceFails() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_first_copy_block_rollback_repository"));
    String projectKey =
        "first-copy-rollback-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey,
                "First copy rollback project",
                null,
                true,
                repository.getId(),
                null,
                null));
    Long projectId = detail.project().id();

    assertThatThrownBy(
            () ->
                cmsContentService.createFirstCopyBlock(
                    projectId,
                    new CmsContentService.FirstCopyBlockCommand(
                        "welcome-email",
                        "Welcome email",
                        "Signup confirmation",
                        "copy",
                        "Welcome to the product",
                        null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Translator context is required");

    entityManager.clear();
    detail = cmsContentService.getProject(projectId);
    assertThat(detail.contentTypes()).isEmpty();
    assertThat(detail.entries()).isEmpty();
  }

  @Test
  public void createsContentTypeFieldWithInitialGeneratedFieldSource() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_initial_field_source_repository"));
    String projectKey =
        "initial-field-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey,
                "Initial field source project",
                null,
                true,
                repository.getId(),
                null,
                null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();

    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "cta",
                "CTA",
                "Button copy",
                CmsContentTypeField.FieldType.TEXT,
                true,
                true,
                0,
                new CmsContentService.InitialFieldSourceCommand(
                    variantId, "Start free", "CTA below header")));

    assertThat(detail.contentTypes().get(0).fields())
        .extracting(CmsContentService.FieldView::fieldKey)
        .containsExactly("cta");
    assertThat(detail.entries().get(0).variants().get(0).fieldMappings())
        .extracting(
            CmsContentService.FieldMappingView::fieldKey,
            CmsContentService.FieldMappingView::sourceContent,
            CmsContentService.FieldMappingView::sourceComment)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("cta", "Start free", "CTA below header"));
  }

  @Test
  public void rollsBackContentTypeFieldWhenInitialFieldSourceFails() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_initial_field_source_rollback_repository"));
    String projectKey =
        "field-rollback-"
            + DigestUtils.sha256Hex(testIdWatcher.getEntityName("project")).substring(0, 8);
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey,
                "Initial field source rollback project",
                null,
                true,
                repository.getId(),
                null,
                null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();

    assertThatThrownBy(
            () ->
                cmsContentService.createContentTypeField(
                    contentTypeId,
                    new CmsContentService.ContentTypeFieldCommand(
                        "cta",
                        "CTA",
                        "Button copy",
                        CmsContentTypeField.FieldType.TEXT,
                        true,
                        true,
                        0,
                        new CmsContentService.InitialFieldSourceCommand(
                            variantId, "Start free", null))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Translator context is required for mapping: welcome.default.cta");

    entityManager.clear();
    assertThat(fieldRepository.findByContentTypeIdAndFieldKeyIgnoreCase(contentTypeId, "cta"))
        .isEmpty();
  }

  @Test
  public void readyEntryRejectsAuthoringMutationThatBreaksPublishableStructure() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_ready_repository"));

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "ready-banner", "Ready banner", null, true, repository.getId(), null, "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in ready banner header", null));
    detail = markEntryReady(detail);

    CmsContentService.FieldMappingView mapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    assertThatThrownBy(
            () ->
                cmsContentService.unmapFieldMapping(
                    mapping.id(),
                    new CmsContentService.FieldMappingDeleteCommand(mapping.entityVersion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Ready entry has publishable variants without mapped localizable fields: "
                + "welcome.default");

    CmsContentService.ProjectDetail afterRejectedUnmap = cmsContentService.getProject(projectId);
    assertThat(afterRejectedUnmap.entries().get(0).variants().get(0).fieldMappings()).hasSize(1);

    detail =
        cmsContentService.updateEntry(
            entryId,
            new CmsContentService.EntryUpdateCommand(
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                null,
                afterRejectedUnmap.entries().get(0).entityVersion()));
    CmsContentService.FieldMappingView draftMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    detail =
        cmsContentService.unmapFieldMapping(
            draftMapping.id(),
            new CmsContentService.FieldMappingDeleteCommand(draftMapping.entityVersion()));
    assertThat(detail.entries().get(0).variants().get(0).fieldMappings()).isEmpty();
  }

  @Test
  public void requiredFieldSchemaMutationRollsBackWhenReadyEntryWouldBecomeInvalid()
      throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_required_field_rollback_repository"));

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "required-field-rollback",
                "Required field rollback",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long headerFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                headerFieldId, null, "Hello", "Shown in rollback banner header", null));
    detail = markEntryReady(detail);
    assertThat(detail.contentTypes().get(0).schemaVersion()).isEqualTo(2);

    assertThatThrownBy(
            () ->
                cmsContentService.createContentTypeField(
                    contentTypeId,
                    new CmsContentService.ContentTypeFieldCommand(
                        "cta", "CTA", null, CmsContentTypeField.FieldType.TEXT, true, true, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Ready entry has missing required field mappings: welcome.default.cta");

    CmsContentService.ProjectDetail afterRejectedField = cmsContentService.getProject(projectId);
    assertThat(afterRejectedField.contentTypes().get(0).schemaVersion()).isEqualTo(2);
    assertThat(afterRejectedField.contentTypes().get(0).fields())
        .extracting(CmsContentService.FieldView::fieldKey)
        .containsExactly("header");
    assertThat(afterRejectedField.entries().get(0).status())
        .isEqualTo(CmsContentEntry.Status.READY);
  }

  @Test
  public void createContentTypeRollsBackWhenAuthoringDetailExceedsConfiguredByteLimit()
      throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_authoring_detail_rollback_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "authoring-detail-rollback",
                "Authoring detail rollback",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    long originalMaxAuthoringDetailBytes =
        contentCmsConfigurationProperties.getMaxAuthoringDetailBytes();

    try {
      contentCmsConfigurationProperties.setMaxAuthoringDetailBytes(1);
      assertThatThrownBy(
              () ->
                  cmsContentService.createContentType(
                      projectId,
                      new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Content project authoring detail exceeds configured byte limit");
    } finally {
      contentCmsConfigurationProperties.setMaxAuthoringDetailBytes(originalMaxAuthoringDetailBytes);
    }

    assertThat(cmsContentService.getProject(projectId).contentTypes()).isEmpty();
  }

  @Test
  public void publishRejectsMappedTextUnitWithoutMojitoStringIdAfterTextUnitDrift()
      throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_string_id_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "string-id-banner",
                "String ID banner",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in banner header", null));
    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    detail = markEntryReady(detail);
    jdbcTemplate.update("update tm_text_unit set name = ? where id = ?", " \t ", tmTextUnitId);

    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Publishable mapped text units must have usable Mojito string IDs: welcome.default.header");
  }

  @Test
  public void publishRejectsEntryMetadataThatViolatesSchemaAfterStoredDrift() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_metadata_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "metadata-banner",
                "Metadata banner",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId,
            new CmsContentService.ContentTypeCommand(
                "banner",
                "Banner",
                null,
                1,
                "{\"type\":\"object\",\"required\":[\"surface\"],\"properties\":{\"surface\":{\"type\":\"string\"}}}"));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId,
                "welcome",
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                "{\"surface\":\"modal\"}"));
    Long entryId = detail.entries().get(0).id();
    Long variantId = detail.entries().get(0).variants().get(0).id();
    cmsContentService.upsertFieldMapping(
        variantId,
        new CmsContentService.FieldMappingCommand(
            fieldId, null, "Hello", "Shown in banner header", null));
    detail = markEntryReady(detail);
    jdbcTemplate.update(
        "update cms_content_entry set metadata_json = ? where id = ?", "{}", entryId);

    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Entry metadata for welcome is missing required property: surface");
  }

  @Test
  public void publishUsesApprovedParentLocaleTranslationForInheritedLocale() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_inherited_locale_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");
    repositoryService.addRepositoryLocale(repository, "fr-CA", "fr-FR", false);

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "inherited-banner",
                "Inherited banner",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "promo", "Promo", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in promo body", null));
    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    detail = markEntryReady(detail);
    Locale french = localeService.findByBcp47Tag("fr-FR");
    tmService.addCurrentTMTextUnitVariant(tmTextUnitId, french.getId(), "Bonjour", APPROVED, true);

    CmsContentService.PublishSnapshotView snapshot =
        publishProject(projectId, publishCommand(projectId, List.of("fr-CA")));
    JsonNode artifact =
        objectMapper.readTree(
            cmsContentService
                .getSnapshotArtifact("inherited-banner", snapshot.snapshotVersion())
                .artifactJson());

    assertThat(snapshot.localeTags()).containsExactly("en", "fr-CA");
    assertThat(
            artifact
                .path("entries")
                .get(0)
                .path("variants")
                .get(0)
                .path("fields")
                .path("body")
                .path("values")
                .path("fr-CA")
                .asText())
        .isEqualTo("Bonjour");
  }

  @Test
  public void authoringMutationsWriteCmsProjectAuditHistory() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_audit_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "audit-banner", "Audit banner", null, true, repository.getId(), null, "BLOB_CDN"));
    Long projectId = detail.project().id();

    cmsContentService.updateProject(
        projectId,
        new CmsContentService.ProjectUpdateCommand(
            "Audit banner updated", null, true, "BLOB_CDN", detail.project().entityVersion()));

    assertThat(
            jdbcTemplate.queryForList(
                "select name from cms_content_project_aud where id = ? order by rev",
                String.class,
                projectId))
        .containsExactly("Audit banner", "Audit banner updated");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from revchanges changes
                join cms_content_project_aud audit on audit.rev = changes.rev
                where audit.id = ?
                  and changes.entityname = ?
                """,
                Long.class,
                projectId,
                CmsContentProject.class.getName()))
        .isEqualTo(2L);
  }

  @Test
  public void publishTreatsDeletedCurrentTranslationAsIncompleteLocale() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_deleted_locale_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "deleted-locale-banner",
                "Deleted locale banner",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "promo", "Promo", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in promo body", null));
    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    detail = markEntryReady(detail);
    Locale french = localeService.findByBcp47Tag("fr-FR");
    tmService.addCurrentTMTextUnitVariant(tmTextUnitId, french.getId(), "Bonjour", APPROVED, true);
    assertThat(
            tmTextUnitCurrentVariantService.removeCurrentVariant(
                tmTextUnitCurrentVariantRepository
                    .findByLocale_IdAndTmTextUnit_Id(french.getId(), tmTextUnitId)
                    .getId()))
        .isTrue();

    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void sourceEditRequiresReapprovalBeforePublish() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_source_edit_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "source-edit-banner",
                "Source edit banner",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "promo", "Promo", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in promo body", null));
    CmsContentService.FieldMappingView initialMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    Long initialTmTextUnitId = initialMapping.tmTextUnitId();
    detail = markEntryReady(detail);
    Locale french = localeService.findByBcp47Tag("fr-FR");
    tmService.addCurrentTMTextUnitVariant(
        initialTmTextUnitId, french.getId(), "Bonjour", APPROVED, true);
    assertThat(cmsContentService.getProjectCompleteness(projectId, List.of("fr-FR")).complete())
        .isTrue();

    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId,
                null,
                null,
                "Updated hello",
                "Shown in promo body",
                initialMapping.entityVersion()));
    CmsContentService.FieldMappingView updatedMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    assertThat(updatedMapping.tmTextUnitId()).isNotEqualTo(initialTmTextUnitId);
    assertThat(
            tmTextUnitCurrentVariantRepository
                .findByLocale_IdAndTmTextUnit_Id(french.getId(), updatedMapping.tmTextUnitId())
                .getTmTextUnitVariant()
                .getStatus())
        .isEqualTo(TRANSLATION_NEEDED);
    CmsContentService.ProjectCompletenessView completeness =
        cmsContentService.getProjectCompleteness(projectId, List.of("fr-FR"));
    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.locales().get(1).translationNeededFields()).isEqualTo(1);
    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void translatorContextEditRequiresReapprovalBeforePublish() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_context_edit_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "context-edit-banner",
                "Context edit banner",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "promo", "Promo", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in promo body", null));
    CmsContentService.FieldMappingView initialMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    Long initialTmTextUnitId = initialMapping.tmTextUnitId();
    detail = markEntryReady(detail);
    Locale french = localeService.findByBcp47Tag("fr-FR");
    tmService.addCurrentTMTextUnitVariant(
        initialTmTextUnitId, french.getId(), "Bonjour", APPROVED, true);
    assertThat(cmsContentService.getProjectCompleteness(projectId, List.of("fr-FR")).complete())
        .isTrue();

    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId,
                null,
                null,
                "Hello",
                "Shown in promo banner body",
                initialMapping.entityVersion()));
    CmsContentService.FieldMappingView updatedMapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    assertThat(updatedMapping.tmTextUnitId()).isNotEqualTo(initialTmTextUnitId);
    assertThat(updatedMapping.stringId()).isEqualTo(initialMapping.stringId());
    assertThat(
            tmTextUnitCurrentVariantRepository
                .findByLocale_IdAndTmTextUnit_Id(french.getId(), updatedMapping.tmTextUnitId())
                .getTmTextUnitVariant()
                .getStatus())
        .isEqualTo(TRANSLATION_NEEDED);
    CmsContentService.ProjectCompletenessView completeness =
        cmsContentService.getProjectCompleteness(projectId, List.of("fr-FR"));
    assertThat(completeness.complete()).isFalse();
    assertThat(completeness.locales().get(1).translationNeededFields()).isEqualTo(1);
    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cannot publish with incomplete locales: fr-FR");
  }

  @Test
  public void publishRejectsMalformedApprovedIcuTranslation() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_icu_repository"));
    repositoryService.addRepositoryLocale(repository, "fr-FR");

    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "icu-banner", "ICU banner", null, true, repository.getId(), null, "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.ICU_MESSAGE, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "quota", "Quota", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId,
                null,
                "{count, plural, one {# item} other {# items}}",
                "Shown in quota body",
                null));
    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    detail = markEntryReady(detail);
    Locale french = localeService.findByBcp47Tag("fr-FR");
    tmService.addCurrentTMTextUnitVariant(
        tmTextUnitId, french.getId(), "{count, plural, one {# article}", APPROVED, true);

    assertThatThrownBy(() -> publishProject(projectId, publishCommand(projectId, List.of("fr-FR"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ICU message integrity check failed for quota.default.body fr-FR");
  }

  @Test
  public void generatedMappingAcceptsLongSourceComment() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_long_comment_repository"));
    String projectKey = "long-comment-" + Long.toUnsignedString(System.nanoTime());
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey,
                "Long comment project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    String sourceComment = "x".repeat(2049);

    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(fieldId, null, "Hello", sourceComment, null));

    assertThat(detail.entries().get(0).variants().get(0).fieldMappings().get(0).sourceComment())
        .isEqualTo(sourceComment);
  }

  @Test
  public void schemaKeepsCmsFreeFormTextColumnsAsLongText() {
    requireMysql();

    assertThat(
            jdbcTemplate.queryForList(
                """
                select concat(table_name, '.', column_name)
                from information_schema.columns
                where table_schema = database()
                  and data_type = 'longtext'
                  and (
                    (table_name = 'cms_content_type' and column_name = 'metadata_schema_json')
                    or (table_name = 'cms_content_entry' and column_name = 'metadata_json')
                    or (table_name = 'cms_content_entry_variant' and column_name = 'metadata_json')
                    or (
                      table_name = 'cms_publish_snapshot'
                      and column_name in (
                        'artifact_json',
                        'completeness_json',
                        'locale_tags',
                        'publish_request_locale_tags'
                      )
                    )
                  )
                order by table_name, column_name
                """,
                String.class))
        .containsExactly(
            "cms_content_entry.metadata_json",
            "cms_content_entry_variant.metadata_json",
            "cms_content_type.metadata_schema_json",
            "cms_publish_snapshot.artifact_json",
            "cms_publish_snapshot.completeness_json",
            "cms_publish_snapshot.locale_tags",
            "cms_publish_snapshot.publish_request_locale_tags");
  }

  @Test
  public void schemaKeepsCmsMvpCheckConstraints() {
    requireMysql();

    assertThat(
            jdbcTemplate.queryForList(
                """
                select constraint_name
                from information_schema.check_constraints
                where constraint_schema = database()
                  and constraint_name in (
                    'CK__ASSET__CMS_MANAGED_VIRTUAL',
                    'CK__CMS_CONTENT_PROJECT__PROJECT_KEY',
                    'CK__CMS_CONTENT_PROJECT__DELIVERY_HINT',
                    'CK__CMS_CONTENT_PROJECT__ENTITY_VERSION',
                    'CK__CMS_CONTENT_TYPE__TYPE_KEY',
                    'CK__CMS_CONTENT_TYPE__ENTITY_VERSION',
                    'CK__CMS_CONTENT_TYPE__SCHEMA_VERSION',
                    'CK__CMS_CONTENT_TYPE_FIELD__FIELD_KEY',
                    'CK__CMS_CONTENT_TYPE_FIELD__ENTITY_VERSION',
                    'CK__CMS_CONTENT_TYPE_FIELD__FIELD_TYPE',
                    'CK__CMS_CONTENT_TYPE_FIELD__LOCALIZABLE',
                    'CK__CMS_CONTENT_TYPE_FIELD__SORT_ORDER',
                    'CK__CMS_CONTENT_ENTRY__ENTRY_KEY',
                    'CK__CMS_CONTENT_ENTRY__ENTITY_VERSION',
                    'CK__CMS_CONTENT_ENTRY__STATUS',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__VARIANT_KEY',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_KEY',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_REQUIRED',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__ENTITY_VERSION',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__STATUS',
                    'CK__CMS_CONTENT_ENTRY_VARIANT__SORT_ORDER',
                    'CK__CMS_CONTENT_FIELD_MAPPING__ENTITY_VERSION',
                    'CK__CMS_CONTENT_PROJECT__ASSET_CMS_MANAGED',
                    'CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED',
                    'CK__CMS_CONTENT_PROJECT__ASSET_VIRTUAL',
                    'CK__CMS_CONTENT_PROJECT__LAST_PUBLISHED_SNAPSHOT_VERSION',
                    'CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_VERSION',
                    'CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SIGNATURE',
                    'CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SHA256',
                    'CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_BYTE_SIZE',
                    'CK__CMS_PUBLISH_SNAPSHOT__CREATED_BY_USERNAME',
                    'CK__CMS_PUBLISH_SNAPSHOT__PUBLISHED_AT',
                    'CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_AUTHORING_SHA256',
                    'CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_KEY',
                    'CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_PACKAGE_SHA256',
                    'CK__CMS_PUBLISH_SNAPSHOT__STATUS',
                    'CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNING_KEY_ID',
                    'CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNATURE'
                  )
                order by constraint_name
                """,
                String.class))
        .containsExactly(
            "CK__ASSET__CMS_MANAGED_VIRTUAL",
            "CK__CMS_CONTENT_ENTRY__ENTITY_VERSION",
            "CK__CMS_CONTENT_ENTRY__ENTRY_KEY",
            "CK__CMS_CONTENT_ENTRY__STATUS",
            "CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_KEY",
            "CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_REQUIRED",
            "CK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY",
            "CK__CMS_CONTENT_ENTRY_VARIANT__ENTITY_VERSION",
            "CK__CMS_CONTENT_ENTRY_VARIANT__SORT_ORDER",
            "CK__CMS_CONTENT_ENTRY_VARIANT__STATUS",
            "CK__CMS_CONTENT_ENTRY_VARIANT__VARIANT_KEY",
            "CK__CMS_CONTENT_FIELD_MAPPING__ENTITY_VERSION",
            "CK__CMS_CONTENT_PROJECT__ASSET_CMS_MANAGED",
            "CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED",
            "CK__CMS_CONTENT_PROJECT__ASSET_VIRTUAL",
            "CK__CMS_CONTENT_PROJECT__DELIVERY_HINT",
            "CK__CMS_CONTENT_PROJECT__ENTITY_VERSION",
            "CK__CMS_CONTENT_PROJECT__LAST_PUBLISHED_SNAPSHOT_VERSION",
            "CK__CMS_CONTENT_PROJECT__PROJECT_KEY",
            "CK__CMS_CONTENT_TYPE__ENTITY_VERSION",
            "CK__CMS_CONTENT_TYPE__SCHEMA_VERSION",
            "CK__CMS_CONTENT_TYPE__TYPE_KEY",
            "CK__CMS_CONTENT_TYPE_FIELD__ENTITY_VERSION",
            "CK__CMS_CONTENT_TYPE_FIELD__FIELD_KEY",
            "CK__CMS_CONTENT_TYPE_FIELD__FIELD_TYPE",
            "CK__CMS_CONTENT_TYPE_FIELD__LOCALIZABLE",
            "CK__CMS_CONTENT_TYPE_FIELD__SORT_ORDER",
            "CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_BYTE_SIZE",
            "CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SHA256",
            "CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SIGNATURE",
            "CK__CMS_PUBLISH_SNAPSHOT__CREATED_BY_USERNAME",
            "CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_AUTHORING_SHA256",
            "CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_KEY",
            "CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_PACKAGE_SHA256",
            "CK__CMS_PUBLISH_SNAPSHOT__PUBLISHED_AT",
            "CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNATURE",
            "CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNING_KEY_ID",
            "CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_VERSION",
            "CK__CMS_PUBLISH_SNAPSHOT__STATUS");
  }

  @Test
  public void schemaRejectsActiveCandidateWithoutCandidateGroupKey() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_candidate_group_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "candidate-group-project",
                    "Candidate group project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    null))
            .project()
            .id();
    CmsContentService.ProjectDetail detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                """
                insert into cms_content_entry_variant
                  (created_by_user_id, last_modified_by_user_id, content_entry_id,
                   content_type_id, content_project_id, variant_key, name, status, sort_order,
                   entity_version)
                values (?, ?, ?, ?, ?, ?, ?, 'CANDIDATE', 0, 0)
                """,
                auditUserId(),
                auditUserId(),
                entryId,
                contentTypeId,
                projectId,
                "candidate",
                "Candidate"),
        "CK__CMS_CONTENT_ENTRY_VARIANT__CANDIDATE_GROUP_REQUIRED");
  }

  @Test
  public void schemaRejectsInvalidProjectDeliveryHint() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_delivery_hint_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "delivery-hint-project",
                    "Delivery hint project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    null))
            .project()
            .id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_project set delivery_hint = 'CUSTOM' where id = ?", projectId),
        "CK__CMS_CONTENT_PROJECT__DELIVERY_HINT");
  }

  @Test
  public void schemaRejectsDeletedProjectAssetMarker() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_deleted_asset_marker_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "deleted-asset-marker-project",
                    "Deleted asset marker project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    null))
            .project()
            .id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_project set asset_deleted = true where id = ?", projectId),
        "CK__CMS_CONTENT_PROJECT__ASSET_NOT_DELETED");
  }

  @Test
  public void schemaRejectsInvalidContentTypeSchemaVersion() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_schema_version_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "schema-version-project",
                    "Schema version project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    null))
            .project()
            .id();
    Long contentTypeId =
        cmsContentService
            .createContentType(
                projectId,
                new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null))
            .contentTypes()
            .get(0)
            .id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_type set schema_version = 0 where id = ?", contentTypeId),
        "CK__CMS_CONTENT_TYPE__SCHEMA_VERSION");
  }

  @Test
  public void schemaRejectsNegativeFieldAndVariantSortOrder() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_sort_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "sort-project", "Sort project", null, true, repository.getId(), null, null))
            .project()
            .id();
    CmsContentService.ProjectDetail detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, false, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_type_field set sort_order = -1 where id = ?", fieldId),
        "CK__CMS_CONTENT_TYPE_FIELD__SORT_ORDER");
    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_entry_variant set sort_order = -1 where id = ?", variantId),
        "CK__CMS_CONTENT_ENTRY_VARIANT__SORT_ORDER");
  }

  @Test
  public void repositoryRejectsMutatingPublishedSnapshot() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("jpa-snapshot");
    snapshot.setArtifactJson("{\"mutated\":true}");

    assertThatThrownBy(() -> snapshotRepository.saveAndFlush(snapshot))
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasMessageContaining("CMS publish snapshots are immutable");
  }

  @Test
  public void schemaRejectsMalformedPublishedSnapshotSignature() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("signature-schema");

    assertThatThrownBy(
            () ->
                insertPublishSnapshotCopy(
                    snapshot, 2, "publish-request-2", "bad", null, null, null, null))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_PUBLISH_SNAPSHOT__SNAPSHOT_SIGNATURE");
  }

  @Test
  public void schemaRejectsMalformedPublishedArtifactSignature() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("artifact-signature-schema");

    assertThatThrownBy(
            () ->
                insertPublishSnapshotCopy(
                    snapshot, 2, "publish-request-2", null, "bad", null, null, null))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_SIGNATURE");
  }

  @Test
  public void schemaRejectsMalformedPublishedSnapshotRequestKey() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("request-key-schema");

    assertThatThrownBy(
            () -> insertPublishSnapshotCopy(snapshot, 2, "bad.key", null, null, null, null, null))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_KEY");
  }

  @Test
  public void schemaRejectsMalformedPublishedSnapshotRequestAuthoringSha256() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("request-authoring-schema");

    assertThatThrownBy(
            () ->
                insertPublishSnapshotCopy(
                    snapshot, 2, "publish-request-2", null, null, "bad", null, null))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_AUTHORING_SHA256");
  }

  @Test
  public void schemaRejectsMalformedPublishedSnapshotRequestPackageSha256() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("request-package-schema");

    assertThatThrownBy(
            () ->
                insertPublishSnapshotCopy(
                    snapshot, 2, "publish-request-2", null, null, null, "bad", null))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_PUBLISH_SNAPSHOT__PUBLISH_REQUEST_PACKAGE_SHA256");
  }

  @Test
  public void schemaRejectsDuplicatePublishedSnapshotRequestKeyWithinProject() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("duplicate-request-key-schema");

    assertThatThrownBy(
            () ->
                insertPublishSnapshotCopy(
                    snapshot, 2, snapshot.getPublishRequestKey(), null, null, null, null, null))
        .isInstanceOf(DuplicateKeyException.class)
        .hasMessageContaining("UK__CMS_PUBLISH_SNAPSHOT__PROJECT_REQUEST_KEY");
  }

  @Test
  public void schemaRejectsMalformedPublishedSnapshotTimestamp() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("published-at-schema");

    assertThatThrownBy(
            () ->
                insertPublishSnapshotCopy(
                    snapshot, 2, "publish-request-2", null, null, null, null, "bad"))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_PUBLISH_SNAPSHOT__PUBLISHED_AT");
  }

  @Test
  public void storedSnapshotDeleteLeavesDetectedPublishHistoryGap() throws Exception {
    requireMysql();
    CmsPublishSnapshot snapshot = savePublishSnapshot("db-delete-snapshot");
    Long projectId = snapshot.getProject().getId();

    assertThat(
            jdbcTemplate.update(
                "delete from cms_publish_snapshot_seal where publish_snapshot_id = ?",
                snapshot.getId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.update("delete from cms_publish_snapshot where id = ?", snapshot.getId()))
        .isEqualTo(1);
    entityManager.clear();

    assertThatThrownBy(() -> cmsContentService.getProject(projectId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CMS publish snapshot history is incomplete");
  }

  @Test
  public void schemaRejectsDeletingSealedPublishedSnapshot() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("sealed-snapshot-delete");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "delete from cms_publish_snapshot where id = ?", snapshot.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("FK__CMS_PUBLISH_SNAPSHOT_SEAL__SNAPSHOT");
  }

  @Test
  public void schemaRejectsDeletingProjectWithPublishedSnapshot() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("published-snapshot-project-delete");

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "delete from cms_content_project where id = ?", snapshot.getProject().getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("FK__CMS_PUBLISH_SNAPSHOT__PROJECT");
  }

  @Test
  public void deletedSnapshotPublisherKeepsPublishTimeUsernameInHistory() throws Exception {
    String publisherUsername = testIdWatcher.getEntityName("snapshot_publisher");
    User publisher =
        userService.createUserWithRole(publisherUsername, "SnapshotPublisher1234", Role.ROLE_ADMIN);
    Long publisherId = publisher.getId();
    UserDetailsImpl publisherPrincipal = new UserDetailsImpl(publisher);
    Authentication publisherAuthentication =
        new UsernamePasswordAuthenticationToken(
            publisherPrincipal,
            publisherPrincipal.getPassword(),
            publisherPrincipal.getAuthorities());
    Authentication adminAuthentication = SecurityContextHolder.getContext().getAuthentication();
    CmsPublishSnapshot snapshot =
        withAuthentication(
            publisherAuthentication, () -> publishReadySnapshot("deleted-snapshot-publisher"));

    withAuthentication(
        adminAuthentication,
        () -> {
          userService.deleteUser(publisher);
          return null;
        });
    entityManager.clear();

    User deletedPublisher = userRepository.findById(publisherId).orElseThrow();
    assertThat(deletedPublisher.getEnabled()).isFalse();
    assertThat(deletedPublisher.getUsername()).startsWith("deleted__");
    CmsContentService.ProjectDetail detail =
        withAuthentication(
            adminAuthentication, () -> cmsContentService.getProject(snapshot.getProject().getId()));
    assertThat(detail.publishSnapshots())
        .extracting(CmsContentService.PublishSnapshotView::createdByUsername)
        .containsExactly(publisherUsername);
  }

  @Test
  public void repositoryRejectsDeletingPublishedSnapshot() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("jpa-delete-snapshot");

    assertThatThrownBy(
            () -> {
              snapshotRepository.delete(snapshot);
              snapshotRepository.flush();
            })
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasMessageContaining("CMS publish snapshots are immutable");
  }

  @Test
  public void repositoryRejectsDeletingPublishedSnapshotSeal() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("jpa-delete-snapshot-seal");
    CmsPublishSnapshotSeal seal = snapshotSealRepository.findById(snapshot.getId()).orElseThrow();

    assertThatThrownBy(
            () -> {
              snapshotSealRepository.delete(seal);
              snapshotSealRepository.flush();
            })
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasMessageContaining("CMS publish snapshot seals are immutable");
  }

  @Test
  public void schemaRejectsNegativePublishSnapshotWatermark() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("snapshot-watermark-schema");

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_project set last_published_snapshot_version = -1 where id = ?",
                snapshot.getProject().getId()),
        "CK__CMS_CONTENT_PROJECT__LAST_PUBLISHED_SNAPSHOT_VERSION");
  }

  @Test
  public void schemaRejectsNegativeAuthoringEntityVersions() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_entity_version_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "entity-version-project",
                "Entity version project",
                null,
                true,
                repository.getId(),
                null,
                null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, false, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in email header", null));
    Long mappingId = detail.entries().get(0).variants().get(0).fieldMappings().get(0).id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_project set entity_version = -1 where id = ?", projectId),
        "CK__CMS_CONTENT_PROJECT__ENTITY_VERSION");
    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_type set entity_version = -1 where id = ?", contentTypeId),
        "CK__CMS_CONTENT_TYPE__ENTITY_VERSION");
    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_type_field set entity_version = -1 where id = ?", fieldId),
        "CK__CMS_CONTENT_TYPE_FIELD__ENTITY_VERSION");
    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_entry set entity_version = -1 where id = ?", entryId),
        "CK__CMS_CONTENT_ENTRY__ENTITY_VERSION");
    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_entry_variant set entity_version = -1 where id = ?", variantId),
        "CK__CMS_CONTENT_ENTRY_VARIANT__ENTITY_VERSION");
    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_field_mapping set entity_version = -1 where id = ?", mappingId),
        "CK__CMS_CONTENT_FIELD_MAPPING__ENTITY_VERSION");
  }

  @Test
  public void schemaRejectsZeroPublishedSnapshotArtifactByteSize() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("artifact-byte-size-schema");

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_publish_snapshot set artifact_byte_size = 0 where id = ?",
                snapshot.getId()),
        "CK__CMS_PUBLISH_SNAPSHOT__ARTIFACT_BYTE_SIZE");
  }

  @Test
  public void schemaRejectsStoredEntryKeyWithGeneratedStringIdSeparator() throws Exception {
    requireMysql();

    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_key_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "key-project", "Key project", null, true, repository.getId(), null, null))
            .project()
            .id();
    Long contentTypeId =
        cmsContentService
            .createContentType(
                projectId,
                new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null))
            .contentTypes()
            .get(0)
            .id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_entry
                      (created_by_user_id, last_modified_by_user_id, content_project_id,
                       content_type_id, entry_key, name, status, entity_version)
                    values (?, ?, ?, ?, ?, ?, 'DRAFT', 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    projectId,
                    contentTypeId,
                    "invalid.entry",
                    "Invalid entry"))
        .isInstanceOf(UncategorizedSQLException.class)
        .hasMessageContaining("CK__CMS_CONTENT_ENTRY__ENTRY_KEY");
  }

  @Test
  public void schemaRejectsUnsupportedRichTextFieldType() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_field_type_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "field-type-project",
                    "Field type project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    null))
            .project()
            .id();
    Long contentTypeId =
        cmsContentService
            .createContentType(
                projectId,
                new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null))
            .contentTypes()
            .get(0)
            .id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                """
                insert into cms_content_type_field
                  (created_by_user_id, last_modified_by_user_id, content_type_id, field_key,
                   name, field_type, localizable, required, sort_order, entity_version)
                values (?, ?, ?, ?, ?, 'RICH_TEXT', true, false, 0, 0)
                """,
                auditUserId(),
                auditUserId(),
                contentTypeId,
                "body",
                "Body"),
        "CK__CMS_CONTENT_TYPE_FIELD__FIELD_TYPE");
  }

  @Test
  public void schemaRejectsStoredNonLocalizableField() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_localizable_field_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "localizable-field-project",
                    "Localizable field project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    null))
            .project()
            .id();
    CmsContentService.ProjectDetail detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, false, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_type_field set localizable = false where id = ?", fieldId),
        "CK__CMS_CONTENT_TYPE_FIELD__LOCALIZABLE");
  }

  @Test
  public void publishProjectLockSerializesSameProjectSnapshotVersionAllocation() throws Exception {
    requireMysql();

    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_lock_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "lock-project",
                    "Lock project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    "BLOB_CDN"))
            .project()
            .id();
    CountDownLatch firstLockAcquired = new CountDownLatch(1);
    CountDownLatch releaseFirstLock = new CountDownLatch(1);
    CountDownLatch secondLockAttempted = new CountDownLatch(1);
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    try {
      Future<?> firstLock =
          executorService.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        projectRepository
                            .findByIdWithRepositoryAndAssetForUpdate(projectId)
                            .orElseThrow();
                        firstLockAcquired.countDown();
                        awaitLatch(releaseFirstLock);
                      }));
      assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> secondLock =
          executorService.submit(
              () -> {
                secondLockAttempted.countDown();
                transactionTemplate.executeWithoutResult(
                    status ->
                        projectRepository
                            .findByIdWithRepositoryAndAssetForUpdate(projectId)
                            .orElseThrow());
              });
      assertThat(secondLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> secondLock.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseFirstLock.countDown();
      firstLock.get(5, TimeUnit.SECONDS);
      secondLock.get(5, TimeUnit.SECONDS);
    } finally {
      releaseFirstLock.countDown();
      executorService.shutdownNow();
    }
  }

  @Test
  public void concurrentMatchingPublishRetriesReturnOneSnapshot() throws Exception {
    requireMysql();

    CmsPublishSnapshot publishedSnapshot = publishReadySnapshot("concurrent-publish-retry");
    Long projectId = publishedSnapshot.getProject().getId();
    CmsContentService.PublishCommand publishCommand = publishCommand(projectId, List.of());
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    CountDownLatch startPublish = new CountDownLatch(1);
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    try {
      Future<CmsContentService.PublishSnapshotView> firstPublish =
          executorService.submit(
              () ->
                  withAuthentication(
                      authentication,
                      () -> {
                        awaitLatch(startPublish);
                        return publishProject(projectId, publishCommand, "concurrent-publish");
                      }));
      Future<CmsContentService.PublishSnapshotView> retryPublish =
          executorService.submit(
              () ->
                  withAuthentication(
                      authentication,
                      () -> {
                        awaitLatch(startPublish);
                        return publishProject(projectId, publishCommand, "concurrent-publish");
                      }));

      startPublish.countDown();
      CmsContentService.PublishSnapshotView firstSnapshot = firstPublish.get(20, TimeUnit.SECONDS);
      CmsContentService.PublishSnapshotView retrySnapshot = retryPublish.get(20, TimeUnit.SECONDS);

      assertThat(retrySnapshot).isEqualTo(firstSnapshot);
      assertThat(firstSnapshot.snapshotVersion())
          .isEqualTo(publishedSnapshot.getSnapshotVersion() + 1);
      assertThat(cmsContentService.getProject(projectId).publishSnapshots()).hasSize(2);
    } finally {
      startPublish.countDown();
      executorService.shutdownNow();
    }
  }

  @Test
  public void publishTextUnitLockSerializesCurrentVariantMutationLock() throws Exception {
    requireMysql();

    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_text_unit_lock_repository"));
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "text-unit-lock-project",
                    "Text unit lock project",
                    null,
                    true,
                    repository.getId(),
                    null,
                    "BLOB_CDN"))
            .project()
            .id();
    TMTextUnit tmTextUnit = createMappedTextUnit(projectId, "Hello");
    CountDownLatch firstLockAcquired = new CountDownLatch(1);
    CountDownLatch releaseFirstLock = new CountDownLatch(1);
    CountDownLatch secondLockAttempted = new CountDownLatch(1);
    ExecutorService executorService = Executors.newFixedThreadPool(2);
    try {
      Future<?> publishLock =
          executorService.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      status -> {
                        tmTextUnitCurrentVariantMutationLockService.lockTextUnit(
                            tmTextUnit.getId());
                        firstLockAcquired.countDown();
                        awaitLatch(releaseFirstLock);
                      }));
      assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> mutationLock =
          executorService.submit(
              () -> {
                secondLockAttempted.countDown();
                transactionTemplate.executeWithoutResult(
                    status ->
                        tmTextUnitCurrentVariantMutationLockService.lockTextUnit(
                            tmTextUnit.getId()));
              });
      assertThat(secondLockAttempted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> mutationLock.get(250, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseFirstLock.countDown();
      publishLock.get(5, TimeUnit.SECONDS);
      mutationLock.get(5, TimeUnit.SECONDS);
    } finally {
      releaseFirstLock.countDown();
      executorService.shutdownNow();
    }
  }

  @Test
  public void publishSnapshotHistoryQueryLimitsLatestRows() throws Exception {
    CmsPublishSnapshot firstSnapshot = savePublishSnapshot("snapshot-history");
    Long projectId = firstSnapshot.getProject().getId();
    for (int snapshotVersion = 2; snapshotVersion <= 11; snapshotVersion++) {
      savePublishSnapshot(projectId, snapshotVersion);
    }

    List<CmsPublishSnapshot> snapshots =
        snapshotRepository.findByProjectIdOrderBySnapshotVersionDesc(
            projectId, PageRequest.of(0, 10));

    assertThat(snapshots)
        .extracting(CmsPublishSnapshot::getSnapshotVersion)
        .containsExactly(11, 10, 9, 8, 7, 6, 5, 4, 3, 2);
  }

  @Test
  public void publishSnapshotHistoryPagesRetainedMetadataAfterRecentArtifactValidation()
      throws Exception {
    CmsPublishSnapshot firstSnapshot = publishReadySnapshot("snapshot-history-page");
    Long projectId = firstSnapshot.getProject().getId();
    for (int snapshotVersion = 2; snapshotVersion <= 11; snapshotVersion++) {
      publishProject(
          projectId,
          publishCommand(projectId, List.of()),
          "snapshot-history-page-" + snapshotVersion);
    }

    CmsContentService.PublishSnapshotHistoryView firstPage =
        cmsContentService.getProjectPublishSnapshots(projectId, null, null);
    CmsContentService.PublishSnapshotHistoryView secondPage =
        cmsContentService.getProjectPublishSnapshots(projectId, 2, null);

    assertThat(firstPage.snapshots())
        .extracting(CmsContentService.PublishSnapshotView::snapshotVersion)
        .containsExactly(11, 10, 9, 8, 7, 6, 5, 4, 3, 2);
    assertThat(firstPage.hasMore()).isTrue();
    assertThat(firstPage.nextBeforeSnapshotVersion()).isEqualTo(2);
    assertThat(secondPage.snapshots())
        .extracting(CmsContentService.PublishSnapshotView::snapshotVersion)
        .containsExactly(1);
    assertThat(secondPage.hasMore()).isFalse();
    assertThat(secondPage.nextBeforeSnapshotVersion()).isNull();
  }

  @Test
  public void publishSnapshotIdLookupFetchesAdminHistoryDependencies() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("snapshot-id");
    entityManager.clear();

    CmsPublishSnapshot reloaded = snapshotRepository.findById(snapshot.getId()).orElseThrow();

    assertThat(Hibernate.isInitialized(reloaded.getProject())).isTrue();
    assertThat(Hibernate.isInitialized(reloaded.getCreatedByUser())).isTrue();
  }

  @Test
  public void versionedPublishSnapshotLookupFetchesArtifactDependencies() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("snapshot-versioned");
    entityManager.clear();

    CmsPublishSnapshot reloaded =
        snapshotRepository
            .findByProjectIdAndSnapshotVersion(
                snapshot.getProject().getId(), snapshot.getSnapshotVersion())
            .orElseThrow();

    assertThat(Hibernate.isInitialized(reloaded.getProject())).isTrue();
    assertThat(Hibernate.isInitialized(reloaded.getCreatedByUser())).isTrue();
  }

  @Test
  public void latestPublishSnapshotLookupFetchesArtifactDependencies() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("snapshot-latest");
    entityManager.clear();

    CmsPublishSnapshot reloaded =
        snapshotRepository
            .findFirstByProjectIdOrderBySnapshotVersionDesc(snapshot.getProject().getId())
            .orElseThrow();

    assertThat(Hibernate.isInitialized(reloaded.getProject())).isTrue();
    assertThat(Hibernate.isInitialized(reloaded.getCreatedByUser())).isTrue();
  }

  @Test
  public void disabledProjectDoesNotExposeLatestDeliveryDescriptor() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("snapshot-disabled");
    CmsContentProject project =
        projectRepository
            .findByIdWithRepositoryAndAsset(snapshot.getProject().getId())
            .orElseThrow();
    project.setEnabled(false);
    projectRepository.saveAndFlush(project);

    assertThatThrownBy(
            () -> cmsContentService.getLatestPublishedSnapshotDescriptor(project.getProjectKey()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Content project is disabled: " + project.getProjectKey());
  }

  @Test
  public void disabledProjectStillExportsExactSnapshotForRollback() throws Exception {
    CmsPublishSnapshot snapshot = publishReadySnapshot("snapshot-disabled-rollback");
    CmsContentProject project =
        projectRepository
            .findByIdWithRepositoryAndAsset(snapshot.getProject().getId())
            .orElseThrow();
    project.setEnabled(false);
    projectRepository.saveAndFlush(project);

    CmsContentService.SnapshotArtifact artifact =
        cmsContentService.getSnapshotArtifact(
            project.getProjectKey(), snapshot.getSnapshotVersion());

    assertThat(artifact.filename())
        .isEqualTo(project.getProjectKey() + ".v" + snapshot.getSnapshotVersion() + ".json");
    assertThat(artifact.artifactSha256()).isEqualTo(snapshot.getArtifactSha256());
  }

  @Test
  public void orphanedProjectDoesNotExposeLatestDeliveryDescriptor() throws Exception {
    CmsPublishSnapshot snapshot = savePublishSnapshot("snapshot-orphaned");
    Repository repository =
        repositoryRepository.findById(snapshot.getProject().getRepository().getId()).orElseThrow();
    repository.setDeleted(true);
    repositoryRepository.saveAndFlush(repository);
    entityManager.clear();

    assertThatThrownBy(
            () ->
                cmsContentService.getLatestPublishedSnapshotDescriptor(
                    snapshot.getProject().getProjectKey()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "Content project repository is deleted: " + snapshot.getProject().getProjectKey());
  }

  @Test
  public void orphanedProjectStillExportsExactSnapshotForRollback() throws Exception {
    CmsPublishSnapshot snapshot = publishReadySnapshot("snapshot-orphaned-rollback");
    Repository repository =
        repositoryRepository.findById(snapshot.getProject().getRepository().getId()).orElseThrow();
    repository.setDeleted(true);
    repositoryRepository.saveAndFlush(repository);
    entityManager.clear();

    CmsContentService.SnapshotArtifact artifact =
        cmsContentService.getSnapshotArtifact(
            snapshot.getProject().getProjectKey(), snapshot.getSnapshotVersion());

    assertThat(artifact.filename())
        .isEqualTo(
            snapshot.getProject().getProjectKey() + ".v" + snapshot.getSnapshotVersion() + ".json");
    assertThat(artifact.artifactSha256()).isEqualTo(snapshot.getArtifactSha256());
  }

  @Test
  public void createProjectRejectsReusingVirtualAssetAcrossContentProjects() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_asset_repository"));
    String assetPath = "cms/shared-project";
    cmsContentService.createProject(
        new CmsContentService.ProjectCommand(
            "shared-project-a",
            "Shared project A",
            null,
            true,
            repository.getId(),
            assetPath,
            "BLOB_CDN"));

    assertThatThrownBy(
            () ->
                cmsContentService.createProject(
                    new CmsContentService.ProjectCommand(
                        "shared-project-b",
                        "Shared project B",
                        null,
                        true,
                        repository.getId(),
                        assetPath,
                        "BLOB_CDN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CMS asset is already assigned to content project: shared-project-a");
  }

  @Test
  public void createProjectRejectsAdoptingUnassignedVirtualAsset() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_unassigned_asset_repository"));
    String assetPath = "cms/unassigned-project";
    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repository.getId());
    virtualAsset.setPath(assetPath);
    virtualAssetService.createOrUpdateVirtualAsset(virtualAsset);

    assertThatThrownBy(
            () ->
                cmsContentService.createProject(
                    new CmsContentService.ProjectCommand(
                        "unassigned-project",
                        "Unassigned project",
                        null,
                        true,
                        repository.getId(),
                        assetPath,
                        "BLOB_CDN")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "CMS asset path already exists; choose an unused path for a dedicated CMS virtual asset");
  }

  @Test
  public void schemaRejectsEntryWhoseContentTypeBelongsToAnotherProject() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_cross_project_repository"));
    Long firstProjectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "cross-project-a",
                    "Cross project A",
                    null,
                    true,
                    repository.getId(),
                    null,
                    "BLOB_CDN"))
            .project()
            .id();
    Long secondProjectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "cross-project-b",
                    "Cross project B",
                    null,
                    true,
                    repository.getId(),
                    null,
                    "BLOB_CDN"))
            .project()
            .id();
    Long contentTypeId =
        cmsContentService
            .createContentType(
                firstProjectId,
                new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null))
            .contentTypes()
            .get(0)
            .id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_entry
                      (created_by_user_id, last_modified_by_user_id, content_project_id,
                       content_type_id, entry_key, name, status, entity_version)
                    values (?, ?, ?, ?, ?, ?, 'DRAFT', 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    secondProjectId,
                    contentTypeId,
                    "invalid-entry",
                    "Invalid entry"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsProjectWhoseAssetBelongsToAnotherRepository() throws Exception {
    Repository assetOwnerRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_asset_owner_repository"));
    Repository projectOwnerRepository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_project_owner_repository"));
    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(assetOwnerRepository.getId());
    virtualAsset.setPath("cms/unassigned-project-asset");
    Long assetId = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset).getId();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_project
                      (created_by_user_id, last_modified_by_user_id, project_key, name, enabled,
                       repository_id, asset_id, asset_virtual, delivery_hint, entity_version)
                    values (?, ?, ?, ?, true, ?, ?, true, 'BLOB_CDN', 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    "invalid-project",
                    "Invalid project",
                    projectOwnerRepository.getId(),
                    assetId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsProjectWhoseAssetIsNotVirtual() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_real_asset_repository"));
    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repository.getId());
    virtualAsset.setPath("cms/non-virtual-project-asset");
    Long assetId = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset).getId();
    Asset asset = assetRepository.findById(assetId).orElseThrow();
    asset.setVirtual(false);
    assetRepository.saveAndFlush(asset);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_project
                      (created_by_user_id, last_modified_by_user_id, project_key, name, enabled,
                       repository_id, asset_id, asset_virtual, delivery_hint, entity_version)
                    values (?, ?, ?, ?, true, ?, ?, true, 'BLOB_CDN', 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    "invalid-non-virtual-project",
                    "Invalid non virtual project",
                    repository.getId(),
                    assetId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsProjectWhoseAssetIsNotCmsManaged() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_unmanaged_asset_repository"));
    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repository.getId());
    virtualAsset.setPath("cms/unmanaged-project-asset");
    Long assetId = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset).getId();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_project
                      (created_by_user_id, last_modified_by_user_id, project_key, name, enabled,
                       repository_id, asset_id, asset_virtual, delivery_hint, entity_version)
                    values (?, ?, ?, ?, true, ?, ?, true, 'BLOB_CDN', 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    "invalid-unmanaged-project",
                    "Invalid unmanaged project",
                    repository.getId(),
                    assetId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsCmsManagedAssetThatIsNotVirtual() throws Exception {
    requireMysql();

    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_managed_non_virtual_repository"));
    VirtualAsset virtualAsset = new VirtualAsset();
    virtualAsset.setRepositoryId(repository.getId());
    virtualAsset.setPath("cms/managed-non-virtual-asset");
    Long assetId = virtualAssetService.createOrUpdateVirtualAsset(virtualAsset).getId();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update asset set cms_managed = true, `virtual` = false where id = ?", assetId),
        "CK__ASSET__CMS_MANAGED_VIRTUAL");
  }

  @Test
  public void schemaRejectsCmsProjectAssetBecomingNonVirtual() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_virtual_lock_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "virtual-lock-project",
                "Virtual lock project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Asset asset = assetRepository.findById(detail.project().asset().id()).orElseThrow();
    asset.setVirtual(false);

    assertSchemaConstraintViolation(
        () -> assetRepository.saveAndFlush(asset), "CK__ASSET__CMS_MANAGED_VIRTUAL");
  }

  @Test
  public void schemaRejectsCmsProjectAssetLosingManagedMarker() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_managed_lock_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "managed-lock-project",
                "Managed lock project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Asset asset = assetRepository.findById(detail.project().asset().id()).orElseThrow();
    asset.setCmsManaged(false);

    assertSchemaConstraintViolation(
        () -> assetRepository.saveAndFlush(asset), "FK__CMS_CONTENT_PROJECT__LIVE_CMS_ASSET");
  }

  @Test
  public void schemaRejectsCmsProjectAssetBecomingDeleted() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_deleted_lock_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "deleted-lock-project",
                "Deleted lock project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Asset asset = assetRepository.findById(detail.project().asset().id()).orElseThrow();
    asset.setDeleted(true);

    assertSchemaConstraintViolation(
        () -> assetRepository.saveAndFlush(asset), "FK__CMS_CONTENT_PROJECT__LIVE_CMS_ASSET");
  }

  @Test
  public void schemaRejectsMappingWhoseFieldBelongsToAnotherContentType() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_cross_type_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "cross-type-project",
                "Cross type project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long emailTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            emailTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long headerFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long bannerTypeId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .id();
    detail =
        cmsContentService.createContentTypeField(
            bannerTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long bodyFieldId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .fields()
            .get(0)
            .id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                emailTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                headerFieldId, null, "Hello", "Shown in email header", null));
    Long tmTextUnitId = unmapOnlyFieldMappingAndReturnTextUnitId(detail);
    Long assetId = detail.project().asset().id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_field_mapping
                      (created_by_user_id, last_modified_by_user_id, content_entry_variant_id,
                       content_type_field_id, content_type_id, content_project_id, asset_id,
                       tm_text_unit_id, entity_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    variantId,
                    bodyFieldId,
                    emailTypeId,
                    projectId,
                    assetId,
                    tmTextUnitId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsVariantWhoseContentTypeDoesNotMatchEntry() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_variant_type_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "variant-type-project",
                "Variant type project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long emailTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long bannerTypeId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                emailTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                insert into cms_content_entry_variant
                  (created_by_user_id, last_modified_by_user_id, content_entry_id,
                   content_type_id, content_project_id, variant_key, name, status, sort_order,
                   entity_version)
                values (?, ?, ?, ?, ?, ?, ?, 'ARCHIVED', 0, 0)
                """,
                    auditUserId(),
                    auditUserId(),
                    entryId,
                    bannerTypeId,
                    projectId,
                    "invalid-variant",
                    "Invalid variant"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsVariantWhoseProjectDoesNotMatchEntry() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_variant_project_repository"));
    Long entryProjectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "variant-project-entry",
                    "Variant project entry",
                    null,
                    true,
                    repository.getId(),
                    null,
                    "BLOB_CDN"))
            .project()
            .id();
    Long otherProjectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    "variant-project-other",
                    "Variant project other",
                    null,
                    true,
                    repository.getId(),
                    null,
                    "BLOB_CDN"))
            .project()
            .id();
    CmsContentService.ProjectDetail detail =
        cmsContentService.createContentType(
            entryProjectId,
            new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            entryProjectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                """
                insert into cms_content_entry_variant
                  (created_by_user_id, last_modified_by_user_id, content_entry_id,
                   content_type_id, content_project_id, variant_key, name, status, sort_order,
                   entity_version)
                values (?, ?, ?, ?, ?, ?, ?, 'ARCHIVED', 0, 0)
                """,
                auditUserId(),
                auditUserId(),
                entryId,
                contentTypeId,
                otherProjectId,
                "invalid-variant",
                "Invalid variant"),
        "FK__CMS_CONTENT_ENTRY_VARIANT__ENTRY_TYPE_PROJECT");
  }

  @Test
  public void schemaRejectsMappingWhoseVariantBelongsToAnotherContentType() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_mapping_variant_type_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "mapping-variant-type-project",
                "Mapping variant type project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long emailTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            emailTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long headerFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long bannerTypeId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .id();
    detail =
        cmsContentService.createContentTypeField(
            bannerTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "body", "Body", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long bodyFieldId =
        detail.contentTypes().stream()
            .filter(contentType -> "banner".equals(contentType.typeKey()))
            .findFirst()
            .orElseThrow()
            .fields()
            .get(0)
            .id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                emailTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                headerFieldId, null, "Hello", "Shown in email header", null));
    Long tmTextUnitId = unmapOnlyFieldMappingAndReturnTextUnitId(detail);
    Long assetId = detail.project().asset().id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_field_mapping
                      (created_by_user_id, last_modified_by_user_id, content_entry_variant_id,
                       content_type_field_id, content_type_id, content_project_id, asset_id,
                       tm_text_unit_id, entity_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    variantId,
                    bodyFieldId,
                    bannerTypeId,
                    projectId,
                    assetId,
                    tmTextUnitId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsMappingTextUnitFromAnotherCmsAsset() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_mapping_asset_repository"));
    CmsContentService.ProjectDetail targetDetail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "mapping-asset-target",
                "Mapping asset target",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long targetProjectId = targetDetail.project().id();
    Long targetAssetId = targetDetail.project().asset().id();
    targetDetail =
        cmsContentService.createContentType(
            targetProjectId,
            new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long targetContentTypeId = targetDetail.contentTypes().get(0).id();
    targetDetail =
        cmsContentService.createContentTypeField(
            targetContentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long targetFieldId = targetDetail.contentTypes().get(0).fields().get(0).id();
    targetDetail =
        cmsContentService.createEntry(
            targetProjectId,
            new CmsContentService.EntryCommand(
                targetContentTypeId,
                "welcome",
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                null));
    Long targetVariantId = targetDetail.entries().get(0).variants().get(0).id();

    CmsContentService.ProjectDetail sourceDetail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "mapping-asset-source",
                "Mapping asset source",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long sourceProjectId = sourceDetail.project().id();
    sourceDetail =
        cmsContentService.createContentType(
            sourceProjectId,
            new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long sourceContentTypeId = sourceDetail.contentTypes().get(0).id();
    sourceDetail =
        cmsContentService.createContentTypeField(
            sourceContentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long sourceFieldId = sourceDetail.contentTypes().get(0).fields().get(0).id();
    sourceDetail =
        cmsContentService.createEntry(
            sourceProjectId,
            new CmsContentService.EntryCommand(
                sourceContentTypeId,
                "welcome",
                "Welcome",
                null,
                CmsContentEntry.Status.DRAFT,
                null));
    Long sourceVariantId = sourceDetail.entries().get(0).variants().get(0).id();
    sourceDetail =
        cmsContentService.upsertFieldMapping(
            sourceVariantId,
            new CmsContentService.FieldMappingCommand(
                sourceFieldId, null, "Hello", "Shown in source email header", null));
    Long sourceTmTextUnitId = unmapOnlyFieldMappingAndReturnTextUnitId(sourceDetail);

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                """
                insert into cms_content_field_mapping
                  (created_by_user_id, last_modified_by_user_id, content_entry_variant_id,
                   content_type_field_id, content_type_id, content_project_id, asset_id,
                   tm_text_unit_id, entity_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                auditUserId(),
                auditUserId(),
                targetVariantId,
                targetFieldId,
                targetContentTypeId,
                targetProjectId,
                targetAssetId,
                sourceTmTextUnitId),
        "FK__CMS_CONTENT_FIELD_MAPPING__TM_TEXT_UNIT_ASSET");
  }

  @Test
  public void schemaRejectsMappingOneTextUnitToAnotherCmsField() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_duplicate_mapping_repository"));
    String projectKey = "duplicate-mapping-" + Long.toUnsignedString(System.nanoTime());
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey,
                "Duplicate mapping project",
                null,
                true,
                repository.getId(),
                null,
                "BLOB_CDN"));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("email", "Email", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long headerFieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "cta", "CTA", null, CmsContentTypeField.FieldType.TEXT, true, true, 1));
    Long ctaFieldId =
        detail.contentTypes().get(0).fields().stream()
            .filter(field -> "cta".equals(field.fieldKey()))
            .findFirst()
            .orElseThrow()
            .id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                headerFieldId, null, "Hello", "Shown in email header", null));
    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    Long assetId = detail.project().asset().id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                    insert into cms_content_field_mapping
                      (created_by_user_id, last_modified_by_user_id, content_entry_variant_id,
                       content_type_field_id, content_type_id, content_project_id, asset_id,
                       tm_text_unit_id, entity_version)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    auditUserId(),
                    auditUserId(),
                    variantId,
                    ctaFieldId,
                    contentTypeId,
                    projectId,
                    assetId,
                    tmTextUnitId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void promotesCandidateVariantToControlWithoutUniqueControlRace() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_promotion_repository"));
    String projectKey = "promotion-project-" + Long.toUnsignedString(System.nanoTime());
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "Promotion project", null, true, repository.getId(), null, null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();
    detail =
        cmsContentService.createVariant(
            entryId,
            new CmsContentService.VariantCommand(
                "winner",
                "Winner",
                "welcome-subject",
                CmsContentEntryVariant.Status.CANDIDATE,
                null,
                1));
    CmsContentService.VariantView winner = findVariant(detail, "winner");

    detail =
        cmsContentService.updateVariant(
            winner.id(),
            new CmsContentService.VariantUpdateCommand(
                "Winner",
                "welcome-subject",
                CmsContentEntryVariant.Status.CONTROL,
                null,
                1,
                winner.entityVersion()));

    CmsContentService.VariantView archivedControl = findVariant(detail, "default");
    CmsContentService.VariantView promotedWinner = findVariant(detail, "winner");
    assertThat(archivedControl.status()).isEqualTo(CmsContentEntryVariant.Status.ARCHIVED);
    assertThat(promotedWinner.status()).isEqualTo(CmsContentEntryVariant.Status.CONTROL);
    assertThat(promotedWinner.candidateGroupKey()).isEqualTo("welcome-subject");
    assertAdminAudit(archivedControl.audit());
    assertAdminAudit(promotedWinner.audit());

    entityManager.clear();
    CmsContentService.ProjectDetail persistedDetail = cmsContentService.getProject(projectId);
    assertThat(findVariant(persistedDetail, "default").status())
        .isEqualTo(CmsContentEntryVariant.Status.ARCHIVED);
    assertThat(findVariant(persistedDetail, "winner").status())
        .isEqualTo(CmsContentEntryVariant.Status.CONTROL);
  }

  @Test
  public void schemaRejectsSecondControlVariantForSameEntry() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_control_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "control-project", "Control project", null, true, repository.getId(), null, null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                insert into cms_content_entry_variant
                  (created_by_user_id, last_modified_by_user_id, content_entry_id,
                   content_type_id, content_project_id, variant_key, name, status,
                   control_entry_id, sort_order, entity_version)
                values (?, ?, ?, ?, ?, ?, ?, 'CONTROL', ?, 1, 0)
                """,
                    auditUserId(),
                    auditUserId(),
                    entryId,
                    contentTypeId,
                    projectId,
                    "duplicate-control",
                    "Duplicate control",
                    entryId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  public void schemaRejectsControlVariantMarkerDrift() throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName("cms_control_marker_repository"));
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                "control-marker-project",
                "Control marker project",
                null,
                true,
                repository.getId(),
                null,
                null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long controlVariantId = detail.entries().get(0).variants().get(0).id();

    assertSchemaConstraintViolation(
        () ->
            jdbcTemplate.update(
                "update cms_content_entry_variant set control_entry_id = null where id = ?",
                controlVariantId),
        "CK__CMS_CONTENT_ENTRY_VARIANT__CONTROL_ENTRY");
  }

  @Test
  public void schemaRejectsInvalidVariantLifecycleStatus() throws Exception {
    Repository repository =
        repositoryService.createRepository(testIdWatcher.getEntityName("cms_status_repository"));
    String projectKey = "status-project-" + Long.toUnsignedString(System.nanoTime());
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "Status project", null, true, repository.getId(), null, null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long entryId = detail.entries().get(0).id();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    """
                insert into cms_content_entry_variant
                  (created_by_user_id, last_modified_by_user_id, content_entry_id,
                   content_type_id, content_project_id, variant_key, name, status,
                   control_entry_id, sort_order, entity_version)
                values (?, ?, ?, ?, ?, ?, ?, 'BROKEN', null, 1, 0)
                """,
                    auditUserId(),
                    auditUserId(),
                    entryId,
                    contentTypeId,
                    projectId,
                    "broken",
                    "Broken"))
        .isInstanceOfAny(UncategorizedSQLException.class, DataIntegrityViolationException.class)
        .satisfies(
            exception -> {
              if (dbUtils.isMysql()) {
                assertThat(exception).hasMessageContaining("CK__CMS_CONTENT_ENTRY_VARIANT__STATUS");
              }
            });
  }

  private void requireMysql() {
    Assume.assumeTrue("Requires MySQL Flyway schema or MySQL locking", dbUtils.isMysql());
  }

  private CmsContentService.VariantView findVariant(
      CmsContentService.ProjectDetail detail, String variantKey) {
    return detail.entries().get(0).variants().stream()
        .filter(variant -> variantKey.equals(variant.variantKey()))
        .findFirst()
        .orElseThrow();
  }

  private TMTextUnit createMappedTextUnit(Long projectId, String sourceContent) {
    CmsContentService.ProjectDetail detail =
        cmsContentService.createContentType(
            projectId,
            new CmsContentService.ContentTypeCommand(
                "lock-content", "Lock content", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "header", "Header", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId,
                "lock-entry",
                "Lock entry",
                null,
                CmsContentEntry.Status.DRAFT,
                null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, sourceContent, "Lock test header", null));
    Long tmTextUnitId =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0).tmTextUnitId();
    return entityManager.find(TMTextUnit.class, tmTextUnitId);
  }

  private CmsContentService.ProjectDetail markEntryReady(CmsContentService.ProjectDetail detail) {
    CmsContentService.EntryView entry = detail.entries().get(0);
    return cmsContentService.updateEntry(
        entry.id(),
        new CmsContentService.EntryUpdateCommand(
            entry.name(),
            entry.description(),
            CmsContentEntry.Status.READY,
            entry.metadataJson(),
            entry.entityVersion()));
  }

  private Long unmapOnlyFieldMappingAndReturnTextUnitId(CmsContentService.ProjectDetail detail) {
    CmsContentService.FieldMappingView mapping =
        detail.entries().get(0).variants().get(0).fieldMappings().get(0);
    cmsContentService.unmapFieldMapping(
        mapping.id(), new CmsContentService.FieldMappingDeleteCommand(mapping.entityVersion()));
    return mapping.tmTextUnitId();
  }

  private void assertAdminAudit(CmsContentService.AuditView audit) {
    assertThat(audit.createdByUsername()).isEqualTo("admin");
    assertThat(audit.lastModifiedByUsername()).isEqualTo("admin");
  }

  private Long auditUserId() {
    return jdbcTemplate.queryForObject(
        "select id from user where username = ?", Long.class, "admin");
  }

  private void assertSchemaConstraintViolation(ThrowingCallable update, String constraintName) {
    var assertion =
        assertThatThrownBy(update)
            .isInstanceOfAny(
                UncategorizedSQLException.class,
                DataIntegrityViolationException.class,
                JpaSystemException.class);
    if (dbUtils.isMysql()) {
      assertion.hasMessageContaining(constraintName);
    }
  }

  private CmsPublishSnapshot publishReadySnapshot(String projectKeyPrefix) throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName(projectKeyPrefix + "_repository"));
    String projectKey = projectKeyPrefix + "-" + Long.toUnsignedString(System.nanoTime());
    CmsContentService.ProjectDetail detail =
        cmsContentService.createProject(
            new CmsContentService.ProjectCommand(
                projectKey, "Snapshot project", null, true, repository.getId(), null, null));
    Long projectId = detail.project().id();
    detail =
        cmsContentService.createContentType(
            projectId, new CmsContentService.ContentTypeCommand("banner", "Banner", null, 1, null));
    Long contentTypeId = detail.contentTypes().get(0).id();
    detail =
        cmsContentService.createContentTypeField(
            contentTypeId,
            new CmsContentService.ContentTypeFieldCommand(
                "headline", "Headline", null, CmsContentTypeField.FieldType.TEXT, true, true, 0));
    Long fieldId = detail.contentTypes().get(0).fields().get(0).id();
    detail =
        cmsContentService.createEntry(
            projectId,
            new CmsContentService.EntryCommand(
                contentTypeId, "welcome", "Welcome", null, CmsContentEntry.Status.DRAFT, null));
    Long variantId = detail.entries().get(0).variants().get(0).id();
    detail =
        cmsContentService.upsertFieldMapping(
            variantId,
            new CmsContentService.FieldMappingCommand(
                fieldId, null, "Hello", "Shown in rollback banner headline", null));
    detail = markEntryReady(detail);
    CmsContentService.PublishSnapshotView snapshot =
        publishProject(projectId, publishCommand(projectId, List.of()));
    return snapshotRepository.findById(snapshot.id()).orElseThrow();
  }

  private CmsPublishSnapshot savePublishSnapshot(String projectKeyPrefix) throws Exception {
    Repository repository =
        repositoryService.createRepository(
            testIdWatcher.getEntityName(projectKeyPrefix + "_repository"));
    String projectKey = projectKeyPrefix + "-" + Long.toUnsignedString(System.nanoTime());
    Long projectId =
        cmsContentService
            .createProject(
                new CmsContentService.ProjectCommand(
                    projectKey, "Snapshot project", null, true, repository.getId(), null, null))
            .project()
            .id();
    return savePublishSnapshot(projectId, 1);
  }

  private CmsPublishSnapshot savePublishSnapshot(Long projectId, int snapshotVersion) {
    String artifactJson = "{}";
    CmsPublishSnapshot snapshot = new CmsPublishSnapshot();
    CmsContentProject project =
        projectRepository.findByIdWithRepositoryAndAsset(projectId).orElseThrow();
    snapshot.setProject(project);
    snapshot.setCreatedByUser(userRepository.findByUsername("admin"));
    snapshot.setCreatedByUsername("admin");
    snapshot.setPublishedAt("2026-01-01T00:00:00Z");
    snapshot.setSnapshotVersion(snapshotVersion);
    snapshot.setPublishRequestKey("publish-request-" + snapshotVersion);
    snapshot.setPublishRequestLocaleTags("");
    snapshot.setPublishRequestAuthoringSha256(DigestUtils.sha256Hex("{}"));
    snapshot.setPublishRequestPackageSha256(DigestUtils.sha256Hex("{\"package\":true}"));
    snapshot.setStatus(CmsPublishSnapshot.Status.PUBLISHED);
    snapshot.setLocaleTags("en");
    snapshot.setArtifactJson(artifactJson);
    snapshot.setArtifactSha256(DigestUtils.sha256Hex(artifactJson));
    snapshot.setArtifactByteSize((long) artifactJson.getBytes(StandardCharsets.UTF_8).length);
    snapshot.setCompletenessJson("[]");
    snapshotSigningService.sign(snapshot);
    CmsPublishSnapshot saved = snapshotRepository.saveAndFlush(snapshot);
    CmsPublishSnapshotSeal seal = new CmsPublishSnapshotSeal();
    seal.setPublishSnapshotId(saved.getId());
    snapshotSealRepository.saveAndFlush(seal);
    project.setLastPublishedSnapshotVersion(snapshotVersion);
    projectRepository.saveAndFlush(project);
    return saved;
  }

  private CmsContentService.PublishCommand publishCommand(Long projectId, List<String> localeTags) {
    CmsContentService.ProjectCompletenessView validation =
        cmsContentService.getProjectCompleteness(projectId, localeTags);
    return new CmsContentService.PublishCommand(
        localeTags, validation.authoringSha256(), validation.publishPackageSha256());
  }

  private CmsContentService.PublishSnapshotView publishProject(
      Long projectId, CmsContentService.PublishCommand command) {
    return publishProject(projectId, command, "test-publish-" + projectId);
  }

  private CmsContentService.PublishSnapshotView publishProject(
      Long projectId, CmsContentService.PublishCommand command, String publishRequestKey) {
    return cmsContentService.publishProject(projectId, command, publishRequestKey);
  }

  private void insertPublishSnapshotCopy(
      CmsPublishSnapshot snapshot,
      int snapshotVersion,
      String publishRequestKey,
      String snapshotSignature,
      String artifactSignature,
      String publishRequestAuthoringSha256,
      String publishRequestPackageSha256,
      String publishedAt) {
    jdbcTemplate.update(
        """
        insert into cms_publish_snapshot
          (created_by_user_id, created_by_username, published_at, content_project_id,
           snapshot_version, publish_request_key, publish_request_locale_tags,
           publish_request_authoring_sha256, publish_request_package_sha256, status, locale_tags,
           artifact_json, artifact_sha256, artifact_byte_size, snapshot_signing_key_id,
           snapshot_signature, artifact_signature, completeness_json)
        select created_by_user_id, created_by_username, ?, content_project_id, ?,
               ?, publish_request_locale_tags, ?, ?, status, locale_tags, artifact_json,
               artifact_sha256, artifact_byte_size, snapshot_signing_key_id, ?, ?, completeness_json
        from cms_publish_snapshot
        where id = ?
        """,
        publishedAt == null ? snapshot.getPublishedAt() : publishedAt,
        snapshotVersion,
        publishRequestKey,
        publishRequestAuthoringSha256 == null
            ? snapshot.getPublishRequestAuthoringSha256()
            : publishRequestAuthoringSha256,
        publishRequestPackageSha256 == null
            ? snapshot.getPublishRequestPackageSha256()
            : publishRequestPackageSha256,
        snapshotSignature == null ? snapshot.getSnapshotSignature() : snapshotSignature,
        artifactSignature == null ? snapshot.getArtifactSignature() : artifactSignature,
        snapshot.getId());
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting to release CMS content project lock");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting to release CMS content project lock");
    }
  }

  private <T> T withAuthentication(Authentication authentication, Callable<T> work)
      throws Exception {
    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(authentication);
    SecurityContextHolder.setContext(securityContext);
    try {
      return work.call();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
