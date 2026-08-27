package com.box.l10n.mojito.okapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.okapi.filters.AndroidFilter;
import java.util.List;
import org.junit.Test;

public class AndroidFilterConfigurationPropertiesTest {

  @Test
  public void disabledServerDefaultDoesNotChangeOptions() {
    AndroidFilterConfigurationProperties properties = new AndroidFilterConfigurationProperties();
    List<String> requested = List.of("oldEscaping=true");

    assertThat(properties.isValidateGeneratedResources()).isFalse();
    assertThat(properties.applyServerDefaults(requested)).isSameAs(requested);
    assertThat(properties.applyServerDefaults(null)).isNull();
  }

  @Test
  public void generatedResourceValidationCanBeEnabledForCanaryRollout() {
    AndroidFilterConfigurationProperties properties = new AndroidFilterConfigurationProperties();

    properties.setValidateGeneratedResources(true);

    assertThat(properties.isValidateGeneratedResources()).isTrue();
    assertThat(properties.applyServerDefaults(null))
        .containsExactly(AndroidFilter.OPTION_VALIDATE_GENERATED_RESOURCES + "=true");
    assertThat(
            properties.applyServerDefaults(
                List.of(AndroidFilter.OPTION_VALIDATE_GENERATED_RESOURCES + "=false")))
        .containsExactly(AndroidFilter.OPTION_VALIDATE_GENERATED_RESOURCES + "=true");
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
