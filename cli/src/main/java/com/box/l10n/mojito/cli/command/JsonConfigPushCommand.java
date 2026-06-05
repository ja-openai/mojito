package com.box.l10n.mojito.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.JsonConfigLocalization;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.StatsigPushInput;
import com.box.l10n.mojito.rest.client.JsonConfigLocalizationClient.StatsigPushResult;
import com.box.l10n.mojito.rest.entity.Repository;
import java.util.List;
import org.fusesource.jansi.Ansi.Color;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
@Parameters(
    commandNames = {"json-config-push"},
    commandDescription = "Push localized JSON config from a saved setup to Statsig")
public class JsonConfigPushCommand extends JsonConfigCommandSupport {

  @Parameter(
      names = {"--config-id"},
      arity = 1,
      description = "Statsig config id. Defaults to the saved setup provider config id.")
  String configIdParam;

  @Parameter(
      names = {"--dry-run"},
      description = "Build the Statsig update without changing Statsig")
  boolean dryRunParam;

  @Parameter(
      names = {"--output", "-o"},
      arity = 1,
      description = "Optional file for the Statsig response JSON")
  String outputFileParam;

  @Override
  protected void execute() throws CommandException {
    Repository repository = getRepository();
    JsonConfigLocalization setup = resolveSetup(repository, configIdParam, true);
    String configId = firstNonBlank(configIdParam, setup.providerConfigId());
    if (!hasText(configId)) {
      throw new CommandException("Statsig config id is required for this setup.");
    }

    StatsigPushResult result =
        jsonConfigLocalizationClient.pushStatsigForSetup(
            setup.id(), new StatsigPushInput(configId, dryRunParam ? Boolean.TRUE : null));

    printWarnings(result.warnings() == null ? List.of() : result.warnings());
    writeResponse(result.responseJson(), outputFileParam);

    consoleWriter
        .fg(result.dryRun() ? Color.YELLOW : Color.GREEN)
        .a(result.dryRun() ? "Built dry-run Statsig update for " : "Pushed Statsig config ")
        .a(result.configId())
        .println();
  }
}
