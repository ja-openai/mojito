package com.box.l10n.mojito.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobContainerClient;
import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.fileformat.MdxDocument;
import com.box.l10n.mojito.fileformat.MdxPreviewMessage;
import com.box.l10n.mojito.rest.resttemplate.AuthenticatedRestTemplate;
import com.box.l10n.mojito.service.assetintegritychecker.integritychecker.Mf2TranslationIntegrityChecker;
import com.box.l10n.mojito.service.blobstorage.Retention;
import com.box.l10n.mojito.service.blobstorage.azure.AzureBlobStorage;
import com.box.l10n.mojito.service.content.RepositoryContentIndex;
import com.box.l10n.mojito.service.content.RepositoryContentPreview;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The content demo must work through the packaged Java CLI without a Node or model process. */
@TestPropertySource(properties = {"l10n.blob-storage.default-type=AZURE"})
public class ContentDemoCreateCommandTest extends CLITestBase {

  private static final List<String> TARGET_LOCALES = List.of("fr", "de", "es", "ja", "ar");

  // Preserve real provider routing and the no-database-blob guard, replacing only external I/O.
  @MockitoBean AzureBlobStorage azure;
  @MockitoBean BlobContainerClient azureContainer;

  @Autowired RepositoryRepository repositories;
  @Autowired JdbcTemplate jdbc;
  @Autowired AuthenticatedRestTemplate client;
  @Autowired ObjectMapper objectMapper;

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

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
  public void createsSmallMultilingualContentRepositoryFromPackagedExample() throws Exception {
    String name = testIdWatcher.getEntityName("content");
    Path output = temporaryFolder.getRoot().toPath().resolve("site");
    int projectCount = jdbc.queryForObject("select count(*) from review_project", Integer.class);

    L10nJCommander command = getL10nJCommander();
    command.run("demo-create", "-n", name, "-t", "content", "-o", output.toString());

    assertThat(command.getExitCode()).as("content demo creation").isZero();
    Repository repository = repositories.findByName(name);
    assertThat(repository).isNotNull();
    assertThat(repository.getSourceLocale().getBcp47Tag()).isEqualTo("en");
    assertThat(repository.getRepositoryLocales())
        .extracting(locale -> locale.getLocale().getBcp47Tag())
        .containsExactlyInAnyOrder("en", "fr", "de", "es", "ja", "ar");
    assertThat(jdbc.queryForObject("select count(*) from review_project", Integer.class))
        .isEqualTo(projectCount);
    assertThat(outputCapture.toString()).contains("/content?repoId=" + repository.getId());

    Path example = repositoryRoot().resolve("examples/mdx-content-site");
    assertCopiedDirectory(example.resolve("content"), output.resolve("content"));
    assertCopiedDirectory(example.resolve("translations"), output.resolve("translations"));
    List<Path> sources = files(output.resolve("content"));
    assertThat(sources).hasSize(9);
    assertThat(sources.stream().filter(path -> path.toString().endsWith(".mdx"))).hasSize(8);
    assertThat(files(output.resolve("translations"))).hasSize(45);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from asset where repository_id = ?",
                Integer.class,
                repository.getId()))
        .isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from tm_text_unit tu join asset a on a.id = tu.asset_id "
                    + "where a.repository_id = ?",
                Integer.class,
                repository.getId()))
        .isEqualTo(25);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from asset_content c join asset a on a.id = c.asset_id "
                    + "where a.repository_id = ? and character_length(c.content) > 0",
                Integer.class,
                repository.getId()))
        .isZero();
    assertThat(externalObjects.keySet().stream().filter(key -> key.startsWith("asset_content/")))
        .hasSize(9);
    for (Path source : sources) {
      assertThat(externalObjects.values()).contains(Files.readString(source));
    }

    String endpoint = "api/repositories/" + repository.getId() + "/content";
    RepositoryContentIndex index = client.getForObject(endpoint, RepositoryContentIndex.class);
    assertThat(index.assets()).hasSize(8);
    assertThat(index.hasMore()).isFalse();
    Long homeId =
        index.assets().stream()
            .filter(asset -> "index.mdx".equals(asset.assetPath()))
            .findFirst()
            .orElseThrow()
            .assetId();

    var sourceMessages =
        MdxPreviewMessage.parseCatalog(
            Files.readString(output.resolve("content/messages.mf2.json")));
    var integrity = new Mf2TranslationIntegrityChecker();
    for (String locale : TARGET_LOCALES) {
      Path pulled = temporaryFolder.getRoot().toPath().resolve("pulled-" + locale);
      Files.createDirectories(pulled);
      L10nJCommander pull = getL10nJCommander();
      pull.run(
          "pull",
          "-r",
          name,
          "-s",
          output.resolve("content").toString(),
          "-t",
          pulled.toString(),
          "-ft",
          "MDX",
          "JSON",
          "-lm",
          locale + ":" + locale,
          "-lmt",
          "MAP_ONLY");
      assertThat(pull.getExitCode()).as("pull " + locale).isZero();
      assertThat(files(pulled)).hasSize(9);
      for (Path source : sources) {
        Path relative = output.resolve("content").relativize(source);
        String sourceName = relative.getFileName().toString();
        int extension = sourceName.lastIndexOf('.');
        Path localized =
            relative.resolveSibling(
                sourceName.substring(0, extension)
                    + "_"
                    + locale
                    + sourceName.substring(extension));
        Path expected = output.resolve("translations").resolve(localized);
        Path actual = pulled.resolve(localized);
        if (sourceName.endsWith(".mdx")) {
          assertThat(Files.readAllBytes(actual))
              .as(localized.toString())
              .isEqualTo(Files.readAllBytes(expected));
          assertThat(MdxDocument.parse(Files.readAllBytes(actual)).imports())
              .isEqualTo(MdxDocument.parse(Files.readAllBytes(source)).imports());
        } else {
          assertThat(objectMapper.readTree(actual.toFile()))
              .as(localized.toString())
              .isEqualTo(objectMapper.readTree(expected.toFile()));
          var targetMessages = MdxPreviewMessage.parseCatalog(Files.readString(actual));
          for (var message : sourceMessages.entrySet()) {
            integrity.check(message.getValue(), targetMessages.get(message.getKey()), locale);
          }
        }
      }
      RepositoryContentPreview preview =
          client.getForObject(
              endpoint + "/" + homeId + "?locale=" + locale, RepositoryContentPreview.class);
      assertThat(preview.warnings()).as(locale).isEmpty();
      assertThat(preview.document().warnings()).as(locale).isEmpty();
      assertThat(preview.localeTag()).isEqualTo(locale);
      assertThat(preview.document().blocks())
          .anyMatch(block -> block.moduleDepth() > 1 && block.translatable());
      assertThat(
              preview.document().blocks().stream()
                  .filter(ReviewProjectDocumentView.Block::translatable))
          .allSatisfy(
              block -> {
                assertThat(block.mappingStatus()).isEqualTo(MappingStatus.MATCHED);
                assertThat(block.targetContent()).isNotEmpty();
                assertThat(block.tmTextUnitVariantId()).isPositive();
                assertThat(block.reviewProjectTextUnitId()).isNull();
              });
      assertThat(preview.document().blocks().stream().filter(block -> "mf2".equals(block.type())))
          .hasSize(2);
    }
    assertThat(jdbc.queryForObject("select count(*) from mblob", Integer.class)).isZero();
  }

  @Test
  public void createsEmailDemoWithSeparatePartsAndRunnableRenderer() throws Exception {
    String name = testIdWatcher.getEntityName("email");
    Path output = temporaryFolder.getRoot().toPath().resolve("email");
    L10nJCommander command = getL10nJCommander();
    command.run("demo-create", "-n", name, "-t", "email", "-o", output.toString());
    assertThat(command.getExitCode()).as("email demo creation").isZero();
    Repository repository = repositories.findByName(name);
    assertThat(repository).isNotNull();
    assertThat(files(output.resolve("content"))).hasSize(8);
    assertThat(files(output.resolve("translations"))).hasSize(40);
    for (String file :
        List.of(
            "package.json",
            "package-lock.json",
            "render.jsx",
            ".gitignore",
            "scripts/prepare.mjs",
            "scripts/validate.mjs",
            "scripts/export.mjs")) {
      assertThat(output.resolve(file)).isRegularFile();
    }
    Path pulled = temporaryFolder.newFolder("email-pulled").toPath();
    L10nJCommander pull = getL10nJCommander();
    pull.run(
        "pull",
        "-r",
        name,
        "-s",
        output.resolve("content").toString(),
        "-t",
        pulled.toString(),
        "-ft",
        "MDX",
        "-lm",
        "fr:fr",
        "-lmt",
        "MAP_ONLY");
    assertThat(pull.getExitCode()).isZero();
    for (Path source : files(output.resolve("content"))) {
      Path relative = output.resolve("content").relativize(source);
      Path target =
          relative.resolveSibling(relative.getFileName().toString().replace(".mdx", "_fr.mdx"));
      assertThat(Files.readString(pulled.resolve(target)))
          .isEqualTo(Files.readString(output.resolve("translations").resolve(target)));
    }
    String endpoint = "api/repositories/" + repository.getId() + "/content";
    var index = client.getForObject(endpoint, RepositoryContentIndex.class);
    assertThat(index.assets()).hasSize(8);
    for (var asset : index.assets()) {
      var preview =
          client.getForObject(
              endpoint + "/" + asset.assetId() + "?locale=fr", RepositoryContentPreview.class);
      assertThat(preview.warnings()).isEmpty();
      assertThat(preview.document().warnings()).isEmpty();
      assertThat(
              preview.document().blocks().stream()
                  .filter(ReviewProjectDocumentView.Block::translatable))
          .allSatisfy(block -> assertThat(block.targetContent()).isNotEmpty());
    }
    assertThat(jdbc.queryForObject("select count(*) from mblob", Integer.class)).isZero();
  }

  @Test
  public void rejectsUnknownDemoTypeBeforeCreatingRepositoryOrFiles() {
    String name = testIdWatcher.getEntityName("unknown");
    Path output = temporaryFolder.getRoot().toPath().resolve("site");
    L10nJCommander command = getL10nJCommander();
    command.run("demo-create", "-n", name, "-t", "unknown", "-o", output.toString());

    assertThat(command.getExitCode()).isNotZero();
    assertThat(repositories.findByName(name)).isNull();
    assertThat(output).doesNotExist();
    assertThat(externalObjects).isEmpty();
  }

  @Test
  public void refusesNonemptyOutputBeforeCreatingRepository() throws Exception {
    String name = testIdWatcher.getEntityName("existing");
    Path output = temporaryFolder.newFolder("site").toPath();
    Path existing = output.resolve("keep.txt");
    Files.writeString(existing, "Keep this existing file.");
    L10nJCommander command = getL10nJCommander();
    command.run("demo-create", "-n", name, "-t", "content", "-o", output.toString());

    assertThat(command.getExitCode()).isNotZero();
    assertThat(repositories.findByName(name)).isNull();
    assertThat(Files.readString(existing)).isEqualTo("Keep this existing file.");
    assertThat(files(output)).containsExactly(existing);
    assertThat(externalObjects).isEmpty();
  }

  @Test
  public void refusesExistingRepositoryWithoutChangingItsContent() throws Exception {
    String name = testIdWatcher.getEntityName("existing-content");
    Path source = temporaryFolder.newFolder("original-source").toPath();
    Path translated = temporaryFolder.newFolder("original-translations").toPath();
    Files.writeString(
        source.resolve("index.mdx"), "{/* mojito-id: home.title */}\n# Existing page\n");
    Files.writeString(
        translated.resolve("index_fr.mdx"), "{/* mojito-id: home.title */}\n# Page existante\n");
    L10nJCommander create = getL10nJCommander();
    create.run("repo-create", "-n", name, "-l", "fr");
    assertThat(create.getExitCode()).isZero();
    L10nJCommander push = getL10nJCommander();
    push.run("push", "-r", name, "-s", source.toString(), "-ft", "MDX");
    assertThat(push.getExitCode()).isZero();
    L10nJCommander seed = getL10nJCommander();
    seed.run(
        "import",
        "-r",
        name,
        "-s",
        source.toString(),
        "-t",
        translated.toString(),
        "-ft",
        "MDX",
        "-lm",
        "fr:fr",
        "-lmt",
        "MAP_ONLY");
    assertThat(seed.getExitCode()).isZero();

    Repository original = repositories.findByName(name);
    String snapshotQuery =
        "select a.id as asset_id, a.path, a.last_successful_asset_extraction_id, "
            + "tu.id as unit_id, tu.name, tu.content as source, "
            + "v.id as variant_id, v.content as target, v.status "
            + "from asset a join tm_text_unit tu on tu.asset_id = a.id "
            + "join tm_text_unit_current_variant cv on cv.tm_text_unit_id = tu.id "
            + "join tm_text_unit_variant v on v.id = cv.tm_text_unit_variant_id "
            + "where a.repository_id = ? order by a.id, tu.id, v.id";
    var before = jdbc.queryForList(snapshotQuery, original.getId());
    assertThat(before).isNotEmpty();
    assertThat(before).anySatisfy(row -> assertThat(row).containsEntry("TARGET", "Page existante"));

    Path output = temporaryFolder.getRoot().toPath().resolve("content-demo");
    L10nJCommander command = getL10nJCommander();
    command.run("demo-create", "-n", name, "-t", "content", "-o", output.toString());

    assertThat(command.getExitCode()).isNotZero();
    Repository after = repositories.findByName(name);
    assertThat(after.getId()).isEqualTo(original.getId());
    assertThat(after.getSourceLocale().getBcp47Tag()).isEqualTo("en");
    assertThat(after.getRepositoryLocales())
        .extracting(locale -> locale.getLocale().getBcp47Tag())
        .containsExactlyInAnyOrder("en", "fr");
    assertThat(jdbc.queryForList(snapshotQuery, original.getId())).isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from asset where repository_id = ?",
                Integer.class,
                original.getId()))
        .isEqualTo(1);
    assertThat(outputCapture.toString()).doesNotContain("/content?repoId=");
  }

  @Test
  public void reportsNestedPushFailureWithoutClaimingDemoIsReady() {
    doThrow(new IllegalStateException("Demo fixture storage is unavailable"))
        .when(azure)
        .put(startsWith("asset_content/"), anyString(), any(Retention.class));
    String name = testIdWatcher.getEntityName("failed-push");
    Path output = temporaryFolder.getRoot().toPath().resolve("site");
    L10nJCommander command = getL10nJCommander();
    command.run("demo-create", "-n", name, "-t", "content", "-o", output.toString());

    assertThat(command.getExitCode()).isNotZero();
    assertThat(outputCapture.toString()).doesNotContain("Demo repository is ready!");
    assertThat(outputCapture.toString()).doesNotContain("/content?repoId=");
  }

  private static void assertCopiedDirectory(Path expected, Path actual) throws Exception {
    List<Path> expectedFiles = files(expected);
    assertThat(files(actual).stream().map(actual::relativize))
        .containsExactlyElementsOf(expectedFiles.stream().map(expected::relativize).toList());
    for (Path file : expectedFiles) {
      assertThat(Files.readAllBytes(actual.resolve(expected.relativize(file))))
          .as(expected.relativize(file).toString())
          .isEqualTo(Files.readAllBytes(file));
    }
  }

  private static List<Path> files(Path directory) throws Exception {
    try (var files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).sorted().toList();
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null && !Files.isDirectory(current.resolve("examples/mdx-content-site"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IllegalStateException("Cannot find the canonical MDX example");
    }
    return current;
  }
}
