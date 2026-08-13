package com.box.l10n.mojito.cli.command;

import com.box.l10n.mojito.cli.CLITestBase;
import com.box.l10n.mojito.fileformat.LocalizationConverterSelection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jeanaurambault
 */
public class ExtractionCommandTest extends CLITestBase {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(ExtractionCommandTest.class);

  @Test
  public void extract() throws Exception {
    getL10nJCommander()
        .run(
            "extract",
            "-s",
            getInputResourcesTestDir("source1").getAbsolutePath(),
            "-o",
            getTargetTestDir().getAbsolutePath(),
            "-fo",
            "testoption=something",
            "-n",
            "source1");

    checkExpectedGeneratedResources();
  }

  @Test
  public void extractBrokenPO() {
    L10nJCommander l10nJCommander = getL10nJCommander();
    l10nJCommander.run(
        "extract",
        "-s",
        getInputResourcesTestDir("source1").getAbsolutePath(),
        "-o",
        getTargetTestDir().getAbsolutePath(),
        "-fo",
        "testoption=something",
        "-n",
        "source1");

    Assert.assertEquals(1L, l10nJCommander.getExitCode());
  }

  @Test
  public void portableConverterReusesExistingGettextExtractionDataset() throws Exception {
    Path source =
        Path.of(
            "src/test/resources/com/box/l10n/mojito/cli/command/ExtractionCommandTest_IO/extract/input/source1");

    getL10nJCommander()
        .run(
            "extract",
            "-s",
            source.toString(),
            "-o",
            getTargetTestDir("legacy").getAbsolutePath(),
            "-n",
            "source1");
    getL10nJCommander()
        .run(
            "extract",
            "-s",
            source.toString(),
            "-o",
            getTargetTestDir("portable").getAbsolutePath(),
            "-n",
            "source1",
            "--converter",
            "portable");

    ObjectMapper mapper = new ObjectMapper();
    for (String file : new String[] {"messages.pot.json", "messages2.pot.json"}) {
      Path relative = Path.of("source1", "LC_MESSAGES", file);
      ObjectNode legacy =
          (ObjectNode)
              mapper.readTree(getTargetTestDir("legacy").toPath().resolve(relative).toFile());
      ObjectNode portable =
          (ObjectNode)
              mapper.readTree(getTargetTestDir("portable").toPath().resolve(relative).toFile());
      JsonNode selection = portable.remove("filterOptions");
      legacy.remove("filterOptions");
      Assert.assertEquals(legacy, portable);
      Assert.assertEquals(
          LocalizationConverterSelection.PORTABLE_OPTION, selection.get(0).asText());
    }
  }
}
