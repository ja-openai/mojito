package com.box.l10n.mojito.service.blobstorage.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

public class DatabaseBlobStorageConfigurationPropertiesTest {

  @Test
  public void temporaryRetentionDefaultsToExactlyOneDay() {
    assertThat(new DatabaseBlobStorageConfigurationProperties().getMin1DayTtl())
        .isEqualTo(Duration.ofDays(1).toSeconds());
  }

  @Test
  public void explicitShortRetentionOverrideIsNotChangedByTheDefault() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("l10n.blob-storage.database.min1-day-ttl", "1")));

    var properties =
        binder
            .bind(
                "l10n.blob-storage.database",
                Bindable.of(DatabaseBlobStorageConfigurationProperties.class))
            .get();

    assertThat(properties.getMin1DayTtl()).isEqualTo(1);
  }
}
