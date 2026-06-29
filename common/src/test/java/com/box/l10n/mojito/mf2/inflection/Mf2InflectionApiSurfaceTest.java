package com.box.l10n.mojito.mf2.inflection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
