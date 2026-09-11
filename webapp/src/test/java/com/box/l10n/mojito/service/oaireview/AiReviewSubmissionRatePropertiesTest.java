package com.box.l10n.mojito.service.oaireview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class AiReviewSubmissionRatePropertiesTest {
  @Test
  public void defaultsObserveThreePerSecondWithTenBurstTokens() {
    AiReviewSubmissionRateProperties properties = new AiReviewSubmissionRateProperties();
    assertTrue(properties.isEnabled());
    assertEquals(3, properties.getRequestsPerSecond(), 0);
    assertEquals(10, properties.getBurst());
  }

  @Test
  public void springBindsOverridesAndRetainsUnspecifiedDefaults() {
    AiReviewSubmissionRateProperties properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "l10n.ai-review.submission-rate.enabled", "false",
                        "l10n.ai-review.submission-rate.requests-per-second", "4.5")))
            .bind(
                "l10n.ai-review.submission-rate",
                Bindable.of(AiReviewSubmissionRateProperties.class))
            .get();
    assertFalse(properties.isEnabled());
    assertEquals(4.5, properties.getRequestsPerSecond(), 0);
    assertEquals(10, properties.getBurst());
  }

  @Test
  public void invalidRatesAndBurstsDoNotReplaceWorkingConfiguration() {
    AiReviewSubmissionRateProperties properties = new AiReviewSubmissionRateProperties();
    for (double invalid :
        new double[] {
          0, -1, 1001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
      assertThrows(IllegalArgumentException.class, () -> properties.setRequestsPerSecond(invalid));
    }
    for (int invalid : new int[] {0, -1, 10001}) {
      assertThrows(IllegalArgumentException.class, () -> properties.setBurst(invalid));
    }
    assertEquals(3, properties.getRequestsPerSecond(), 0);
    assertEquals(10, properties.getBurst());
  }

  @Test
  public void validBoundaryValuesAndFractionalRatesAreSupported() {
    AiReviewSubmissionRateProperties properties = new AiReviewSubmissionRateProperties();
    properties.setRequestsPerSecond(0.5);
    properties.setBurst(1);
    assertEquals(0.5, properties.getRequestsPerSecond(), 0);
    assertEquals(1, properties.getBurst());
    properties.setRequestsPerSecond(1000);
    properties.setBurst(10000);
    assertEquals(1000, properties.getRequestsPerSecond(), 0);
    assertEquals(10000, properties.getBurst());
  }
}
