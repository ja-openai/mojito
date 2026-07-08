package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;

public class Mf2InflectionApiSurfaceTest {

  private static final String PACKAGE_NAME = Mf2InflectionApiSurfaceTest.class.getPackageName();
  private static final Set<String> DOCUMENTED_PUBLIC_API_CLASSES =
      Set.of(
          "CompiledTermPack",
          "CompiledTermPackJsonLoader",
          "CompiledTermPackBinaryCodec",
          "Mf2TermRenderer",
          "SourceSpan",
          "TermUsageExtractor",
          "TermRequirementJsonLoader",
          "TermRequirementValidator",
          "TermBindingManifestValidator",
          "TermRequirementReportJsonWriter",
          "TermBindingManifestReportJsonWriter",
          "TermInflectionProfilePackJsonLoader",
          "TermInflectionDiagnostics",
          "HindiPronounAgreementPackJsonLoader",
          "Mf2InflectionReleaseValidator");
  private static final Set<String> GENERATOR_SUPPORT_CLASSES =
      Set.of(
          "ArabicPackAuditJsonLoader",
          "DutchNounMetadataPackJsonLoader",
          "FrenchGenderSuffixRulePackJsonLoader",
          "FrenchNounMetadataAnalyzer",
          "FrenchNounMetadataPackJsonLoader",
          "GermanArticleCaseReportJsonLoader",
          "GermanicNordicPackAuditJsonLoader",
          "HebrewPackAuditJsonLoader",
          "HindiPackSurveyJsonLoader",
          "ItalianNounPackReportJsonLoader",
          "LowInflectionLocaleAuditJsonLoader",
          "MalayalamPackAuditJsonLoader",
          "NorwegianBokmalNounMetadataPackJsonLoader",
          "PortugueseNounPackReportJsonLoader",
          "PronounProfilePackJsonLoader",
          "RussianCaseFormPackJsonLoader",
          "RussianCasePackAuditJsonLoader",
          "SerbianCaseFormPackJsonLoader",
          "SerbianCasePackReportJsonLoader",
          "SpanishNounPackReportJsonLoader",
          "TurkishSuffixPackSurveyJsonLoader");
  private static final Set<String> RELEASE_VALIDATOR_ARTIFACT_FAILURE_CODES =
      Set.of(
          "invalid-release-artifact-path",
          "unreadable-release-artifact",
          "invalid-compiled-term-pack-json",
          "invalid-compiled-term-pack-m2if",
          "invalid-hindi-pronoun-agreement-pack-json");

  @Test
  public void stableRuntimeAndToolingApiClassesRemainPublic() {
    assertThat(
            List.of(
                CompiledTermPack.class,
                CompiledTermPackJsonLoader.class,
                CompiledTermPackBinaryCodec.class,
                Mf2TermRenderer.class,
                SourceSpan.class,
                TermUsageExtractor.class,
                TermRequirementJsonLoader.class,
                TermRequirementValidator.class,
                TermBindingManifestValidator.class,
                TermRequirementReportJsonWriter.class,
                TermBindingManifestReportJsonWriter.class,
                TermInflectionProfilePackJsonLoader.class,
                TermInflectionDiagnostics.class,
                HindiPronounAgreementPackJsonLoader.class,
                Mf2InflectionReleaseValidator.class))
        .allSatisfy(type -> assertThat(Modifier.isPublic(type.getModifiers())).isTrue());
  }

  @Test
  public void onlyDocumentedTopLevelApiClassesArePublic() throws Exception {
    Set<String> publicClasses = new TreeSet<>();
    for (String className : topLevelMainClassNames()) {
      Class<?> type = Class.forName(PACKAGE_NAME + "." + className);
      if (Modifier.isPublic(type.getModifiers())) {
        publicClasses.add(className);
      }
    }

    assertThat(publicClasses).isEqualTo(new TreeSet<>(DOCUMENTED_PUBLIC_API_CLASSES));
  }

  @Test
  public void publicApiDoesNotExposeCliEntryPoints() throws Exception {
    Set<String> executableClasses = new TreeSet<>();
    for (String className : topLevelMainClassNames()) {
      Class<?> type = Class.forName(PACKAGE_NAME + "." + className);
      if (Modifier.isPublic(type.getModifiers()) && hasPublicMain(type)) {
        executableClasses.add(className);
      }
    }

    assertThat(executableClasses)
        .as("Java/common inflection release validation is API-only, not a CLI wrapper")
        .isEmpty();
  }

