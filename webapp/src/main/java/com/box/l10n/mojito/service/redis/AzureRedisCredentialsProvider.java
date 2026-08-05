package com.box.l10n.mojito.service.redis;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.authx.TokenBasedRedisCredentialsProvider;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import redis.clients.authentication.core.IdentityProvider;
import redis.clients.authentication.core.SimpleToken;
import redis.clients.authentication.core.Token;
import redis.clients.authentication.core.TokenAuthConfig;

final class AzureRedisCredentialsProvider {

  static final String REDIS_SCOPE = "https://redis.azure.com/.default";
  private static final int REFRESH_BEFORE_EXPIRY_MILLIS = 180_000;

  private AzureRedisCredentialsProvider() {}

  static TokenBasedRedisCredentialsProvider create(
      TokenCredential credential, ObjectMapper objectMapper, Duration timeout) {
    IdentityProvider identityProvider =
        () -> {
          AccessToken accessToken =
              credential.getTokenSync(new TokenRequestContext().addScopes(REDIS_SCOPE));
          return asRedisToken(accessToken, objectMapper);
        };

    TokenAuthConfig tokenAuthConfig =
        TokenAuthConfig.builder()
            .identityProviderConfig(() -> identityProvider)
            .expirationRefreshRatio(0.8f)
            .lowerRefreshBoundMillis(REFRESH_BEFORE_EXPIRY_MILLIS)
            .tokenRequestExecTimeoutInMs(Math.toIntExact(timeout.toMillis()))
            .maxAttemptsToRetry(3)
            .delayInMsToRetry(1_000)
            .build();

    return TokenBasedRedisCredentialsProvider.create(tokenAuthConfig);
  }

  static Token asRedisToken(AccessToken accessToken, ObjectMapper objectMapper) {
    if (accessToken == null) {
      throw new IllegalStateException("Azure did not return a Redis access token");
    }

    String[] segments = accessToken.getToken().split("\\.");
    if (segments.length < 2) {
      throw new IllegalArgumentException("Azure Redis access token is not a JWT");
    }

    JsonNode claims;
    try {
      claims = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
    } catch (IllegalArgumentException | IOException exception) {
      throw new IllegalArgumentException("Azure Redis access token has invalid claims", exception);
    }

    String objectId = claims.path("oid").asText();
    if (objectId.isBlank()) {
      throw new IllegalArgumentException("Azure Redis access token is missing its object ID");
    }

    return new SimpleToken(
        objectId,
        accessToken.getToken(),
        accessToken.getExpiresAt().toInstant().toEpochMilli(),
        System.currentTimeMillis(),
        Map.of("oid", objectId));
  }
}
