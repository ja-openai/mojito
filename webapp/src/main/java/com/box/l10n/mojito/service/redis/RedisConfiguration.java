package com.box.l10n.mojito.service.redis;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import io.lettuce.authx.TokenBasedRedisCredentialsProvider;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty("l10n.redis.enabled")
public class RedisConfiguration {

  @Bean(destroyMethod = "shutdown")
  public RedisClient redisClient(
      RedisConfigurationProperties properties,
      ObjectProvider<TokenBasedRedisCredentialsProvider> managedIdentityCredentialsProvider) {
    RedisURI.Builder builder =
        RedisURI.Builder.redis(properties.getHost(), properties.getPort())
            .withDatabase(properties.getDatabase())
            .withSsl(properties.isSsl())
            .withTimeout(properties.getTimeout());

    if (properties.isManagedIdentity()) {
      builder.withAuthentication(managedIdentityCredentialsProvider.getObject());
    } else if (!Strings.isNullOrEmpty(properties.getPassword())) {
      if (Strings.isNullOrEmpty(properties.getUsername())) {
        builder.withPassword(properties.getPassword());
      } else {
        builder.withAuthentication(properties.getUsername(), properties.getPassword());
      }
    }

    RedisClient redisClient = RedisClient.create(builder.build());
    if (properties.isManagedIdentity()) {
      redisClient.setOptions(
          ClientOptions.builder()
              .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
              .build());
    }
    return redisClient;
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty("l10n.redis.managed-identity")
  public TokenBasedRedisCredentialsProvider managedIdentityRedisCredentialsProvider(
      RedisConfigurationProperties properties, ObjectMapper objectMapper) {
    return AzureRedisCredentialsProvider.create(
        new DefaultAzureCredentialBuilder().build(), objectMapper, properties.getTimeout());
  }
}
