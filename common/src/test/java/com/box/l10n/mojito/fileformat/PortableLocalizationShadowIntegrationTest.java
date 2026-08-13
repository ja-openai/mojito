package com.box.l10n.mojito.fileformat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.box.l10n.mojito.okapi.TextUnitUtils;
import com.box.l10n.mojito.okapi.asset.AssetPathToFilterConfigMapper;
import com.box.l10n.mojito.okapi.asset.FilterConfigurationMappers;
import com.box.l10n.mojito.okapi.extractor.AssetExtractor;
import com.box.l10n.mojito.okapi.extractor.AssetExtractorTextUnit;
import com.box.l10n.mojito.okapi.filters.UnescapeUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.aspectj.EnableSpringConfigured;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * The opt-in production hook observes the real pipeline without changing its returned text units.
 */
@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(
    classes = {
      AssetExtractor.class,
      AssetPathToFilterConfigMapper.class,
      FilterConfigurationMappers.class,
      TextUnitUtils.class,
      UnescapeUtils.class,
      PortableLocalizationShadow.class,
      PortableLocalizationShadowIntegrationTest.MetricsConfiguration.class
    },
    properties = "l10n.file-formats.portable.shadow.enabled=true")
@EnableSpringConfigured
@DirtiesContext
public class PortableLocalizationShadowIntegrationTest {

  @Autowired private AssetExtractor extractor;

  @Autowired private MeterRegistry registry;

  @Test
  public void featureFlagEnablesObservationalComparisonWithoutChangingLegacyResult()
      throws Exception {
    List<AssetExtractorTextUnit> extracted =
        extractor.getAssetExtractorTextUnitsForAsset(
            "res/values/strings.xml",
            "<resources><string name=\"signal\">Steady</string></resources>",
            null,
            null);

    assertEquals(1, extracted.size());
    assertEquals("signal", extracted.get(0).getName());
    assertEquals("Steady", extracted.get(0).getSource());
    assertEquals(
        1,
        registry
            .get(PortableLocalizationShadow.COMPARISONS)
            .tags("format", "android", "outcome", "match")
            .counter()
            .count(),
        0);
  }

  @Test
  public void productProjectionCollisionMetricsNeverExposeQualifiedMessageIdentities()
      throws Exception {
    List<AssetExtractorTextUnit> extracted =
        extractor.getAssetExtractorTextUnitsForAsset(
            "res/values/strings.xml",
            "<resources>"
                + "<string name=\"beacon\" product=\"default\">Steady</string>"
                + "<string name=\"beacon\" product=\"tablet\">Wide</string>"
                + "</resources>",
            null,
            null);

    assertEquals(2, extracted.size());
    assertEquals(
        2,
        registry
            .get(PortableLocalizationShadow.DIFFERENCES)
            .tags("format", "android", "category", "legacy_projection_collision")
            .counter()
            .count(),
        0);
    assertEquals(
        2,
        registry
            .get(PortableLocalizationShadow.DIFFERENCES)
            .tags("format", "android", "category", "duplicate_legacy")
            .counter()
            .count(),
        0);
    assertTrue(
        registry.find(PortableLocalizationShadow.DIFFERENCES).meters().stream()
            .allMatch(
                meter ->
                    meter.getId().getTags().stream()
                        .allMatch(tag -> List.of("format", "category").contains(tag.getKey()))));
  }

  @Configuration
  static class MetricsConfiguration {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
