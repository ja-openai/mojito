package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public class AsyncJobQueueRealDatabaseCiContractTest {

  @Test
  public void realDatabaseJobSelectsAdapterAndCoreContractsWithoutAutomaticReruns()
      throws Exception {
    List<String> commands =
        databaseCommands().stream().filter(command -> command.contains("-Dtest=")).toList();

    assertThat(commands).as("the opted-in application database test invocation").hasSize(1);
    List<String> arguments = Arrays.asList(commands.getFirst().strip().split("\\s+"));
    assertThat(arguments).contains("-Pno-local-config", "test");
    assertDatabaseOptInWithoutReruns(arguments);
    List<String> selectors =
        arguments.stream().filter(argument -> argument.startsWith("-Dtest=")).toList();
    assertThat(selectors).hasSize(1);
    assertThat(selectors.getFirst().substring("-Dtest=".length()).split(","))
        .doesNotHaveDuplicates()
        .contains(
            JdbcAsyncJobStoreDatabaseIntegrationTest.class.getSimpleName(),
            JdbcAsyncJobStorePoolIntegrationTest.class.getSimpleName(),
            JdbcAsyncJobStoreDatabaseRestartIntegrationTest.class.getSimpleName(),
            JdbcAsyncJobStoreTimezoneIntegrationTest.class.getSimpleName(),
            AsyncJobQueueJpaTransactionIntegrationTest.class.getSimpleName(),
            JdbcPostgresAsyncJobQueueWakeupListenerDatabaseIntegrationTest.class.getSimpleName(),
            AsyncJobQueueProcessCrashIntegrationTest.class.getSimpleName(),
            AssetLocalizeAsyncJobOutputRetryIntegrationTest.class.getSimpleName());
  }

  @Test
  public void independentJpaConsumerOptsIntoRealDatabasesWithoutAutomaticReruns() throws Exception {
    List<String> commands =
        databaseCommands().stream().filter(command -> command.contains("-Pjpa")).toList();
    assertThat(commands).as("the independent JPA host invocation").hasSize(1);
    List<String> arguments = Arrays.asList(commands.getFirst().strip().split("\\s+"));
    assertThat(arguments)
        .contains(
            "-f",
            "dev-docs/queue-library-probe/consumer/pom.xml",
            "-Pjpa",
            "clean",
            "test",
            "spotless:check");
    assertDatabaseOptInWithoutReruns(arguments);
  }

  private static List<String> databaseCommands() throws Exception {
    Path base = Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
    Path workflow = base.resolve(".github/workflows/maven-test.yml");
    if (!Files.isRegularFile(workflow)) {
      workflow = base.getParent().resolve(".github/workflows/maven-test.yml");
    }
    Map<?, ?> document;
    try (InputStream input = Files.newInputStream(workflow)) {
      document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
    }
    Map<?, ?> jobs = (Map<?, ?>) document.get("jobs");
    Map<?, ?> job = (Map<?, ?>) jobs.get("async-job-queue-real-db-contract");
    List<?> steps = (List<?>) job.get("steps");
    return steps.stream()
        .map(Map.class::cast)
        .map(step -> step.get("run"))
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .toList();
  }

  private static void assertDatabaseOptInWithoutReruns(List<String> arguments) {
    assertThat(arguments)
        .filteredOn(argument -> argument.startsWith("-Dmojito.asyncJobQueue.testcontainers="))
        .containsExactly("-Dmojito.asyncJobQueue.testcontainers=true");
    assertThat(arguments)
        .filteredOn(argument -> argument.startsWith("-Dsurefire.rerunFailingTestsCount="))
        .containsExactly("-Dsurefire.rerunFailingTestsCount=0");
  }
}
