package com.box.l10n.mojito.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.JacksonConfigurationProperties;
import com.box.l10n.mojito.json.ObjectMapper;
import org.junit.Test;

/**
 * @author jaurambault
 */
public class AppTest extends CLITestBase {

  @Test
  public void appVersion() throws Exception {
    getL10nJCommander().run("--version");
  }

  @Test
  public void appHelp() throws Exception {
    getL10nJCommander().run("--help");
  }

  @Test
  public void appHelpShort() throws Exception {
    getL10nJCommander().run("-h");
  }

  @Test
  public void outputMapperUsesConfiguredMaximumStringLength() {
    JacksonConfigurationProperties properties = new JacksonConfigurationProperties();
    properties.setMaxStringLength(40_000_000);

    ObjectMapper objectMapper = new App().getOutputIndented(properties);

    assertThat(objectMapper.getFactory().streamReadConstraints().getMaxStringLength())
        .isEqualTo(40_000_000);
  }
}
