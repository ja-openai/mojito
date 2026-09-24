package com.box.l10n.mojito.service.cli;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.samskivert.mustache.Mustache;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises the rendered installer with real bash, curl, Java, and a local HTTP server. */
public class CliInstallScriptTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void recoversFromServiceUnavailableAndRateLimit() throws Exception {
    byte[] jar = executableJar("new");
    Result result =
        install(
            new Response(503, "unavailable".getBytes(StandardCharsets.UTF_8), null, false),
            new Response(429, new byte[0], "1", false),
            new Response(200, jar, null, false));

    assertEquals(result.output, 0, result.exitCode);
    assertEquals(3, result.requests);
    assertArrayEquals(jar, result.installedJar);
    assertTrue(result.output, result.output.contains("retry"));
  }

  @Test
  public void preservesHttpErrorsWithoutRetryingCredentialsOrValidationFailures() throws Exception {
    for (int status : new int[] {400, 401, 403, 404}) {
      Result result = install(new Response(status, new byte[0], null, false));

      assertEquals(result.output, 22, result.exitCode);
      assertEquals(1, result.requests);
      assertArrayEquals(executableJar("old"), result.installedJar);
      assertTrue(result.output, result.output.contains(Integer.toString(status)));
      assertTrue(result.output, result.output.contains("Mojito CLI download failed"));
    }
  }

  @Test
  public void stopsWhenRetryAfterExceedsTheRetryBudget() throws Exception {
    Result result = install(new Response(503, new byte[0], "180", false));

    assertEquals(result.output, 22, result.exitCode);
    assertEquals(1, result.requests);
    assertArrayEquals(executableJar("old"), result.installedJar);
    assertTrue(result.output, result.output.contains("maximum"));
    assertTrue(result.output, result.output.contains("Mojito CLI download failed"));
  }

  @Test
  public void stopsAfterEightRetriesAndPreservesTheFinalError() throws Exception {
    Result result = install(true, new Response(503, new byte[0], null, false));

    assertEquals(result.output, 22, result.exitCode);
    assertEquals(9, result.requests);
    assertArrayEquals(executableJar("old"), result.installedJar);
    assertTrue(result.output, result.output.contains("503"));
    assertTrue(result.output, result.output.contains("Mojito CLI download failed"));
  }

  @Test
  public void rejectsErrorPagesAndTruncatedJarsWithoutReplacingTheInstalledJar() throws Exception {
    byte[] jar = executableJar("new");
    for (byte[] invalid :
        new byte[][] {
          "<html>upstream unavailable</html>".getBytes(StandardCharsets.UTF_8),
          Arrays.copyOf(jar, jar.length / 2)
        }) {
      Result result = install(new Response(200, invalid, null, false));

      assertEquals(result.output, 1, result.exitCode);
      assertEquals(1, result.requests);
      assertArrayEquals(executableJar("old"), result.installedJar);
      assertTrue(result.output, result.output.contains("Downloaded Mojito CLI JAR is invalid"));
    }
  }

  @Test
  public void preservesTheInstalledJarOnAnIncompleteTransfer() throws Exception {
    Result result = install(new Response(200, executableJar("new"), null, true));

    assertEquals(result.output, 18, result.exitCode);
    assertEquals(1, result.requests);
    assertArrayEquals(executableJar("old"), result.installedJar);
    assertTrue(result.output, result.output.contains("Mojito CLI download failed"));
  }

  @Test
  public void preservesTheInstalledJarWhenTheConnectionClosesBeforeHeaders() throws Exception {
    Result result = install(new Response(0, new byte[0], null, false));

    assertEquals(result.output, 52, result.exitCode);
    assertEquals(1, result.requests);
    assertArrayEquals(executableJar("old"), result.installedJar);
    assertTrue(result.output, result.output.contains("Mojito CLI download failed"));
  }

  private Result install(Response... responses) throws Exception {
    return install(false, responses);
  }

  private Result install(boolean shortRetryDelays, Response... responses) throws Exception {
    Path directory = temporaryFolder.newFolder().toPath();
    Path installDirectory = directory.resolve(".mojito");
    Files.createDirectories(installDirectory);
    Path installedJar = installDirectory.resolve("mojito-cli.jar");
    Files.write(installedJar, executableJar("old"));
    AtomicInteger requests = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/cli/mojito-cli.jar",
        exchange -> {
          Response response = responses[Math.min(requests.getAndIncrement(), responses.length - 1)];
          if (response.status == 0) {
            exchange.close();
            return;
          }
          if (response.retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", response.retryAfter);
          }
          if (response.incomplete) {
            exchange.getResponseHeaders().set("Connection", "close");
          }
          long length = response.body.length + (response.incomplete ? 100 : 0);
          exchange.sendResponseHeaders(response.status, length == 0 ? -1 : length);
          try {
            exchange.getResponseBody().write(response.body);
          } finally {
            // Close the exchange directly so an incomplete body closes the connection as well.
            exchange.close();
          }
        });
    server.start();
    Process process = null;
    try {
      InstallCliContext context =
          new InstallCliContext(
              "${PWD}/.mojito",
              "http",
              "127.0.0.1",
              Integer.toString(server.getAddress().getPort()));
      context.cliFileCacheKey = "test";
      String template;
      try (InputStream stream = getClass().getResourceAsStream("/templates/cli/install.sh")) {
        template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      }
      Path script = directory.resolve("install.sh");
      Files.writeString(script, Mustache.compiler().compile(template).execute(context));
      Path output = directory.resolve("output");
      // Exercise curl's real retry limit quickly. All other tests use production delays.
      String sourceCommand =
          (shortRetryDelays ? "curl() { command curl \"$@\" --retry-delay 1; }; " : "")
              + "source ./install.sh";
      process =
          new ProcessBuilder("bash", "-eu", "-c", sourceCommand)
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      assertTrue("Installer fixture did not complete", process.waitFor(20, TimeUnit.SECONDS));
      try (var files = Files.list(installDirectory)) {
        assertFalse(
            "Temporary download was not cleaned up",
            files.anyMatch(path -> path.getFileName().toString().startsWith("mojito-cli.jar.")));
      }
      return new Result(
          process.exitValue(),
          requests.get(),
          Files.readAllBytes(installedJar),
          Files.readString(output));
    } finally {
      if (process != null) {
        process.destroyForcibly();
      }
      server.stop(0);
    }
  }

  private byte[] executableJar(String marker) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, TestCli.class.getName());
    try (JarOutputStream jar = new JarOutputStream(bytes)) {
      JarEntry manifestEntry = new JarEntry("META-INF/MANIFEST.MF");
      manifestEntry.setTime(0);
      jar.putNextEntry(manifestEntry);
      manifest.write(jar);
      jar.closeEntry();
      String classFile = TestCli.class.getName().replace('.', '/') + ".class";
      JarEntry classEntry = new JarEntry(classFile);
      classEntry.setTime(0);
      jar.putNextEntry(classEntry);
      try (InputStream stream = getClass().getResourceAsStream("/" + classFile)) {
        stream.transferTo(jar);
      }
      jar.closeEntry();
      JarEntry markerEntry = new JarEntry(marker);
      markerEntry.setTime(0);
      jar.putNextEntry(markerEntry);
      jar.closeEntry();
    }
    return bytes.toByteArray();
  }

  /** A minimal executable JAR fixture that needs no server for --version. */
  public static class TestCli {
    public static void main(String[] args) {
      System.exit(args.length == 1 && args[0].equals("--version") ? 0 : 1);
    }
  }

  private record Response(int status, byte[] body, String retryAfter, boolean incomplete) {}

  private record Result(int exitCode, int requests, byte[] installedJar, String output) {}
}
