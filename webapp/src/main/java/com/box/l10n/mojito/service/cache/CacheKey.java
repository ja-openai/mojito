package com.box.l10n.mojito.service.cache;

import org.springframework.cache.interceptor.SimpleKeyGenerator;

public class CacheKey {

  public static Object of(Class<?> targetClass, String methodName, Object... params) {
    return SimpleKeyGenerator.generateKey(targetClass.getName() + "." + methodName, params);
  }
}
