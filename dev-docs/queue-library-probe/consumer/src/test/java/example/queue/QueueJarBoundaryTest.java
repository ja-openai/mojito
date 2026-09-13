package example.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.box.l10n.mojito.queue.AsyncJobStore;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.Test;

/** Reject an accidental pass using Mojito's classes, application dependencies or woven output. */
public class QueueJarBoundaryTest {

  @Test
  public void consumesOnlyTheOrdinaryQueueJarWithoutMojitoOrAspectJ() throws Exception {
    ClassLoader loader = AsyncJobStore.class.getClassLoader();
    String resource = "com/box/l10n/mojito/queue/AsyncJobStore.class";
    assertThat(Collections.list(loader.getResources(resource)))
        .hasSize(1)
        .allSatisfy(url -> assertThat(url.getProtocol()).isEqualTo("jar"));
    Path artifact =
        Path.of(AsyncJobStore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    assertThat(artifact.getFileName().toString())
        .isEqualTo("queue-engine-probe-0.0.0-probe-SNAPSHOT.jar");
    Path builtJar = Path.of(System.getProperty("queue.probe.engineJar"));
    assertThat(Files.isRegularFile(builtJar)).as("Build the engine before the consumer").isTrue();
    assertThat(Files.mismatch(builtJar, artifact))
        .as("Resolve this build's JAR, not a stale or concurrently installed probe")
        .isEqualTo(-1);
    for (String forbidden :
        new String[] {
          "com/box/l10n/mojito/Application.class",
          "com/box/l10n/mojito/entity/PollableTask.class",
          "org/aspectj/lang/JoinPoint.class",
          "org/aspectj/weaver/WeaverStateInfo.class",
          "org/quartz/Scheduler.class"
        }) {
      assertThat(loader.getResource(forbidden)).as(forbidden).isNull();
    }
    if (Boolean.getBoolean("queue.probe.jpa")) {
      assertThat(loader.getResource("org/hibernate/Session.class"))
          .as("The optional JPA host supplies Hibernate in test scope")
          .isNotNull();
      assertThat(loader.getResource("example/queue/QueueJpaConsumerTest.class")).isNotNull();
    } else {
      assertThat(loader.getResource("org/hibernate/Session.class")).isNull();
      assertThat(loader.getResource("example/queue/QueueJpaConsumerTest.class")).isNull();
    }
    try (JarFile jar = new JarFile(artifact.toFile())) {
      assertThat(jar.stream().map(entry -> entry.getName()).toList())
          .noneMatch(name -> name.startsWith("db/") || name.startsWith("BOOT-INF/"))
          .filteredOn(name -> name.endsWith(".class"))
          .allMatch(name -> name.startsWith("com/box/l10n/mojito/queue/"));
    }
    assertQueueClassesComeFromArtifact(loader, artifact);
  }

  @Test
  public void rejectsDuplicateCoordinatorEvenWhenStoreComesOnlyFromEngineJar() throws Exception {
    ClassLoader loader = AsyncJobStore.class.getClassLoader();
    Path artifact =
        Path.of(AsyncJobStore.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    String shadowed = "com/box/l10n/mojito/queue/AsyncJobQueueCoordinator.class";
    Path duplicateJar = Files.createTempFile("queue-shadowed-coordinator-", ".jar");
    try {
      try (JarFile source = new JarFile(artifact.toFile());
          var original = source.getInputStream(source.getJarEntry(shadowed));
          JarOutputStream duplicate = new JarOutputStream(Files.newOutputStream(duplicateJar))) {
        duplicate.putNextEntry(new JarEntry(shadowed));
        original.transferTo(duplicate);
        duplicate.closeEntry();
      }
      try (URLClassLoader withShadow =
          new URLClassLoader(new URL[] {duplicateJar.toUri().toURL()}, loader)) {
        assertThat(
                Collections.list(
                    withShadow.getResources("com/box/l10n/mojito/queue/AsyncJobStore.class")))
            .hasSize(1);
        assertThatThrownBy(() -> assertQueueClassesComeFromArtifact(withShadow, artifact))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining(shadowed);
      }
    } finally {
      Files.deleteIfExists(duplicateJar);
    }
  }

  private void assertQueueClassesComeFromArtifact(ClassLoader loader, Path artifact)
      throws Exception {
    try (JarFile jar = new JarFile(artifact.toFile())) {
      for (JarEntry entry : jar.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
        String resource = entry.getName();
        assertThat(Collections.list(loader.getResources(resource)))
            .as("Exactly one engine class resource: %s", resource)
            .hasSize(1)
            .allSatisfy(url -> assertThat(url.getProtocol()).isEqualTo("jar"));
        String className =
            resource.substring(0, resource.length() - ".class".length()).replace('/', '.');
        // Inspect provenance without initializing configuration, loggers or worker lifecycles.
        Class<?> queueClass = Class.forName(className, false, loader);
        assertThat(Path.of(queueClass.getProtectionDomain().getCodeSource().getLocation().toURI()))
            .as("Engine class origin: %s", className)
            .isEqualTo(artifact);
      }
    }
  }
}
