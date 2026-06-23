package com.box.l10n.mojito;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class FlywayMigrationVersionTest {

  private static final Pattern VERSIONED_MIGRATION_FILENAME =
      Pattern.compile("^V(?<version>[0-9]+)__[^$]+\\.(sql|class)$");
  private static final String VERSIONED_SQL_MIGRATIONS = "classpath*:db/migration/V*__*.sql";
  private static final String VERSIONED_JAVA_MIGRATIONS = "classpath*:db/migration/V*__*.class";

  @Test
  public void versionedMigrationsHaveUniqueVersions() throws IOException {
    Map<String, List<String>> migrationFilenamesByVersion;
    try (Stream<Resource> versionedMigrationResources = versionedMigrationResources()) {
      migrationFilenamesByVersion =
          versionedMigrationResources
              .map(this::migrationFilename)
              .collect(
                  groupingBy(this::migrationVersion, TreeMap::new, mapping(identity(), toList())));
    }
    Map<String, List<String>> duplicateMigrationFilenamesByVersion =
        migrationFilenamesByVersion.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .collect(
                toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (left, right) -> left,
                    LinkedHashMap::new));

    assertThat(duplicateMigrationFilenamesByVersion)
        .as("Flyway migration versions must be unique across SQL and Java migrations")
        .isEmpty();
  }

  private Stream<Resource> versionedMigrationResources() throws IOException {
    PathMatchingResourcePatternResolver resourceResolver =
        new PathMatchingResourcePatternResolver();
    return Stream.concat(
            Arrays.stream(resourceResolver.getResources(VERSIONED_SQL_MIGRATIONS)),
            Arrays.stream(resourceResolver.getResources(VERSIONED_JAVA_MIGRATIONS)))
        .filter(this::isRuntimeMigration);
  }

  private boolean isRuntimeMigration(Resource migrationResource) {
    return !migrationResourceDescription(migrationResource).contains("/test-classes/")
        && VERSIONED_MIGRATION_FILENAME.matcher(migrationFilename(migrationResource)).matches();
  }

  private String migrationResourceDescription(Resource migrationResource) {
    return migrationResource.getDescription().replace('\\', '/');
  }

  private String migrationFilename(Resource migrationResource) {
    String filename = migrationResource.getFilename();
    assertThat(filename).as("versioned Flyway migration filename").isNotBlank();
    return filename;
  }

  private String migrationVersion(String migrationFilename) {
    Matcher matcher = VERSIONED_MIGRATION_FILENAME.matcher(migrationFilename);
    assertThat(matcher.matches())
        .as("versioned Flyway migration filename %s", migrationFilename)
        .isTrue();
    return matcher.group("version");
  }
}