  @Test
  public void rendererBoundMessageHandleStaysNarrowPublicApi() {
    Set<String> publicRendererHandles =
        Stream.of(Mf2TermRenderer.class.getClasses())
            .filter(type -> Mf2TermRenderer.class.equals(type.getDeclaringClass()))
            .map(Class::getSimpleName)
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(publicRendererHandles).containsExactly("BoundMessage");
    Class<?> boundMessage = Mf2TermRenderer.BoundMessage.class;
    assertThat(Modifier.isPublic(boundMessage.getModifiers())).isTrue();
    assertThat(Modifier.isStatic(boundMessage.getModifiers())).isTrue();
    assertThat(Modifier.isFinal(boundMessage.getModifiers())).isTrue();
    assertThat(
            Stream.of(boundMessage.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())))
        .as("BoundMessage handles must be created only by Mf2TermRenderer")
        .isTrue();

    Set<String> publicMethods =
        Stream.of(boundMessage.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(method -> method.getName() + ":" + method.getReturnType().getName())
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(publicMethods)
        .containsExactly(
            "message:java.lang.String",
            "messageId:java.lang.String",
            "termArguments:java.util.Map");
  }

  @Test
  public void releaseValidatorNestedApiSurfaceStaysNarrow() {
    Set<String> publicNestedTypes =
        Stream.of(Mf2InflectionReleaseValidator.class.getClasses())
            .filter(type -> Mf2InflectionReleaseValidator.class.equals(type.getDeclaringClass()))
            .map(Class::getSimpleName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(publicNestedTypes)
        .containsExactly(
            "ArtifactKind",
            "ArtifactResult",
            "ArtifactStatus",
            "ReleaseArtifact",
            "ReleaseValidationReport",
            "Summary");

    assertThat(publicMethodSignatures(Mf2InflectionReleaseValidator.class))
        .containsExactly(
            "validate(java.util.List):"
                + Mf2InflectionReleaseValidator.ReleaseValidationReport.class.getName(),
            "validateManifest(java.lang.String,java.nio.file.Path):"
                + Mf2InflectionReleaseValidator.ReleaseValidationReport.class.getName(),
            "writeJson("
                + Mf2InflectionReleaseValidator.ReleaseValidationReport.class.getName()
                + "):java.lang.String");
    assertThat(Stream.of(Mf2InflectionReleaseValidator.ArtifactKind.values()).map(Enum::name))
        .containsExactly(
            "COMPILED_TERM_PACK_JSON",
            "COMPILED_TERM_PACK_M2IF",
            "HINDI_PRONOUN_AGREEMENT_PACK_JSON");
    assertThat(Stream.of(Mf2InflectionReleaseValidator.ArtifactStatus.values()).map(Enum::name))
        .containsExactly("PASSED", "FAILED");

    assertRecordComponents(
        Mf2InflectionReleaseValidator.ReleaseArtifact.class,
        "artifactId:java.lang.String",
        "kind:" + Mf2InflectionReleaseValidator.ArtifactKind.class.getName(),
        "json:java.lang.String",
        "bytes:[B");
    assertRecordComponents(
        Mf2InflectionReleaseValidator.ReleaseValidationReport.class,
        "artifacts:java.util.List",
        "summary:" + Mf2InflectionReleaseValidator.Summary.class.getName());
    assertRecordComponents(
        Mf2InflectionReleaseValidator.ArtifactResult.class,
        "artifactId:java.lang.String",
        "kind:" + Mf2InflectionReleaseValidator.ArtifactKind.class.getName(),
        "status:" + Mf2InflectionReleaseValidator.ArtifactStatus.class.getName(),
        "code:java.lang.String",
        "message:java.lang.String");
    assertRecordComponents(
        Mf2InflectionReleaseValidator.Summary.class, "artifacts:int", "passed:int", "failed:int");
    assertThat(publicStaticMethodSignatures(Mf2InflectionReleaseValidator.ReleaseArtifact.class))
        .containsExactly(
            "compiledTermPackJson(java.lang.String,java.lang.String):"
                + Mf2InflectionReleaseValidator.ReleaseArtifact.class.getName(),
            "compiledTermPackM2if(java.lang.String,[B):"
                + Mf2InflectionReleaseValidator.ReleaseArtifact.class.getName(),
            "hindiPronounAgreementPackJson(java.lang.String,java.lang.String):"
                + Mf2InflectionReleaseValidator.ReleaseArtifact.class.getName());
    assertThat(publicStaticMethodSignatures(Mf2InflectionReleaseValidator.ArtifactResult.class))
        .isEmpty();
    assertThat(publicConstructorSignatures(Mf2InflectionReleaseValidator.ReleaseArtifact.class))
        .containsExactly(
            Mf2InflectionReleaseValidator.ReleaseArtifact.class.getName()
                + "(java.lang.String,"
                + Mf2InflectionReleaseValidator.ArtifactKind.class.getName()
                + ",java.lang.String,[B)");
    assertThat(
            publicConstructorSignatures(
                Mf2InflectionReleaseValidator.ReleaseValidationReport.class))
        .containsExactly(
            Mf2InflectionReleaseValidator.ReleaseValidationReport.class.getName()
                + "(java.util.List)",
            Mf2InflectionReleaseValidator.ReleaseValidationReport.class.getName()
                + "(java.util.List,"
                + Mf2InflectionReleaseValidator.Summary.class.getName()
                + ")");
    assertThat(publicConstructorSignatures(Mf2InflectionReleaseValidator.ArtifactResult.class))
        .containsExactly(
            Mf2InflectionReleaseValidator.ArtifactResult.class.getName()
                + "(java.lang.String,"
                + Mf2InflectionReleaseValidator.ArtifactKind.class.getName()
                + ","
                + Mf2InflectionReleaseValidator.ArtifactStatus.class.getName()
                + ",java.lang.String,java.lang.String)");
    assertThat(publicConstructorSignatures(Mf2InflectionReleaseValidator.Summary.class))
        .containsExactly(Mf2InflectionReleaseValidator.Summary.class.getName() + "(int,int,int)");
  }

  @Test
  public void generatorSupportClassesRemainPackagePrivateAndExplicitlyMarked() throws Exception {
    Set<String> markedClasses = new TreeSet<>();
    for (String className : topLevelMainClassNames()) {
      Class<?> type = Class.forName(PACKAGE_NAME + "." + className);
      if (type.isAnnotationPresent(GeneratorSupport.class)) {
        markedClasses.add(className);
      }
    }

    assertThat(markedClasses).isEqualTo(new TreeSet<>(GENERATOR_SUPPORT_CLASSES));
    for (String className : GENERATOR_SUPPORT_CLASSES) {
      Class<?> type = Class.forName(PACKAGE_NAME + "." + className);
      assertThat(Modifier.isPublic(type.getModifiers()))
          .as(className + " must stay outside the public API")
          .isFalse();
    }
  }

  @Test
  public void profileOnlyPronounPackStaysGeneratorOnly() {
    assertThat(PronounProfilePackJsonLoader.class.isAnnotationPresent(GeneratorSupport.class))
        .isTrue();
    assertThat(Modifier.isPublic(PronounProfilePackJsonLoader.class.getModifiers()))
        .as("profile/no-op pronoun metadata is not a public runtime or authoring API")
        .isFalse();
  }

  @Test
  public void packageDocumentationStaysAlignedWithApiBoundary() throws IOException {
    String packageInfo = Files.readString(mainPackagePath().resolve("package-info.java"));
    String normalizedPackageInfo =
        packageInfo.replaceAll("\\R\\s*\\*\\s?", " ").replaceAll("\\s+", " ");

    assertThat(normalizedPackageInfo)
        .contains(
            "stable runtime/model surface",
            "public authoring/tooling surface is schema-gated",
            "No CLI entry point is published from this package",
            "validates release artifact payloads",
            "duplicate artifact IDs",
            "relative real-path containment",
            "Mf2TermRenderer.BoundMessage",
            "shared MF2 conformance wrapper",
            "fixture-specific source filename pinning",
            "not complete locale or grammar coverage",
            "Runtime rendering is limited to the locale and grammar slices",
            "Metadata/profile-only locales remain validation-only",
            "until a product caller promotes a reviewed runtime path",
            "Locales absent from the current source-data survey",
            "including Polish in the pinned Unicode Inflection checkout",
            "source-data acquisition work rather than Java/common runtime coverage",
            "internal generator support");
    for (String className : DOCUMENTED_PUBLIC_API_CLASSES) {
      assertThat(packageInfo)
          .as("package-info.java must document public API class " + className)
          .contains(PACKAGE_NAME + "." + className);
    }
    for (String className : GENERATOR_SUPPORT_CLASSES) {
      if (!"PronounProfilePackJsonLoader".equals(className)) {
        assertThat(packageInfo)
            .as("package-info.java must not promote generator support class " + className)
            .doesNotContain(PACKAGE_NAME + "." + className);
      }
    }
    assertThat(packageInfo)
        .doesNotContain("prototype", "universal", "all inflection types", "all languages");
    assertMainJavaSourcesDoNotContainBroadCoverageClaims();
  }

  @Test
  public void releaseValidatorDocumentationStaysAlignedWithCoverageBoundary() throws IOException {
    String source =
        Files.readString(mainPackagePath().resolve("Mf2InflectionReleaseValidator.java"));
    String normalizedSource = source.replaceAll("\\R\\s*\\*\\s?", " ").replaceAll("\\s+", " ");

    assertThat(normalizedSource)
        .contains(
            "generic Java/common API boundary",
            "checked V0 artifact schemas",
            "not a certificate of complete locale coverage",
            "complete grammar coverage",
            "public non-Java runtime availability",
            "artifact-level failure codes are exactly",
            "rejected before artifact rows are emitted");
    assertThat(documentedReleaseValidatorArtifactFailureCodes(source))
        .isEqualTo(RELEASE_VALIDATOR_ARTIFACT_FAILURE_CODES);
    assertThat(source).doesNotContain("universal", "all inflection types", "all languages");
    assertMainJavaSourcesDoNotContainBroadCoverageClaims();
  }

  private void assertMainJavaSourcesDoNotContainBroadCoverageClaims() throws IOException {
    for (Path source : mainPackageJavaSources()) {
      String text = Files.readString(source);
      assertThat(text)
          .as(source + " must keep Java/common inflection scope bounded")
          .doesNotContain(
              "production-quality Java path",
              "production-ready Java path",
              "production-ready runtime",
              "The data is complete enough to use",
              "all inflection types",
              "all languages",
              "all Unicode/CLDR languages",
              "all locales",
              "all runtime locales",
              "supports all locales",
              "supports every locale",
              "full locale coverage",
              "full grammar coverage",
              "complete language coverage",
              "complete runtime coverage",
              "universal inflection",
              "Polish support",
              "public non-Java runtime is available");
    }
  }

  private Set<String> documentedReleaseValidatorArtifactFailureCodes(String source) {
    Matcher matcher =
        Pattern.compile("@code ([a-z0-9]+(?:-[a-z0-9]+)+)")
            .matcher(releaseValidatorJavadocs(source));
    Set<String> codes = new LinkedHashSet<>();
    while (matcher.find()) {
      codes.add(matcher.group(1));
    }
    return codes;
  }

  private String releaseValidatorJavadocs(String source) {
    int start = source.indexOf("/**");
    int end = source.indexOf(" */", start);
    assertThat(start).as("release validator Javadocs must exist").isNotNegative();
    assertThat(end).as("release validator Javadocs must close").isGreaterThan(start);
    return source.substring(start, end);
  }

  private Set<String> topLevelMainClassNames() throws IOException {
    return mainPackageJavaSources().stream()
        .map(path -> path.getFileName().toString())
        .filter(fileName -> !"package-info.java".equals(fileName))
        .map(fileName -> fileName.substring(0, fileName.length() - ".java".length()))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private List<Path> mainPackageJavaSources() throws IOException {
    try (Stream<Path> sources = Files.list(mainPackagePath())) {
      return sources
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .sorted()
          .collect(Collectors.toList());
    }
  }

  private Path mainPackagePath() {
    Path moduleRelative = Path.of("src/main/java", PACKAGE_NAME.replace('.', '/'));
    if (Files.isDirectory(moduleRelative)) {
      return moduleRelative;
    }
    return Path.of("common/src/main/java", PACKAGE_NAME.replace('.', '/'));
  }

  private void assertRecordComponents(Class<?> recordType, String... expectedComponents) {
    assertThat(recordType.isRecord()).as(recordType.getName() + " must stay a record").isTrue();
    assertThat(
            Stream.of(recordType.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getName()))
        .containsExactly(expectedComponents);
  }

  private List<String> publicMethodSignatures(Class<?> type) {
    return Stream.of(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .map(this::methodSignature)
        .sorted()
        .toList();
  }

  private List<String> publicStaticMethodSignatures(Class<?> type) {
    return Stream.of(type.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> Modifier.isStatic(method.getModifiers()))
        .map(this::methodSignature)
        .sorted()
        .toList();
  }

  private List<String> publicConstructorSignatures(Class<?> type) {
    return Stream.of(type.getDeclaredConstructors())
        .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
        .map(this::constructorSignature)
        .sorted()
        .toList();
  }

  private String constructorSignature(Constructor<?> constructor) {
    String parameterTypes =
        Stream.of(constructor.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(","));
    return constructor.getDeclaringClass().getName() + "(" + parameterTypes + ")";
  }

  private String methodSignature(Method method) {
    String parameterTypes =
        Stream.of(method.getParameterTypes()).map(Class::getName).collect(Collectors.joining(","));
    return method.getName() + "(" + parameterTypes + "):" + method.getReturnType().getName();
  }

  private boolean hasPublicMain(Class<?> type) {
    for (Method method : type.getDeclaredMethods()) {
      if (method.getName().equals("main")
          && Modifier.isPublic(method.getModifiers())
          && Modifier.isStatic(method.getModifiers())
          && method.getReturnType() == Void.TYPE
          && method.getParameterCount() == 1
          && method.getParameterTypes()[0].equals(String[].class)) {
        return true;
      }
    }
    return false;
  }
}
