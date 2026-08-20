package com.box.l10n.mojito.okapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.Test;

public class AndroidFilterConfigurationPropertiesTest {

  @Test
  public void disabledServerDefaultDoesNotChangeOptions() {
    AndroidFilterConfigurationProperties properties = new AndroidFilterConfigurationProperties();
    List<String> requested = List.of("oldEscaping=true");

    assertThat(properties.applyServerDefaults(requested)).isSameAs(requested);
    assertThat(properties.applyServerDefaults(null)).isNull();
  }

  @Test
  public void enabledServerDefaultAddsAutoDetection() {
    AndroidFilterConfigurationProperties properties = new AndroidFilterConfigurationProperties();
    properties.setAutoDetectAnchorTags(true);

    assertThat(properties.applyServerDefaults(null)).containsExactly("unescapeAnchorTags=auto");
    assertThat(properties.applyServerDefaults(List.of("oldEscaping=true")))
        .containsExactly("oldEscaping=true", "unescapeAnchorTags=auto");
  }

  @Test
  public void requestedOptionOverridesEnabledServerDefault() {
    AndroidFilterConfigurationProperties properties = new AndroidFilterConfigurationProperties();
    properties.setAutoDetectAnchorTags(true);

    assertThat(properties.applyServerDefaults(List.of("unescapeAnchorTags=false")))
        .containsExactly("unescapeAnchorTags=false");
    assertThat(properties.applyServerDefaults(List.of("unescapeAnchorTags=true")))
        .containsExactly("unescapeAnchorTags=true");
    assertThat(properties.applyServerDefaults(List.of("unescapeAnchorTags=auto")))
        .containsExactly("unescapeAnchorTags=auto");
  }
}
