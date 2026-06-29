package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator.ArtifactKind;
import com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator.ArtifactResult;
import com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator.ArtifactStatus;
import com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator.ReleaseArtifact;
import com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator.ReleaseValidationReport;
import com.box.l10n.mojito.mf2.inflection.Mf2InflectionReleaseValidator.Summary;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Mf2InflectionReleaseValidatorTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Mf2InflectionReleaseValidator validator = new Mf2InflectionReleaseValidator();

  @Test
  public void validatesRuntimeReleaseArtifacts() {
    ReleaseValidationReport report =
        validator.validate(
            List.of(
                ReleaseArtifact.compiledTermPackJson(
                    "es-terms-json", compiledFixture("es_compiled_article_pack_fixture.json")),
                ReleaseArtifact.compiledTermPackM2if(
                    "es-terms-m2if", m2ifFixture("es_compiled_article_pack_fixture.m2if.hex")),
                ReleaseArtifact.hindiPronounAgreementPackJson(
                    "hi-pronouns", compiledFixture("hi_pronoun_agreement_pack_fixture.json"))));

    assertThat(report.passed()).isTrue();
    assertThat(report.summary().artifacts()).isEqualTo(3);
    assertThat(report.summary().passed()).isEqualTo(3);
    assertThat(report.summary().failed()).isZero();
    assertThat(report.artifacts())
        .extracting(ArtifactResult::artifactId)
        .containsExactly("es-terms-json", "es-terms-m2if", "hi-pronouns");
    assertThat(report.artifacts()).allSatisfy(result -> assertThat(result.code()).isNull());
  }

  @Test
  public void reportsCompiledJsonValidationFailureWithoutThrowing() {
    String invalidJson =
        compiledFixture("es_compiled_article_pack_fixture.json")
            .replaceFirst("\"sha256\": \"[0-9a-f]{64}\"", "\"sha256\": \"abc123\"");

    ReleaseValidationReport report =
        validator.validate(List.of(ReleaseArtifact.compiledTermPackJson("es-terms", invalidJson)));

    assertThat(report.passed()).isFalse();
    assertThat(report.summary().failed()).isEqualTo(1);
    assertThat(report.artifacts().getFirst())
        .extracting(
            ArtifactResult::artifactId,
            ArtifactResult::kind,
            ArtifactResult::status,
            ArtifactResult::code)
        .containsExactly(
            "es-terms",
            ArtifactKind.COMPILED_TERM_PACK_JSON,
            ArtifactStatus.FAILED,
            "invalid-compiled-term-pack-json");
    assertThat(report.artifacts().getFirst().message())
        .contains("sha256 must be a 64-character lowercase SHA-256 hex digest");
  }

  @Test
  public void reportsCompiledJsonProvenanceFailureWithoutThrowing() {
    String invalidJson =
        compiledFixture("es_compiled_article_pack_fixture.json")
            .replace(
                "    \"license\": \"Unicode-3.0\",\n    \"sourceLabels\":",
                "    \"sourceLabels\":");

    ReleaseValidationReport report =
        validator.validate(List.of(ReleaseArtifact.compiledTermPackJson("es-terms", invalidJson)));

    assertThat(report.passed()).isFalse();
    assertThat(report.summary().failed()).isEqualTo(1);
    assertThat(report.artifacts().getFirst())
        .extracting(
            ArtifactResult::artifactId,
            ArtifactResult::kind,
            ArtifactResult::status,
            ArtifactResult::code)
        .containsExactly(
            "es-terms",
            ArtifactKind.COMPILED_TERM_PACK_JSON,
            ArtifactStatus.FAILED,
            "invalid-compiled-term-pack-json");
    assertThat(report.artifacts().getFirst().message())
        .contains("Source-backed provenance requires license");
  }

  @Test
  public void reportsBinaryValidationFailureWithoutThrowing() {
    byte[] invalidM2if = m2ifFixture("es_compiled_article_pack_fixture.m2if.hex");
    invalidM2if[0] = 'X';

    ReleaseValidationReport report =
        validator.validate(List.of(ReleaseArtifact.compiledTermPackM2if("es-terms", invalidM2if)));

    assertThat(report.passed()).isFalse();
    assertThat(report.artifacts().getFirst().code()).isEqualTo("invalid-compiled-term-pack-m2if");
    assertThat(report.artifacts().getFirst().message())
        .contains("Invalid compiled term pack magic");
  }

  @Test
  public void reportsBinaryProvenanceFailureWithoutThrowing() {
    byte[] invalidM2if =
        replaceFirstAscii(
            m2ifFixture("es_compiled_article_pack_fixture.m2if.hex"),
            "\"license\"",
            "\"xicense\"");

    ReleaseValidationReport report =
        validator.validate(List.of(ReleaseArtifact.compiledTermPackM2if("es-terms", invalidM2if)));

    assertThat(report.passed()).isFalse();
    assertThat(report.summary().failed()).isEqualTo(1);
    assertThat(report.artifacts().getFirst())
        .extracting(
            ArtifactResult::artifactId,
            ArtifactResult::kind,
            ArtifactResult::status,
            ArtifactResult::code)
        .containsExactly(
            "es-terms",
            ArtifactKind.COMPILED_TERM_PACK_M2IF,
            ArtifactStatus.FAILED,
            "invalid-compiled-term-pack-m2if");
    assertThat(report.artifacts().getFirst().message())
        .contains("Source-backed provenance requires license");
  }

  @Test
  public void reportsHindiPronounValidationFailureWithoutThrowing() {
    String invalidJson =
        compiledFixture("hi_pronoun_agreement_pack_fixture.json")
            .replaceFirst("\"sha256\": \"[0-9a-f]{64}\"", "\"sha256\": \"abc123\"");

    ReleaseValidationReport report =
        validator.validate(
            List.of(ReleaseArtifact.hindiPronounAgreementPackJson("hi-pronouns", invalidJson)));

    assertThat(report.passed()).isFalse();
    assertThat(report.artifacts().getFirst().code())
        .isEqualTo("invalid-hindi-pronoun-agreement-pack-json");
    assertThat(report.artifacts().getFirst().message())
        .contains("sha256 must be a 64-character lowercase SHA-256 hex digest");
  }

  @Test
  public void reportsHindiPronounSummaryFailureWithoutThrowing() {
    String invalidJson =
        compiledFixture("hi_pronoun_agreement_pack_fixture.json")
            .replace("\"dependencyRows\": 20", "\"dependencyRows\": 19");

    ReleaseValidationReport report =
        validator.validate(
            List.of(ReleaseArtifact.hindiPronounAgreementPackJson("hi-pronouns", invalidJson)));

    assertThat(report.passed()).isFalse();
    assertThat(report.summary().failed()).isEqualTo(1);
    assertThat(report.artifacts().getFirst())
        .extracting(
            ArtifactResult::artifactId,
            ArtifactResult::kind,
            ArtifactResult::status,
            ArtifactResult::code)
        .containsExactly(
            "hi-pronouns",
            ArtifactKind.HINDI_PRONOUN_AGREEMENT_PACK_JSON,
            ArtifactStatus.FAILED,
            "invalid-hindi-pronoun-agreement-pack-json");
    assertThat(report.artifacts().getFirst().message())
        .contains("Hindi pronoun dependency row count mismatch");
  }

  @Test
  public void writesDeterministicJsonReport() {
    ReleaseValidationReport report =
        validator.validate(
            List.of(
                ReleaseArtifact.compiledTermPackM2if("z-invalid", new byte[] {'X'}),
                ReleaseArtifact.compiledTermPackJson(
                    "a-valid", compiledFixture("es_compiled_article_pack_fixture.json"))));

    assertThat(validator.writeJson(report))
        .isEqualTo(
            """
            {
              "artifacts" : [ {
                "artifactId" : "a-valid",
                "kind" : "compiled-term-pack-json",
                "status" : "passed"
              }, {
                "artifactId" : "z-invalid",
                "kind" : "compiled-term-pack-m2if",
                "status" : "failed",
                "code" : "invalid-compiled-term-pack-m2if",
                "message" : "Compiled term pack is smaller than the header"
              } ],
              "schema" : "mojito-mf2-inflection/release-validation-report/v0",
              "summary" : {
                "artifacts" : 2,
                "failed" : 1,
                "passed" : 1
              }
            }
            """);
  }

  @Test
  public void writesDeterministicManifestPathFailureJsonReport() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();
    writeString(
        baseDirectory.resolve("valid/terms.json"),
        compiledFixture("es_compiled_article_pack_fixture.json"));

    ReleaseValidationReport report =
        validator.validateManifest(
            """
            {
              "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
              "artifacts" : [ {
                "artifactId" : "z-valid-json",
                "kind" : "compiled-term-pack-json",
                "path" : "valid/terms.json"
              }, {
                "artifactId" : "a-escaped-json",
                "kind" : "compiled-term-pack-json",
                "path" : "../outside.json"
              } ]
            }
            """,
            baseDirectory);

    assertThat(validator.writeJson(report))
        .isEqualTo(
            """
            {
              "artifacts" : [ {
                "artifactId" : "a-escaped-json",
                "kind" : "compiled-term-pack-json",
                "status" : "failed",
                "code" : "invalid-release-artifact-path",
                "message" : "Release artifact path must stay under baseDirectory: ../outside.json"
              }, {
                "artifactId" : "z-valid-json",
                "kind" : "compiled-term-pack-json",
                "status" : "passed"
              } ],
              "schema" : "mojito-mf2-inflection/release-validation-report/v0",
              "summary" : {
                "artifacts" : 2,
                "failed" : 1,
                "passed" : 1
              }
            }
            """);
  }

  @Test
  public void releaseArtifactDefensivelyCopiesBinaryPayload() {
    byte[] payload = m2ifFixture("es_compiled_article_pack_fixture.m2if.hex");
    ReleaseArtifact artifact = ReleaseArtifact.compiledTermPackM2if("es-terms", payload);
    payload[0] = 'X';

    assertThat(validator.validate(List.of(artifact)).passed()).isTrue();

    byte[] returnedBytes = artifact.bytes();
    returnedBytes[0] = 'X';

    assertThat(validator.validate(List.of(artifact)).passed()).isTrue();
  }

  @Test
  public void rejectsMismatchedReleaseArtifactPayloadsAtConstruction() {
    assertThatThrownBy(
            () ->
                new ReleaseArtifact(
                    "terms", ArtifactKind.COMPILED_TERM_PACK_JSON, "{}", new byte[] {'x'}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON release artifacts must not carry bytes");
    assertThatThrownBy(
            () -> new ReleaseArtifact("terms", ArtifactKind.COMPILED_TERM_PACK_M2IF, "{}", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("bytes");
  }

  @Test
  public void rejectsDuplicateReleaseArtifactIds() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    List.of(
                        ReleaseArtifact.compiledTermPackJson(
                            "terms", compiledFixture("es_compiled_article_pack_fixture.json")),
                        ReleaseArtifact.compiledTermPackM2if(
                            "terms", m2ifFixture("es_compiled_article_pack_fixture.m2if.hex")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Duplicate release artifact IDs: [terms]");
  }

  @Test
  public void rejectsDuplicateReleaseArtifactIdsBeforePayloadValidation() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    List.of(
                        ReleaseArtifact.compiledTermPackJson("terms", "{}"),
                        ReleaseArtifact.compiledTermPackJson("terms", "{}"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Duplicate release artifact IDs: [terms]");
  }

  @Test
  public void rejectsInvalidReleaseReportSummaryInvariants() {
    ArtifactResult passed = ArtifactResult.passed("terms", ArtifactKind.COMPILED_TERM_PACK_JSON);

    assertThatThrownBy(() -> new Summary(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Release summary counts must be non-negative");
    assertThatThrownBy(() -> new Summary(2, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Release summary counts do not add up");
    assertThatThrownBy(() -> new ReleaseValidationReport(List.of(passed), new Summary(2, 1, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Release summary artifact count does not match results");
    assertThatThrownBy(() -> new ReleaseValidationReport(List.of(passed), new Summary(1, 0, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Release summary pass/fail counts do not match results");
  }

  @Test
  public void rejectsNullReleaseReportArtifactRows() {
    assertThatThrownBy(() -> new ReleaseValidationReport(Collections.singletonList(null)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("artifacts[0]");
  }

  @Test
  public void rejectsInvalidArtifactResultErrorFields() {
    assertThatThrownBy(
            () ->
                new ArtifactResult(
                    "terms",
                    ArtifactKind.COMPILED_TERM_PACK_JSON,
                    ArtifactStatus.PASSED,
                    "code",
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Passed release artifacts must not carry errors");
    assertThatThrownBy(
            () ->
                new ArtifactResult(
                    "terms",
                    ArtifactKind.COMPILED_TERM_PACK_JSON,
                    ArtifactStatus.FAILED,
                    "",
                    "message"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("code is required");
    assertThatThrownBy(
            () ->
                new ArtifactResult(
                    "terms",
                    ArtifactKind.COMPILED_TERM_PACK_JSON,
                    ArtifactStatus.FAILED,
                    "code",
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("message is required");
  }

  @Test
  public void validatesReleaseArtifactsFromManifestPaths() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();
    writeString(
        baseDirectory.resolve("es/terms.json"),
        compiledFixture("es_compiled_article_pack_fixture.json"));
    writeBytes(
        baseDirectory.resolve("es/terms.m2if"),
        m2ifFixture("es_compiled_article_pack_fixture.m2if.hex"));
    writeString(
        baseDirectory.resolve("hi/pronouns.json"),
        compiledFixture("hi_pronoun_agreement_pack_fixture.json"));

    ReleaseValidationReport report =
        validator.validateManifest(
            """
            {
              "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
              "artifacts" : [ {
                "artifactId" : "es-terms-json",
                "kind" : "compiled-term-pack-json",
                "path" : "es/terms.json"
              }, {
                "artifactId" : "es-terms-m2if",
                "kind" : "compiled-term-pack-m2if",
                "path" : "es/terms.m2if"
              }, {
                "artifactId" : "hi-pronouns",
                "kind" : "hindi-pronoun-agreement-pack-json",
                "path" : "hi/pronouns.json"
              } ]
            }
            """,
            baseDirectory);

    assertThat(report.passed()).isTrue();
    assertThat(report.artifacts())
        .extracting(ArtifactResult::artifactId)
        .containsExactly("es-terms-json", "es-terms-m2if", "hi-pronouns");
  }

  @Test
  public void rejectsDuplicateManifestArtifactIds() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();
    writeString(
        baseDirectory.resolve("terms.json"),
        compiledFixture("es_compiled_article_pack_fixture.json"));
    writeBytes(
        baseDirectory.resolve("terms.m2if"),
        m2ifFixture("es_compiled_article_pack_fixture.m2if.hex"));

    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ {
                        "artifactId" : "terms",
                        "kind" : "compiled-term-pack-json",
                        "path" : "terms.json"
                      }, {
                        "artifactId" : "terms",
                        "kind" : "compiled-term-pack-m2if",
                        "path" : "terms.m2if"
                      } ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Duplicate release artifact IDs: [terms]");
  }

  @Test
  public void rejectsDuplicateManifestArtifactIdsBeforeReadingFiles() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();

    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ {
                        "artifactId" : "terms",
                        "kind" : "compiled-term-pack-json",
                        "path" : "missing.json"
                      }, {
                        "artifactId" : "terms",
                        "kind" : "compiled-term-pack-m2if",
                        "path" : "also-missing.m2if"
                      } ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Duplicate release artifact IDs: [terms]");
  }

  @Test
  public void rejectsInvalidReleaseValidationManifestShape() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();

    assertThatThrownBy(() -> validator.validateManifest("[]", baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object root: release validation manifest");
    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : {}
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected array field: artifacts");
    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ [] ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected object value in release manifest artifacts");
  }

  @Test
  public void rejectsBlankManifestArtifactFields() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();

    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ {
                        "artifactId" : "",
                        "kind" : "compiled-term-pack-json",
                        "path" : "terms.json"
                      } ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected nonblank text field: artifactId");
    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ {
                        "artifactId" : "terms",
                        "kind" : "",
                        "path" : "terms.json"
                      } ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected nonblank text field: kind");
    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ {
                        "artifactId" : "terms",
                        "kind" : "compiled-term-pack-json",
                        "path" : ""
                      } ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Expected nonblank text field: path");
  }

  @Test
  public void reportsManifestFileFailuresWithoutThrowing() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();

    ReleaseValidationReport report =
        validator.validateManifest(
            """
            {
              "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
              "artifacts" : [ {
                "artifactId" : "missing-json",
                "kind" : "compiled-term-pack-json",
                "path" : "missing.json"
              }, {
                "artifactId" : "escaped-json",
                "kind" : "compiled-term-pack-json",
                "path" : "../outside.json"
              } ]
            }
            """,
            baseDirectory);

    assertThat(report.passed()).isFalse();
    assertThat(report.summary().failed()).isEqualTo(2);
    assertThat(report.artifacts())
        .extracting(ArtifactResult::artifactId, ArtifactResult::code)
        .containsExactly(
            tuple("missing-json", "unreadable-release-artifact"),
            tuple("escaped-json", "invalid-release-artifact-path"));
    assertThat(report.artifacts().getFirst().message()).contains("missing.json");
    assertThat(report.artifacts().getLast().message())
        .isEqualTo("Release artifact path must stay under baseDirectory: ../outside.json");
  }

  @Test
  public void reportsManifestSymlinkEscapesWithoutReadingOutsideBundle() throws Exception {
    Path rootDirectory = temporaryFolder.newFolder("release-symlink").toPath();
    Path baseDirectory = rootDirectory.resolve("bundle");
    Path outsideDirectory = rootDirectory.resolve("outside");
    Files.createDirectories(baseDirectory);
    Files.createDirectories(outsideDirectory);
    writeString(
        outsideDirectory.resolve("terms.json"),
        compiledFixture("es_compiled_article_pack_fixture.json"));
    Files.createSymbolicLink(baseDirectory.resolve("artifacts"), outsideDirectory);

    ReleaseValidationReport report =
        validator.validateManifest(
            """
            {
              "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
              "artifacts" : [ {
                "artifactId" : "escaped-json",
                "kind" : "compiled-term-pack-json",
                "path" : "artifacts/terms.json"
              } ]
            }
            """,
            baseDirectory);

    assertThat(report.passed()).isFalse();
    assertThat(report.artifacts().getFirst())
        .extracting(ArtifactResult::artifactId, ArtifactResult::code)
        .containsExactly("escaped-json", "invalid-release-artifact-path");
    assertThat(report.artifacts().getFirst().message())
        .contains("Release artifact path must stay under baseDirectory");
  }

  @Test
  public void rejectsInvalidReleaseValidationManifest() throws Exception {
    Path baseDirectory = temporaryFolder.newFolder("release").toPath();

    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "wrong",
                      "artifacts" : []
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported release validation manifest schema");
    assertThatThrownBy(
            () ->
                validator.validateManifest(
                    """
                    {
                      "schema" : "mojito-mf2-inflection/release-validation-manifest/v0",
                      "artifacts" : [ {
                        "artifactId" : "terms",
                        "kind" : "unknown",
                        "path" : "terms.json"
                      } ]
                    }
                    """,
                    baseDirectory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported release artifact kind");
  }

  private String compiledFixture(String fileName) {
    return readResource("com/box/l10n/mojito/mf2/inflection/" + fileName);
  }

  private byte[] m2ifFixture(String fileName) {
    return HexFormat.of().parseHex(compiledFixture(fileName).replaceAll("\\s+", ""));
  }

  private byte[] replaceFirstAscii(byte[] payload, String oldValue, String newValue) {
    byte[] oldBytes = oldValue.getBytes(StandardCharsets.US_ASCII);
    byte[] newBytes = newValue.getBytes(StandardCharsets.US_ASCII);
    assertThat(newBytes).hasSize(oldBytes.length);
    byte[] result = payload.clone();
    for (int i = 0; i <= result.length - oldBytes.length; i++) {
      boolean match = true;
      for (int j = 0; j < oldBytes.length; j++) {
        if (result[i + j] != oldBytes[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        System.arraycopy(newBytes, 0, result, i, newBytes.length);
        return result;
      }
    }
    throw new AssertionError("Missing ASCII payload fragment: " + oldValue);
  }

  private String readResource(String path) {
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
      assertThat(inputStream).as("resource %s", path).isNotNull();
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void writeString(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  private void writeBytes(Path path, byte[] content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.write(path, content);
  }
}
