package com.box.l10n.mojito.service.assetintegritychecker.integritychecker;

import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;

public class ValdiIntegrityCheckerTest {
  private final ValdiIntegrityChecker checker = new ValdiIntegrityChecker();

  @Test
  public void acceptsPreservedTokens() {
    checker.check("{number%d}", "{number%d}");
    checker.check("Item {number%d}", "Елемент {number%d}");
    checker.check("{count%d} items", "{count%d} елемента");
    checker.check("{user_name} {x2%s} {n%d}/{n%d}", "{n%d}/{n%d} {user_name} {x2%s}");
    checker.check("100% complete", "100% terminé");
    checker.check("Use { and } around {word%s}", "Entourez {word%s} de { et }");
  }

  @Test
  public void rejectsChangedTokensAndCounts() {
    for (String target :
        List.of(
            "", "{total%d}", "{n}", "{n%s}", "{n%d}{n%d}", "{n%d}{extra}", "n%d}", "{n", "{}")) {
      assertThrows(IntegrityCheckException.class, () -> checker.check("{n%d}", target));
    }
    assertThrows(IntegrityCheckException.class, () -> checker.check("{n%d}{n%d}", "{n%d}"));
  }

  @Test
  public void rejectsMalformedPlaceholders() {
    for (String invalid : List.of("{n%f}", "{n%d", "{n%}", "{n%ds}")) {
      assertThrows(IntegrityCheckException.class, () -> checker.check(invalid, invalid));
    }
  }
}
