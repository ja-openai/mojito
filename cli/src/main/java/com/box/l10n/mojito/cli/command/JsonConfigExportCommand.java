package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.JsonConfigLocalization;
import com.box.l10n.mojito.rest.entity.Repository;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
@Parameters(
    commandNames = {"json-config-export"},
    commandDescription = "Export localized JSON config from a saved setup")
public class JsonConfigExportCommand extends JsonConfigCommandSupport {

  @Parameter(
      names = {"--output", "-o"},
      arity = 1,
      description = "Output file for localized JSON. Defaults to stdout.")
  String outputFileParam;

  @Parameter(
      names = {"--output-dir", "--output-directory"},
      arity = 1,
      description = "Output directory for one JSON file per locale.")
  String outputDirectoryParam;

  @Parameter(
      names = {"--config-id"},
      arity = 1,
      description = "Provider config id for setup selection.")
  String configIdParam;

  @Override
  protected void execute() throws CommandException {
    Repository repository = getRepository();
    JsonConfigLocalization setup = resolveSetup(repository, configIdParam, true);
    writeExport(repository, setup, outputFileParam, outputDirectoryParam);
  }
}
