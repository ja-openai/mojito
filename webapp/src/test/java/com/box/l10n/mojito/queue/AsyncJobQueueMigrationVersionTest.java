package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class AsyncJobQueueMigrationVersionTest {

  @Test
  public void queueMigrationHasOneSharedVersionWithoutCollidingWithApplicationMigrations()
      throws Exception {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    List<String> queueMigrations = new ArrayList<>();
    for (String location : List.of("db/migration", "db/postgresql/migration")) {
      List<MigrationVersion> versions = new ArrayList<>();
      List<String> queueFiles = new ArrayList<>();
      for (Resource resource : resolver.getResources("classpath*:" + location + "/V*__*.sql")) {
        String name = resource.getFilename();
        versions.add(MigrationVersion.fromVersion(name.substring(1, name.indexOf("__"))));
        if (name.endsWith("__Async_Job_Queue.sql")) {
          queueFiles.add(name);
        }
      }
      assertThat(versions).as("Flyway versions in %s", location).doesNotHaveDuplicates();
      assertThat(queueFiles).as("single queue migration in %s", location).hasSize(1);
      queueMigrations.add(queueFiles.get(0));
    }
    assertThat(queueMigrations)
        .containsExactly("V109__Async_Job_Queue.sql", "V109__Async_Job_Queue.sql");
  }
}
