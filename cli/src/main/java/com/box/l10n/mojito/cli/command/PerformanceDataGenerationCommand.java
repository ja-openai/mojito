package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.cli.console.ConsoleWriter;
import com.box.l10n.mojito.io.Files;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.ibm.icu.text.MessageFormat;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * @author jaurambault
 */
@Component
@Scope("prototype")
@Parameters(
    commandNames = {"performance-data-gen"},
    commandDescription = "Generate data for performance tests")
public class PerformanceDataGenerationCommand extends Command {

  static final String MASTER_BRANCH_NAME = "master";
  static final int MASTER_BRANCH_IDX = -1;
  static final String PERFORMANCE = "performance";

  /** logger */
  static Logger logger = LoggerFactory.getLogger(PerformanceDataGenerationCommand.class);

  @Autowired ConsoleWriter consoleWriter;

  @Parameter(
      names = {"--command-name"},
      description = "Command name to use to run Mojito's CLI")
  private String commandName = "mojito";

  @Parameter(
      names = {"--branch-creator"},
      description = "Branch creator")
  private String branchCreator = "admin";

  @Override
  public boolean shouldShowInCommandList() {
    return false;
  }

  @Override
  public void execute() throws CommandException {
    String locales =
        "cs-CZ da-DK de-DE el-GR en-GB es-AR es-ES es-MX fi-FI fr-FR hi-IN hu-HU id-ID it-IT ja-JP "
            + "ko-KR ms-MY nb-NO nl-NL pl-PL pt-BR pt-PT ro-RO ru-RU sk-SK sv-SE th-TH tl-PH tr-TR uk-UA vi-VN";

    createBaseDirectory();

    ImmutableList<RepositoryInfo> repositoryInfos =
        ImmutableList.of(
            new RepositoryInfo(100, 10, 0, 0),
            new RepositoryInfo(1, 1000, 5, 10),
            new RepositoryInfo(1000, 10, 0, 0),
            new RepositoryInfo(1, 10000, 5, 10),
            new RepositoryInfo(1, 10000, 5, 100),
            new RepositoryInfo(1, 50000, 5, 10));

    String commands =
        repositoryInfos.stream()
            .map(
                repositoryInfo -> {
                  String repositoryName = getRepositoryName(repositoryInfo);
                  StringBuilder sb = new StringBuilder();
                  sb.append(getDeleteRepositoryCommand(commandName, repositoryName)).append("\n");
                  sb.append(getCreateRepositoryCommand(commandName, repositoryName, locales))
                      .append("\n");
                  sb.append(
                          getPushCommand(
                              commandName, repositoryName, MASTER_BRANCH_NAME, branchCreator))
                      .append("\n");
                  sb.append(
                          getPullCommand(
                              commandName, repositoryName, MASTER_BRANCH_NAME, branchCreator))
                      .append("\n");
                  sb.append(
                          getImportCommand(
                              commandName, repositoryName, MASTER_BRANCH_NAME, branchCreator))
                      .append("\n");

                  generateFiles(repositoryInfo, MASTER_BRANCH_IDX);

                  String branchPushCommands =
                      IntStream.range(0, repositoryInfo.numberOfBranches())
                          .mapToObj(
                              branchNumber -> {
                                generateFiles(repositoryInfo, branchNumber);
                                return getPushCommand(
                                        commandName,
                                        repositoryName,
                                        getBranchName(branchNumber),
                                        branchCreator)
                                    + "\n";
                              })
                          .collect(Collectors.joining());
                  sb.append(branchPushCommands);
                  return sb.toString();
                })
            .collect(Collectors.joining("\n"));

    createScript(commands);
  }

  String getBranchName(int branchNumber) {
    return MASTER_BRANCH_IDX == branchNumber ? MASTER_BRANCH_NAME : "branch" + branchNumber;
  }

  void createScript(String commands) {
    consoleWriter.a(commands).println();
    Path scriptPath = Paths.get(PERFORMANCE, "script.sh");
    Files.write(scriptPath, commands);
  }

