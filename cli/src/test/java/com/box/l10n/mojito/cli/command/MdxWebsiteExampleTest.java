package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.fileformat.MdxDocument;
import com.box.l10n.mojito.fileformat.MdxPreviewMessage;
import com.box.l10n.mojito.rest.resttemplate.AuthenticatedRestTemplate;
import com.box.l10n.mojito.rest.textunit.TextUnitSaveRequest;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.Mf2TranslationIntegrityChecker;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.content.ContentTranslationCandidate;
import com.box.l10n.mojito.service.content.RepositoryContentIndex;
import com.box.l10n.mojito.service.content.RepositoryContentPreview;
import com.box.l10n.mojito.service.locale.LocaleService;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitCurrentVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exercises the example with real CLI commands and HTTP APIs against an isolated HSQL app. */
@TestPropertySource(
    properties = {
      "l10n.blob-storage.default-type=AZURE",
      // Keep the cached Azure context separate from database-backed CLI tests.
      "spring.datasource.url=jdbc:hsqldb:mem:cli_azure_storage;DB_CLOSE_DELAY=-1"
    })
public class MdxWebsiteExampleTest extends CLITestBase {

  // Only the external provider is replaced. The production routing and MDX storage guard remain
  // active, and no Azure credentials or network calls are needed for this local integration test.
  @MockitoBean AzureBlobStorage azure;
  @MockitoBean BlobContainerClient azureContainer;

  @LocalServerPort int demoPort;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuthenticatedRestTemplate client;
  @Autowired LocaleService locales;
  @Autowired RepositoryRepository repositories;
  @Autowired ReviewProjectRepository projects;
  @Autowired ReviewProjectTextUnitRepository projectRows;
  @Autowired TMTextUnitCurrentVariantRepository currentVariants;

  private final Map<String, String> externalObjects = new ConcurrentHashMap<>();

