package com.box.l10n.mojito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.Test;

public class FlyWayConfigTest {

  @Test
  public void optionalRepairDoesNothingByDefault() {
    FlyWayConfig flyWayConfig = new FlyWayConfig();
    Flyway flyway = mock(Flyway.class);

    flyWayConfig.optionalRepair(flyway);

    assertFalse(flyWayConfig.isRepair());
    verifyNoInteractions(flyway);
  }

  @Test
  public void optionalRepairRunsForUnprotectedSchema() {
    FlyWayConfig flyWayConfig = flyWayConfigWithCleanProtection(false);
    Flyway flyway = mock(Flyway.class);
    flyWayConfig.setRepair(true);

    flyWayConfig.optionalRepair(flyway);

    assertTrue(flyWayConfig.isRepair());
    verify(flyway).repair();
  }

  @Test
  public void optionalRepairFailsForProtectedSchema() {
    FlyWayConfig flyWayConfig = flyWayConfigWithCleanProtection(true);
    Flyway flyway = mock(Flyway.class);
    flyWayConfig.setRepair(true);

    assertThrows(RuntimeException.class, () -> flyWayConfig.optionalRepair(flyway));
    verify(flyway, never()).repair();
  }

  @Test
  public void mysql8RepairFailsForProtectedSchema() {
    FlyWayConfig flyWayConfig = flyWayConfigWithCleanProtection(true);
    Flyway flyway = mock(Flyway.class);

    assertThrows(
        RuntimeException.class,
        () -> flyWayConfig.tryToMigrateIfMysql8Migration(flyway, mysql8ChecksumMismatch()));
    verify(flyway, never()).repair();
  }

  private FlywayException mysql8ChecksumMismatch() {
    return new FlywayException(
        "Migration checksum mismatch for migration version 1\n"
            + "-> Applied to database : 1443976515\n"
            + "-> Resolved locally    : -998267617");
  }

  private FlyWayConfig flyWayConfigWithCleanProtection(boolean enabled) {
    return new FlyWayConfig() {
      @Override
      boolean isFlywayProtectionEnabled() {
        return enabled;
      }
    };
  }
}