  void createBaseDirectory() {
    Path path = Paths.get(PERFORMANCE);
    Files.deleteRecursivelyIfExists(path);
    Files.createDirectories(path);
  }

  void generateFiles(RepositoryInfo repositoryInfo, int branchNumber) {
    IntStream.range(0, repositoryInfo.numberOfAssets())
        .forEach(
            assetIdx -> {
              Path path =
                  Paths.get(
                      PERFORMANCE,
                      getRepositoryName(repositoryInfo),
                      getBranchName(branchNumber),
                      "performance-" + assetIdx + ".properties");
              generateFile(
                  path,
                  MASTER_BRANCH_IDX == branchNumber
                      ? 0
                      : repositoryInfo.numberOfTextUnitsInMaster()
                          + 1
                          + branchNumber * repositoryInfo.numberOfTextUnitsInBranches(),
                  MASTER_BRANCH_IDX == branchNumber
                      ? repositoryInfo.numberOfTextUnitsInMaster()
                      : repositoryInfo.numberOfTextUnitsInBranches());
            });
  }

  void generateFile(Path path, int textunitIdx, int numberOfTextUnits) {
    String fileContent =
        IntStream.range(textunitIdx, textunitIdx + numberOfTextUnits)
            .mapToObj(
                idx ->
                    String.format(
                        "# %s\n%s=%s\n\n",
                        "comment-" + idx + "-" + Strings.padStart("", 50, 'a'),
                        "name-" + idx,
                        "value-" + idx + "-" + Strings.padStart("", 30, 'a')))
            .collect(Collectors.joining());
    Files.createDirectories(path.getParent());
    Files.write(path, fileContent);
  }

  String getRepositoryName(RepositoryInfo repositoryInfo) {
    return MessageFormat.format(
        RepositoryInfo.NAME_PATTERN,
        ImmutableMap.of(
            "numberOfAssets", Integer.toString(repositoryInfo.numberOfAssets()),
            "numberOfBranches", Integer.toString(repositoryInfo.numberOfBranches()),
            "numberOfTextUnitsInBranches",
                Integer.toString(repositoryInfo.numberOfTextUnitsInBranches()),
            "numberOfTextUnitsInMaster",
                Integer.toString(repositoryInfo.numberOfTextUnitsInMaster())));
  }

  String getDeleteRepositoryCommand(String commandName, String repository) {
    return MessageFormat.format(
        "time {commandName} repo-delete -n {repository} || echo \"Repository not deleted\"",
        ImmutableMap.of("commandName", commandName, "repository", repository));
  }

  String getCreateRepositoryCommand(String commandName, String repository, String locales) {
    return MessageFormat.format(
        "time {commandName} repo-create -n {repository} -l {locales}",
        ImmutableMap.of("commandName", commandName, "repository", repository, "locales", locales));
  }

  String getPushCommand(
      String commandName, String repository, String branch, String branchCreator) {
    return MessageFormat.format(
        "time {commandName} push -r {repository} -s {repository}/{branch} -b {branch} -bc {branchCreator}",
        ImmutableMap.of(
            "commandName",
            commandName,
            "repository",
            repository,
            "branch",
            branch,
            "branchCreator",
            branchCreator));
  }

  String getPullCommand(
      String commandName, String repository, String branch, String branchCreator) {
    return MessageFormat.format(
        "time {commandName} pull -r {repository} -s {repository}/{branch}",
        ImmutableMap.of("commandName", commandName, "repository", repository, "branch", branch));
  }

  String getImportCommand(
      String commandName, String repository, String branch, String branchCreator) {
    return MessageFormat.format(
        "time {commandName} import -r {repository} -s {repository}/{branch}",
        ImmutableMap.of("commandName", commandName, "repository", repository, "branch", branch));
  }

  record RepositoryInfo(
      int numberOfAssets,
      int numberOfTextUnitsInMaster,
      int numberOfTextUnitsInBranches,
      int numberOfBranches) {
    static final String NAME_PATTERN =
        "{numberOfAssets}A_{numberOfTextUnitsInMaster}TUM_{numberOfBranches}B_{numberOfTextUnitsInBranches}TUB";
  }
}
