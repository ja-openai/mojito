package com.box.l10n.mojito.cli.command;

import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.rest.resttemplate.AuthenticatedRestTemplate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Copies the small example bundled at build time and uses ordinary CLI operations to seed it. */
@Component
public class ContentDemo {
  static final List<String> LOCALES = List.of("fr", "de", "es", "ja", "ar");
  static final List<String> ASSETS =
      List.of(
          "index.mdx",
          "guide.mdx",
          "modules/SharedNote.mdx",
          "modules/Welcome.mdx",
          "modules/audience/Individual.mdx",
          "modules/audience/Team.mdx",
          "modules/routine/Steps.mdx",
          "modules/routine/Tip.mdx",
          "messages.mf2.json");
  private static final String RESOURCE_ROOT = "/content-demo/";

  private final ConsoleWriter console;
  private final CommandHelper commandHelper;
  private final AuthenticatedRestTemplate client;

  public ContentDemo(
      ConsoleWriter console, CommandHelper commandHelper, AuthenticatedRestTemplate client) {
    this.console = console;
    this.commandHelper = commandHelper;
    this.client = client;
  }

  public void create(String name, String outputDirectory) {
    Path output =
        Path.of(outputDirectory == null ? name : outputDirectory).toAbsolutePath().normalize();
    // Fail before creating a repository when local output or bundled fixtures cannot be used.
    Map<String, byte[]> resources = readResources();
    writeResources(output, resources);

    var createArgs = new ArrayList<>(List.of("repo-create", "-n", name, "-sl", "en", "-l"));
    createArgs.addAll(LOCALES);
    createArgs.addAll(List.of("-it", "json:MF2"));
    runCommand(createArgs.toArray(String[]::new));
    var repository = commandHelper.findRepositoryByName(name);

    String source = output.resolve("content").toString();
    runCommand("push", "-r", name, "-s", source, "-ft", "MDX", "JSON");
    var importArgs =
        new ArrayList<>(
            List.of(
                "import",
                "-r",
                name,
                "-s",
                source,
                "-t",
                output.resolve("translations").toString(),
                "-ft",
                "MDX",
                "JSON",
                "-lm"));
    importArgs.add(
        String.join(",", LOCALES.stream().map(locale -> locale + ":" + locale).toList()));
    importArgs.addAll(List.of("-lmt", "MAP_ONLY"));
    runCommand(importArgs.toArray(String[]::new));

    console
        .newLine()
        .a("Content demo repository is ready: ")
        .a(name)
        .println()
        .a("English source; target languages: ")
        .a(String.join(", ", LOCALES))
        .println()
        .a("Open Content: ")
        .a(client.getURIForResource("content") + "?repoId=" + repository.getId() + "&locale=fr")
        .println()
        .a("Sign in as an admin. Enable My Settings > Admin features > Show Content tab if needed.")
        .println()
        .a("Demo files and push/pull instructions: ")
        .a(output.resolve("README.md").toString())
        .println();
  }

  private Map<String, byte[]> readResources() {
    Map<String, byte[]> resources = new LinkedHashMap<>();
    resources.put("README.md", readResource("README.md"));
    for (String asset : ASSETS) {
      resources.put("content/" + asset, readResource("content/" + asset));
      for (String locale : LOCALES) {
        int extension = asset.lastIndexOf('.');
        String target = asset.substring(0, extension) + "_" + locale + asset.substring(extension);
        resources.put("translations/" + target, readResource("translations/" + target));
      }
    }
    return resources;
  }

  private byte[] readResource(String name) {
    try (var input = getClass().getResourceAsStream(RESOURCE_ROOT + name)) {
      if (input == null) {
        throw new CommandException("Missing bundled content demo resource: " + name);
      }
      return input.readAllBytes();
    } catch (IOException e) {
      throw new CommandException("Cannot read content demo resource: " + name, e);
    }
  }

  private void writeResources(Path output, Map<String, byte[]> resources) {
    try {
      if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
          throw new CommandException(
              "Content demo output must be a new or empty directory: " + output);
        }
        try (var entries = Files.list(output)) {
          if (entries.findAny().isPresent()) {
            throw new CommandException(
                "Content demo output must be a new or empty directory: " + output);
          }
        }
      }
      Files.createDirectories(output);
      for (var entry : resources.entrySet()) {
        Path destination = output.resolve(entry.getKey());
        Files.createDirectories(destination.getParent());
        Files.write(destination, entry.getValue(), StandardOpenOption.CREATE_NEW);
      }
    } catch (IOException e) {
      throw new CommandException("Cannot write content demo files to " + output, e);
    }
  }

  void runCommand(String... args) {
    L10nJCommander command = new L10nJCommander();
    command.setSystemExitEnabled(false);
    command.run(args);
    if (command.getExitCode() != 0) {
      throw new CommandException(
          "Content demo stopped during "
              + args[0]
              + "; see the error above. "
              + "The generated files and any completed server operations were kept.");
    }
  }
}
