package com.box.l10n.mojito;

import static com.box.l10n.mojito.CacheType.Names.DEFAULT;
import static org.junit.Assert.assertEquals;

import com.box.l10n.mojito.service.cache.CacheKey;
import com.box.l10n.mojito.service.cache.CacheService;
import org.junit.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * @author jaurambault
 */
public class CachingConfigTest {

  int i = 0;

  CacheService cacheService = new CacheService(new ConcurrentMapCacheManager(DEFAULT));

  @Test
  public void testCacheable() {
    assertEquals(1, getInt());
    assertEquals(1, getInt());
  }

  public int getInt() {
    return cacheService.get(
        DEFAULT, CacheKey.of(CachingConfigTest.class, "getInt"), this::getIntUncached);
  }

  public int getIntUncached() {
    return ++i;
  }
}
