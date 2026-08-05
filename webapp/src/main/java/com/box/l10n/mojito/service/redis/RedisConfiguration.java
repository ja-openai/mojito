package com.box.l10n.mojito.service.redis;

import com.google.common.base.Strings;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("l10n.redis.enabled")
public class RedisConfiguration {

  @Bean(destroyMethod = "shutdown")
  public RedisClient redisClient(RedisConfigurationProperties properties) {
    RedisURI.Builder builder =
        RedisURI.Builder.redis(properties.getHost(), properties.getPort())
            .withDatabase(properties.getDatabase())
            .withSsl(properties.isSsl())
            .withTimeout(properties.getTimeout());

    if (!Strings.isNullOrEmpty(properties.getPassword())) {
      if (Strings.isNullOrEmpty(properties.getUsername())) {
        builder.withPassword(properties.getPassword());
      } else {
        builder.withAuthentication(properties.getUsername(), properties.getPassword());
      }
    }

    return RedisClient.create(builder.build());
  }
}
