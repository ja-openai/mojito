package com.box.l10n.mojito.service.cache;

import java.util.function.Supplier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Component
public class CacheService {

  final CacheManager cacheManager;

  public CacheService(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  @SuppressWarnings("unchecked")
  public <T> T get(String cacheName, Object key, Supplier<T> valueSupplier) {
    Cache cache = getCache(cacheName);
    Cache.ValueWrapper cachedValue = cache.get(key);

    if (cachedValue != null) {
      return (T) cachedValue.get();
    }

    T value = valueSupplier.get();
    cache.put(key, value);
    return value;
  }

  public void evict(String cacheName, Object key) {
    getCache(cacheName).evict(key);
  }

  private Cache getCache(String cacheName) {
    Cache cache = cacheManager.getCache(cacheName);
    if (cache == null) {
      throw new IllegalStateException("Cache not configured: " + cacheName);
    }
    return cache;
  }
}