  @Before
  public void configureExternalProvider() {
    doAnswer(
            invocation -> {
              externalObjects.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(azure)
        .put(anyString(), anyString(), any(Retention.class));
    when(azure.getString(anyString()))
        .thenAnswer(
            invocation -> Optional.ofNullable(externalObjects.get(invocation.getArgument(0))));
    when(azure.getBytes(anyString()))
        .thenAnswer(
            invocation ->
                Optional.ofNullable(externalObjects.get(invocation.getArgument(0)))
                    .map(content -> content.getBytes(StandardCharsets.UTF_8)));
    when(azure.exists(anyString()))
        .thenAnswer(invocation -> externalObjects.containsKey(invocation.getArgument(0)));
  }

  @Test
  public void pushImportPullAndReviewTheNestedFrenchWebsite() throws Exception {
    Path root = repositoryRoot();
    Path example = root.resolve("examples/mdx-content-site");
    Path source = example.resolve("content");
    Path translations = example.resolve("translations");
    Path output = root.resolve("cli/target/mdx-content-site/localized");
    if (Files.exists(output)) {
      try (var previousOutput = Files.walk(output)) {
        for (Path path : previousOutput.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
    Files.createDirectories(output);
    String repositoryName = testIdWatcher.getEntityName("mdx-site");
    L10nJCommander create = getL10nJCommander();
    create.run("repo-create", "-n", repositoryName, "-l", "fr", "-it", "json:MF2");
    assertThat(create.getExitCode()).as("repo-create exit code").isZero();
    Repository repository = repositories.findByName(repositoryName);
    assertThat(repository).isNotNull();

    runCommand("push", repository, source, null);
    List<Path> sourceFiles = mdxFiles(source);
    assertThat(sourceFiles).isNotEmpty();
    Path catalogFile = source.resolve("messages.mf2.json");
    var sourceMessages = MdxPreviewMessage.parseCatalog(Files.readString(catalogFile));
    int expectedAssets = sourceFiles.size() + 1;
    int expectedTextUnits = sourceMessages.size();
    for (Path file : sourceFiles) {
      expectedTextUnits +=
          (int)
              MdxDocument.parse(Files.readAllBytes(file)).blocks().stream()
                  .filter(MdxDocument.Block::translatable)
                  .count();
    }
    var sourceObjectNames =
        externalObjects.keySet().stream()
            .filter(name -> name.startsWith("asset_content/"))
            .toList();
    assertThat(sourceObjectNames).hasSize(expectedAssets);
    assertThat(externalObjects.values()).contains(Files.readString(catalogFile));
    assertThat(sourceObjectNames)
        .allMatch(name -> name.matches("asset_content/v1/[0-9]+/[0-9]+/source/[a-f0-9]{32}"));
    for (Path file : sourceFiles) {
      assertThat(externalObjects.values()).contains(Files.readString(file));
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from asset_content c join asset a on a.id = c.asset_id "
                    + "where a.repository_id = ?",
                Integer.class,
                repository.getId()))
        .isEqualTo(expectedAssets);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from asset_content c join asset a on a.id = c.asset_id "
                    + "where a.repository_id = ? and character_length(c.content) > 0",
                Integer.class,
                repository.getId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from mblob where name like 'asset_content/%'", Integer.class))
        .isZero();

    assertCandidateImport(
        repository,
        sourceMessages,
        MdxPreviewMessage.parseCatalog(
            Files.readString(translations.resolve("messages.mf2_fr.json"))));
    runCommand("import", repository, source, translations);
    runCommand("pull", repository, source, output);
    // This includes ordinary translation DTO caches as well as full templates and task payloads.
    assertThat(jdbc.queryForObject("select count(*) from mblob", Integer.class)).isZero();
    assertThat(mdxFiles(output)).hasSize(sourceFiles.size());
    for (Path file : sourceFiles) {
      Path relative = source.relativize(file);
      Path localized =
          relative.resolveSibling(relative.getFileName().toString().replace(".mdx", "_fr.mdx"));
      byte[] pulled = Files.readAllBytes(output.resolve(localized));
      assertThat(pulled)
          .as(localized.toString())
          .isEqualTo(Files.readAllBytes(translations.resolve(localized)));
      assertThat(MdxDocument.parse(pulled).imports())
          .isEqualTo(MdxDocument.parse(Files.readAllBytes(file)).imports());
    }

    assertThat(objectMapper.readTree(output.resolve("messages.mf2_fr.json").toFile()))
        .isEqualTo(objectMapper.readTree(translations.resolve("messages.mf2_fr.json").toFile()));
    var targetMessages =
        MdxPreviewMessage.parseCatalog(Files.readString(output.resolve("messages.mf2_fr.json")));
    var integrity = new Mf2TranslationIntegrityChecker();
    for (var entry : sourceMessages.entrySet()) {
      integrity.check(entry.getValue(), targetMessages.get(entry.getKey()), "fr");
    }

    assertRepositoryContent(repository, sourceFiles.size());
    assertNestedReviewDocuments(repository, sourceFiles.size(), expectedTextUnits);
    assertSavedCorrectionRoundTrip(repository, source, output);
    if (Boolean.getBoolean("mojito.mdxExample.demo")) {
      Path demoMetadata = root.resolve("cli/target/mdx-content-site/demo.json");
      objectMapper.writeValue(
          demoMetadata.toFile(),
          Map.of(
              "port",
              demoPort,
              "repositoryId",
              repository.getId(),
              "repositoryName",
              repository.getName()));
      // Explicit opt-in only: retain this isolated test app for real UI/CLI exploration until
      // stopped.
      new java.util.concurrent.CountDownLatch(1).await();
    }
  }

  private void assertCandidateImport(
      Repository repository,
      Map<String, String> sourceMessages,
      Map<String, String> targetMessages) {
    for (var entry : sourceMessages.entrySet()) {
      var metadata =
          jdbc.queryForMap(
              """
          select tu.id as unit_id, asset.id as asset_id, mapping.branch_id, mapping.asset_extraction_id,
            extraction.content_md5, asset.last_successful_asset_extraction_id as merged_extraction_id
          from tm_text_unit tu join asset on asset.id = tu.asset_id
          join asset_extraction_by_branch mapping on mapping.asset_id = asset.id
          join asset_extraction extraction on extraction.id = mapping.asset_extraction_id
          where asset.repository_id = ? and asset.path = 'messages.mf2.json'
            and tu.name = ? and mapping.deleted = false
          """,
              repository.getId(),
              entry.getKey());
      String endpoint =
          "api/repositories/" + repository.getId() + "/content/translation-candidates";
      var source =
          client.getForObject(
              endpoint
                  + "/source?branchId="
                  + metadata.get("branch_id")
                  + "&assetId="
                  + metadata.get("asset_id"),
              ContentTranslationCandidate.Source.class);
      assertThat(source.repositoryId()).isEqualTo(repository.getId());
      assertThat(source.assetPath()).isEqualTo("messages.mf2.json");
      assertThat(source.assetExtractionId())
          .isEqualTo(((Number) metadata.get("asset_extraction_id")).longValue())
          .isNotEqualTo(((Number) metadata.get("merged_extraction_id")).longValue());
      assertThat(source.assetContentMd5()).isEqualTo(metadata.get("content_md5"));
      var candidate =
          new ContentTranslationCandidate(
              source.branchId(),
              source.assetId(),
              ((Number) metadata.get("unit_id")).longValue(),
              locales.findByBcp47Tag("fr").getId(),
              entry.getKey(),
              entry.getValue(),
              source.assetExtractionId(),
              source.assetContentMd5(),
              null,
              targetMessages.get(entry.getKey()));
      var stale =
          new ContentTranslationCandidate(
              candidate.branchId(),
              candidate.assetId(),
              candidate.tmTextUnitId(),
              candidate.localeId(),
              candidate.name(),
              candidate.expectedSource(),
              candidate.expectedAssetExtractionId(),
              "0".repeat(32),
              null,
              candidate.target());
      var conflict =
          org.junit.Assert.assertThrows(
              org.springframework.web.client.HttpClientErrorException.class,
              () ->
                  client.postForObject(endpoint, stale, ContentTranslationCandidate.Result.class));
      assertThat(conflict.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
      var droppedArgument =
          new ContentTranslationCandidate(
              candidate.branchId(),
              candidate.assetId(),
              candidate.tmTextUnitId(),
              candidate.localeId(),
              candidate.name(),
              candidate.expectedSource(),
              candidate.expectedAssetExtractionId(),
              candidate.expectedAssetContentMd5(),
              null,
              "Texte sans argument.");
      var invalid =
          org.junit.Assert.assertThrows(
              org.springframework.web.client.HttpClientErrorException.class,
              () ->
                  client.postForObject(
                      endpoint, droppedArgument, ContentTranslationCandidate.Result.class));
      assertThat(invalid.getStatusCode())
          .isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
      var accepted =
          client.postForObject(endpoint, candidate, ContentTranslationCandidate.Result.class);
      assertThat(accepted.status()).isEqualTo(TMTextUnitVariant.Status.REVIEW_NEEDED);
      assertThat(accepted.target()).isEqualTo(candidate.target());
      var duplicate =
          org.junit.Assert.assertThrows(
              org.springframework.web.client.HttpClientErrorException.class,
              () ->
                  client.postForObject(
                      endpoint, candidate, ContentTranslationCandidate.Result.class));
      assertThat(duplicate.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }
  }

  private void assertRepositoryContent(Repository repository, int expectedAssets) {
    assertThat(jdbc.queryForObject("select count(*) from review_project", Integer.class)).isZero();
    String endpoint = "api/repositories/" + repository.getId() + "/content";
    var index = client.getForObject(endpoint, RepositoryContentIndex.class);
    assertThat(index.assets()).hasSize(expectedAssets);
    assertThat(index.branches()).hasSize(1);
    assertThat(index.branchId()).isPositive();
    assertThat(index.hasMore()).isFalse();
    var filtered =
        client.getForObject(endpoint + "?q=modules&limit=2", RepositoryContentIndex.class);
    assertThat(filtered.assets())
        .hasSize(2)
        .allMatch(asset -> asset.assetPath().contains("modules"));
    assertThat(filtered.hasMore()).isTrue();
    var homeAsset =
        index.assets().stream()
            .filter(asset -> asset.assetPath().equals("index.mdx"))
            .findFirst()
            .orElseThrow();
    var preview =
        client.getForObject(
            endpoint + "/" + homeAsset.assetId() + "?locale=fr", RepositoryContentPreview.class);
    assertThat(preview.warnings()).isEmpty();
    assertThat(preview.localeTag()).isEqualTo("fr");
    assertThat(preview.document().warnings()).isEmpty();
    assertThat(preview.document().sourceLocaleTag()).isEqualTo("en");
    assertThat(
            preview.document().blocks().stream()
                .filter(ReviewProjectDocumentView.Block::translatable))
        .allSatisfy(
            block -> {
              assertThat(block.mappingStatus()).isEqualTo(MappingStatus.MATCHED);
              assertThat(block.tmTextUnitId()).isPositive();
              assertThat(block.reviewProjectTextUnitId()).isNull();
              assertThat(block.targetContent()).isNotEmpty();
              assertThat(block.tmTextUnitVariantId()).isPositive();
            });
    assertThat(
            preview.document().blocks().stream()
                .filter(block -> "audience.team.title".equals(block.id())))
        .singleElement()
        .satisfies(block -> assertThat(block.targetContent()).isEqualTo("Équipe"));
    assertThat(preview.document().blocks().stream().filter(block -> "mf2".equals(block.type())))
        .hasSize(2)
        .allSatisfy(
            block -> {
              assertThat(block.assetPath()).isEqualTo("messages.mf2.json");
              assertThat(block.previewArgs()).isNotEmpty();
            });
    assertThat(jdbc.queryForObject("select count(*) from review_project", Integer.class)).isZero();
  }

  private void assertSavedCorrectionRoundTrip(Repository repository, Path source, Path output)
      throws Exception {
    var locale = locales.findByBcp47Tag("fr");
    var current =
        jdbc.queryForMap(
            """
        select current_variant.tm_text_unit_id as unit_id, current_variant.tm_text_unit_variant_id as variant_id
        from tm_text_unit_current_variant current_variant
        join tm_text_unit unit on unit.id = current_variant.tm_text_unit_id
        join asset on asset.id = unit.asset_id
        where asset.repository_id = ? and asset.path = 'messages.mf2.json'
          and unit.name = 'calendar.date' and current_variant.locale_id = ?
        """,
            repository.getId(),
            locale.getId());
    String correction = "Votre séance est prévue le {$date :date dateStyle=long timeZone=UTC}.";
    var save = new TextUnitSaveRequest();
    save.setTmTextUnitId(((Number) current.get("unit_id")).longValue());
    save.setLocaleId(locale.getId());
    save.setTarget(correction);
    save.setStatus(TMTextUnitVariant.Status.APPROVED);
    save.setIncludedInLocalizedFile(true);
    save.setReviewedVariantId(((Number) current.get("variant_id")).longValue());
    save.setFeedbackOperationId(java.util.UUID.randomUUID().toString());
    var saved = client.postForObject("api/textunits", save, TextUnitDTO.class);
    assertThat(saved.getTarget()).isEqualTo(correction);
    runCommand("pull", repository, source, output);
    assertThat(
            objectMapper
                .readTree(output.resolve("messages.mf2_fr.json").toFile())
                .get("calendar.date")
                .asText())
        .isEqualTo(correction);
    String endpoint = "api/repositories/" + repository.getId() + "/content";
    var index = client.getForObject(endpoint, RepositoryContentIndex.class);
    Long home =
        index.assets().stream()
            .filter(asset -> "index.mdx".equals(asset.assetPath()))
            .findFirst()
            .orElseThrow()
            .assetId();
    var preview =
        client.getForObject(endpoint + "/" + home + "?locale=fr", RepositoryContentPreview.class);
    assertThat(
            preview.document().blocks().stream()
                .filter(block -> "calendar.date".equals(block.id())))
        .singleElement()
        .satisfies(block -> assertThat(block.targetContent()).isEqualTo(correction));
  }

  private void assertNestedReviewDocuments(
      Repository repository, int expectedAssets, int expectedTextUnits) {
    var french = locales.findByBcp47Tag("fr");
    var variants =
        currentVariants.findByTmTextUnit_Tm_IdAndLocale_Id(
            repository.getTm().getId(), french.getId());
    assertThat(variants).hasSize(expectedTextUnits);
    ReviewProject project = new ReviewProject();
    project.setLocale(french);
    project.setDueDate(ZonedDateTime.now().plusDays(1));
    project.setTextUnitCount(variants.size());
    project = projects.saveAndFlush(project);
    for (var variant : variants) {
      ReviewProjectTextUnit row = new ReviewProjectTextUnit();
      row.setReviewProject(project);
      row.setTmTextUnit(variant.getTmTextUnit());
      row.setTmTextUnitVariant(variant.getTmTextUnitVariant());
      projectRows.save(row);
    }
    var preview =
        client.getForObject(
            "api/review-projects/" + project.getId() + "/documents",
            ReviewProjectDocumentView.class);
    assertThat(preview.warnings()).isEmpty();
    assertThat(preview.documents())
        .hasSize(expectedAssets)
        .allSatisfy(document -> assertThat(document.warnings()).isEmpty());
    var home =
        preview.documents().stream()
            .filter(document -> document.assetPath().equals("index.mdx"))
            .findFirst()
            .orElseThrow();
    assertThat(home.sourceLocaleTag()).isEqualTo("en");
    assertThat(home.blocks())
        .anyMatch(block -> block.moduleDepth() == 3 && "tip.body".equals(block.id()));
    assertThat(home.blocks().stream().filter(ReviewProjectDocumentView.Block::translatable))
        .allMatch(
            block ->
                block.mappingStatus() == MappingStatus.MATCHED
                    && block.reviewProjectTextUnitId() != null);
    assertThat(home.blocks())
        .extracting(ReviewProjectDocumentView.Block::id)
        .contains(
            "audience.individual.title",
            "audience.individual.body",
            "audience.team.title",
            "audience.team.body");
    assertThat(home.blocks().stream().filter(block -> block.source().contains("PreviewChoice")))
        .hasSize(2)
        .allMatch(block -> block.moduleStatus() == null && block.moduleWarning() == null);
    var shared = home.blocks().stream().filter(block -> "shared.note".equals(block.id())).toList();
    assertThat(shared).hasSize(2);
    assertThat(shared.get(0).reviewProjectTextUnitId())
        .isEqualTo(shared.get(1).reviewProjectTextUnitId());
    assertThat(shared.get(0).occurrenceId()).isNotEqualTo(shared.get(1).occurrenceId());
  }

  private void runCommand(String command, Repository repository, Path source, Path target) {
    List<String> arguments =
        new ArrayList<>(
            List.of(
                command,
                "-r",
                repository.getName(),
                "-s",
                source.toString(),
                "-ft",
                "MDX",
                "JSON"));
    if (target != null) {
      arguments.addAll(List.of("-t", target.toString(), "-lm", "fr:fr", "-lmt", "MAP_ONLY"));
    }
    L10nJCommander cli = getL10nJCommander();
    cli.run(arguments.toArray(String[]::new));
    assertThat(cli.getExitCode()).as(command + " exit code").isZero();
  }

  private static List<Path> mdxFiles(Path directory) throws Exception {
    try (var files = Files.walk(directory)) {
      return files
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".mdx"))
          .sorted()
          .toList();
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("examples/mdx-content-site"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException(
          "Cannot find the MDX website example from the test directory");
    }
    return current;
  }
}
