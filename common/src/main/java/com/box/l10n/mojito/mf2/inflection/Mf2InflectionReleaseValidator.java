package com.box.l10n.mojito.mf2.inflection;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validates MF2 inflection runtime artifacts before they are published as a release package.
 *
 * <p>This is a generic Java/common API boundary. It validates payload schemas, source-backed
 * provenance embedded in artifacts, duplicate artifact IDs, manifest shape, real-path containment,
 * and deterministic report records. It intentionally does not know about the test fixture source
 * filenames used by the shared MF2 conformance wrapper; that source-map contract belongs to the
 * executable release gate.
 *
 * <p>Passing this validator means the supplied release artifacts match the checked V0 artifact
 * schemas. It is not a certificate of complete locale coverage, complete grammar coverage, or
 * public non-Java runtime availability.
 *
 * <p>The artifact-level failure codes are exactly:
 *
 * <ul>
 *   <li>{@code invalid-release-artifact-path}
 *   <li>{@code unreadable-release-artifact}
 *   <li>{@code invalid-compiled-term-pack-json}
 *   <li>{@code invalid-compiled-term-pack-m2if}
 *   <li>{@code invalid-hindi-pronoun-agreement-pack-json}
 * </ul>
 *
 * Manifest schema, manifest shape, duplicate artifact ID, and in-memory report invariant failures
 * are rejected before artifact rows are emitted.
 */
public class Mf2InflectionReleaseValidator {

  static final String SCHEMA = "mojito-mf2-inflection/release-validation-report/v0";
  static final String MANIFEST_SCHEMA = "mojito-mf2-inflection/release-validation-manifest/v0";

  private final CompiledTermPackJsonLoader compiledTermPackJsonLoader;
  private final CompiledTermPackBinaryCodec compiledTermPackBinaryCodec;
  private final HindiPronounAgreementPackJsonLoader hindiPronounAgreementPackJsonLoader;
  private final ObjectMapper objectMapper;

  public Mf2InflectionReleaseValidator() {
    this(
        new CompiledTermPackJsonLoader(),
        new CompiledTermPackBinaryCodec(),
        new HindiPronounAgreementPackJsonLoader(),
        ObjectMapper.withIndentedOutput());
  }

  Mf2InflectionReleaseValidator(
      CompiledTermPackJsonLoader compiledTermPackJsonLoader,
      CompiledTermPackBinaryCodec compiledTermPackBinaryCodec,
      HindiPronounAgreementPackJsonLoader hindiPronounAgreementPackJsonLoader,
      ObjectMapper objectMapper) {
    this.compiledTermPackJsonLoader =
        Objects.requireNonNull(compiledTermPackJsonLoader, "compiledTermPackJsonLoader");
    this.compiledTermPackBinaryCodec =
        Objects.requireNonNull(compiledTermPackBinaryCodec, "compiledTermPackBinaryCodec");
    this.hindiPronounAgreementPackJsonLoader =
        Objects.requireNonNull(
            hindiPronounAgreementPackJsonLoader, "hindiPronounAgreementPackJsonLoader");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ReleaseValidationReport validate(List<ReleaseArtifact> artifacts) {
    List<ReleaseArtifact> releaseArtifacts = copyReleaseArtifacts(artifacts);
    rejectDuplicateReleaseArtifactIds(releaseArtifacts);
    List<ArtifactResult> results = new ArrayList<>();
    for (ReleaseArtifact artifact : releaseArtifacts) {
      results.add(validateArtifact(artifact));
    }
    return new ReleaseValidationReport(results);
  }

  public ReleaseValidationReport validateManifest(String manifestJson, Path baseDirectory) {
    Objects.requireNonNull(manifestJson, "manifestJson");
    Objects.requireNonNull(baseDirectory, "baseDirectory");
    JsonNode root =
        InflectionJsonFields.requiredObjectRoot(
            objectMapper.readTreeUnchecked(manifestJson), "release validation manifest");
    String schema = InflectionJsonFields.requiredText(root, "schema");
    if (!MANIFEST_SCHEMA.equals(schema)) {
      throw new IllegalArgumentException(
          "Unsupported release validation manifest schema: " + schema);
    }

    Path basePath = realBaseDirectory(baseDirectory);
    List<ReleaseManifestArtifact> manifestArtifacts = new ArrayList<>();
    for (JsonNode artifactNode : InflectionJsonFields.requiredArray(root, "artifacts")) {
      manifestArtifacts.add(loadManifestArtifact(artifactNode));
    }
    rejectDuplicateManifestArtifactIds(manifestArtifacts);

    List<ArtifactResult> results = new ArrayList<>();
    for (ReleaseManifestArtifact manifestArtifact : manifestArtifacts) {
      results.add(validateManifestArtifact(manifestArtifact, basePath));
    }
    return new ReleaseValidationReport(results);
  }

  public String writeJson(ReleaseValidationReport report) {
    Objects.requireNonNull(report, "report");
    return objectMapper.writeValueAsStringUnchecked(toJsonValue(report)) + "\n";
  }

  private ReleaseManifestArtifact loadManifestArtifact(JsonNode artifactNode) {
    if (artifactNode == null || !artifactNode.isObject()) {
      throw new IllegalArgumentException("Expected object value in release manifest artifacts");
    }
    return new ReleaseManifestArtifact(
        InflectionJsonFields.requiredText(artifactNode, "artifactId"),
        artifactKind(InflectionJsonFields.requiredText(artifactNode, "kind")),
        InflectionJsonFields.requiredText(artifactNode, "path"));
  }

  private ArtifactKind artifactKind(String code) {
    for (ArtifactKind kind : ArtifactKind.values()) {
      if (kindCode(kind).equals(code)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("Unsupported release artifact kind: " + code);
  }

  private ArtifactResult validateManifestArtifact(
      ReleaseManifestArtifact manifestArtifact, Path basePath) {
    try {
      Path path = resolveManifestPath(basePath, manifestArtifact.path());
      return validateArtifact(readReleaseArtifact(manifestArtifact, path));
    } catch (InvalidReleaseArtifactPathException e) {
      return ArtifactResult.failed(
          manifestArtifact.artifactId(),
          manifestArtifact.kind(),
          "invalid-release-artifact-path",
          errorMessage(e));
    } catch (IOException e) {
      return ArtifactResult.failed(
          manifestArtifact.artifactId(),
          manifestArtifact.kind(),
          "unreadable-release-artifact",
          errorMessage(e));
    } catch (SecurityException e) {
      return ArtifactResult.failed(
          manifestArtifact.artifactId(),
          manifestArtifact.kind(),
          "unreadable-release-artifact",
          errorMessage(e));
    } catch (RuntimeException e) {
      return ArtifactResult.failed(
          manifestArtifact.artifactId(),
          manifestArtifact.kind(),
          errorCode(manifestArtifact.kind()),
          errorMessage(e));
    }
  }

  private Path realBaseDirectory(Path baseDirectory) {
    try {
      return baseDirectory.toRealPath();
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Release validation baseDirectory must be readable: " + baseDirectory, e);
    }
  }

  private Path resolveManifestPath(Path basePath, String manifestPath) throws IOException {
    Path path = Path.of(manifestPath);
    if (path.isAbsolute()) {
      throw new InvalidReleaseArtifactPathException(
          "Release artifact path must be relative: " + manifestPath);
    }
    Path resolvedPath = basePath.resolve(path).normalize();
    if (!resolvedPath.startsWith(basePath)) {
      throw new InvalidReleaseArtifactPathException(
          "Release artifact path must stay under baseDirectory: " + manifestPath);
    }
    Path realPath = resolvedPath.toRealPath();
    if (!realPath.startsWith(basePath)) {
      throw new InvalidReleaseArtifactPathException(
          "Release artifact path must stay under baseDirectory: " + manifestPath);
    }
    return realPath;
  }

  private ReleaseArtifact readReleaseArtifact(ReleaseManifestArtifact manifestArtifact, Path path)
      throws IOException {
    return switch (manifestArtifact.kind()) {
      case COMPILED_TERM_PACK_JSON ->
          ReleaseArtifact.compiledTermPackJson(
              manifestArtifact.artifactId(), Files.readString(path));
      case COMPILED_TERM_PACK_M2IF ->
          ReleaseArtifact.compiledTermPackM2if(
              manifestArtifact.artifactId(), Files.readAllBytes(path));
      case HINDI_PRONOUN_AGREEMENT_PACK_JSON ->
          ReleaseArtifact.hindiPronounAgreementPackJson(
              manifestArtifact.artifactId(), Files.readString(path));
    };
  }

  private ArtifactResult validateArtifact(ReleaseArtifact artifact) {
    Objects.requireNonNull(artifact, "artifact");
    try {
      switch (artifact.kind()) {
        case COMPILED_TERM_PACK_JSON -> compiledTermPackJsonLoader.load(artifact.json());
        case COMPILED_TERM_PACK_M2IF ->
            compiledTermPackBinaryCodec.decode(
                ByteBuffer.wrap(artifact.bytes()).asReadOnlyBuffer());
        case HINDI_PRONOUN_AGREEMENT_PACK_JSON ->
            hindiPronounAgreementPackJsonLoader.load(artifact.json());
      }
      return ArtifactResult.passed(artifact.artifactId(), artifact.kind());
    } catch (RuntimeException e) {
      return ArtifactResult.failed(
          artifact.artifactId(), artifact.kind(), errorCode(artifact.kind()), errorMessage(e));
    }
  }

  private String errorCode(ArtifactKind kind) {
    return switch (kind) {
      case COMPILED_TERM_PACK_JSON -> "invalid-compiled-term-pack-json";
      case COMPILED_TERM_PACK_M2IF -> "invalid-compiled-term-pack-m2if";
      case HINDI_PRONOUN_AGREEMENT_PACK_JSON -> "invalid-hindi-pronoun-agreement-pack-json";
    };
  }

  private String errorMessage(RuntimeException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }

  private String errorMessage(IOException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }

  private Map<String, Object> toJsonValue(ReleaseValidationReport report) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("artifacts", artifactsJson(report.artifacts()));
    payload.put("schema", SCHEMA);
    payload.put("summary", summaryJson(report.summary()));
    return payload;
  }

  private List<Object> artifactsJson(List<ArtifactResult> artifacts) {
    List<Object> payload = new ArrayList<>();
    for (ArtifactResult artifact : sortedArtifacts(artifacts)) {
      Map<String, Object> artifactPayload = new LinkedHashMap<>();
      artifactPayload.put("artifactId", artifact.artifactId());
      artifactPayload.put("kind", kindCode(artifact.kind()));
      artifactPayload.put("status", artifact.status().name().toLowerCase(Locale.ROOT));
      if (artifact.code() != null) {
        artifactPayload.put("code", artifact.code());
      }
      if (artifact.message() != null) {
        artifactPayload.put("message", artifact.message());
      }
      payload.add(artifactPayload);
    }
    return payload;
  }

  private List<ArtifactResult> sortedArtifacts(List<ArtifactResult> artifacts) {
    return artifacts.stream()
        .sorted(
            Comparator.comparing(ArtifactResult::artifactId)
                .thenComparing(result -> kindCode(result.kind())))
        .toList();
  }

  private String kindCode(ArtifactKind kind) {
    return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private Map<String, Object> summaryJson(Summary summary) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("artifacts", summary.artifacts());
    payload.put("failed", summary.failed());
    payload.put("passed", summary.passed());
    return payload;
  }

  private static void rejectDuplicateReleaseArtifactIds(List<ReleaseArtifact> artifacts) {
    List<String> duplicateIds =
        duplicateArtifactIds(artifacts.stream().map(ReleaseArtifact::artifactId).toList());
    if (!duplicateIds.isEmpty()) {
      throw new IllegalArgumentException("Duplicate release artifact IDs: " + duplicateIds);
    }
  }

  private static void rejectDuplicateManifestArtifactIds(List<ReleaseManifestArtifact> artifacts) {
    List<String> duplicateIds =
        duplicateArtifactIds(artifacts.stream().map(ReleaseManifestArtifact::artifactId).toList());
    if (!duplicateIds.isEmpty()) {
      throw new IllegalArgumentException("Duplicate release artifact IDs: " + duplicateIds);
    }
  }

  private static List<String> duplicateArtifactIds(List<String> artifactIds) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String artifactId : artifactIds) {
      counts.merge(artifactId, 1, Integer::sum);
    }
    return counts.entrySet().stream()
        .filter(entry -> entry.getValue() > 1)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  private static List<ReleaseArtifact> copyReleaseArtifacts(List<ReleaseArtifact> artifacts) {
    Objects.requireNonNull(artifacts, "artifacts");
    List<ReleaseArtifact> copy = new ArrayList<>(artifacts.size());
    for (int i = 0; i < artifacts.size(); i++) {
      copy.add(Objects.requireNonNull(artifacts.get(i), "artifacts[" + i + "]"));
    }
    return List.copyOf(copy);
  }

  private static List<ArtifactResult> copyArtifacts(List<ArtifactResult> artifacts) {
    Objects.requireNonNull(artifacts, "artifacts");
    List<ArtifactResult> copy = new ArrayList<>(artifacts.size());
    for (int i = 0; i < artifacts.size(); i++) {
      copy.add(Objects.requireNonNull(artifacts.get(i), "artifacts[" + i + "]"));
    }
    return List.copyOf(copy);
  }

  private static Summary summaryFor(List<ArtifactResult> artifacts) {
    List<ArtifactResult> copy = copyArtifacts(artifacts);
    return new Summary(
        copy.size(),
        countArtifacts(copy, ArtifactStatus.PASSED),
        countArtifacts(copy, ArtifactStatus.FAILED));
  }

  private static int countArtifacts(List<ArtifactResult> artifacts, ArtifactStatus status) {
    return (int) artifacts.stream().filter(artifact -> artifact.status() == status).count();
  }

  public enum ArtifactKind {
    COMPILED_TERM_PACK_JSON,
    COMPILED_TERM_PACK_M2IF,
    HINDI_PRONOUN_AGREEMENT_PACK_JSON
  }

  public enum ArtifactStatus {
    PASSED,
    FAILED
  }

  private record ReleaseManifestArtifact(String artifactId, ArtifactKind kind, String path) {}

  private static class InvalidReleaseArtifactPathException extends RuntimeException {

    InvalidReleaseArtifactPathException(String message) {
      super(message);
    }
  }

  public record ReleaseArtifact(String artifactId, ArtifactKind kind, String json, byte[] bytes) {

    public ReleaseArtifact {
      artifactId = InflectionJsonFields.requireText(artifactId, "artifactId");
      kind = Objects.requireNonNull(kind, "kind");
      if (kind == ArtifactKind.COMPILED_TERM_PACK_M2IF) {
        if (bytes == null) {
          throw new NullPointerException("bytes");
        }
        if (json != null) {
          throw new IllegalArgumentException("Binary release artifacts must not carry JSON");
        }
        bytes = bytes.clone();
      } else {
        json = InflectionJsonFields.requireText(json, "json");
        if (bytes != null) {
          throw new IllegalArgumentException("JSON release artifacts must not carry bytes");
        }
      }
    }

    public static ReleaseArtifact compiledTermPackJson(String artifactId, String json) {
      return new ReleaseArtifact(artifactId, ArtifactKind.COMPILED_TERM_PACK_JSON, json, null);
    }

    public static ReleaseArtifact compiledTermPackM2if(String artifactId, byte[] bytes) {
      return new ReleaseArtifact(artifactId, ArtifactKind.COMPILED_TERM_PACK_M2IF, null, bytes);
    }

    public static ReleaseArtifact hindiPronounAgreementPackJson(String artifactId, String json) {
      return new ReleaseArtifact(
          artifactId, ArtifactKind.HINDI_PRONOUN_AGREEMENT_PACK_JSON, json, null);
    }

    public byte[] bytes() {
      return bytes == null ? null : bytes.clone();
    }
  }

  public record ReleaseValidationReport(List<ArtifactResult> artifacts, Summary summary) {

    public ReleaseValidationReport(List<ArtifactResult> artifacts) {
      this(artifacts, summaryFor(artifacts));
    }

    public ReleaseValidationReport {
      artifacts = copyArtifacts(artifacts);
      summary = Objects.requireNonNull(summary, "summary");
      if (summary.artifacts() != artifacts.size()) {
        throw new IllegalArgumentException("Release summary artifact count does not match results");
      }
      if (summary.passed() != countArtifacts(artifacts, ArtifactStatus.PASSED)
          || summary.failed() != countArtifacts(artifacts, ArtifactStatus.FAILED)) {
        throw new IllegalArgumentException("Release summary pass/fail counts do not match results");
      }
      List<String> duplicates =
          duplicateArtifactIds(artifacts.stream().map(ArtifactResult::artifactId).toList());
      if (!duplicates.isEmpty()) {
        throw new IllegalArgumentException("Duplicate release artifact IDs: " + duplicates);
      }
    }

    public boolean passed() {
      return summary.failed() == 0;
    }
  }

  public record ArtifactResult(
      String artifactId, ArtifactKind kind, ArtifactStatus status, String code, String message) {

    public ArtifactResult {
      artifactId = InflectionJsonFields.requireText(artifactId, "artifactId");
      kind = Objects.requireNonNull(kind, "kind");
      status = Objects.requireNonNull(status, "status");
      if (status == ArtifactStatus.FAILED) {
        code = InflectionJsonFields.requireText(code, "code");
        message = InflectionJsonFields.requireText(message, "message");
      } else if (code != null || message != null) {
        throw new IllegalArgumentException("Passed release artifacts must not carry errors");
      }
    }

    static ArtifactResult passed(String artifactId, ArtifactKind kind) {
      return new ArtifactResult(artifactId, kind, ArtifactStatus.PASSED, null, null);
    }

    static ArtifactResult failed(
        String artifactId, ArtifactKind kind, String code, String message) {
      return new ArtifactResult(artifactId, kind, ArtifactStatus.FAILED, code, message);
    }
  }

  public record Summary(int artifacts, int passed, int failed) {

    public Summary {
      if (artifacts < 0 || passed < 0 || failed < 0) {
        throw new IllegalArgumentException("Release summary counts must be non-negative");
      }
      if (artifacts != passed + failed) {
        throw new IllegalArgumentException("Release summary counts do not add up");
      }
    }
  }
}
