package com.box.l10n.mojito.service.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.authx.TokenBasedRedisCredentialsProvider;
import io.lettuce.core.RedisCredentials;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.authentication.core.Token;

public class AzureRedisCredentialsProviderTest {

  private static final String OBJECT_ID = "aaaaaaaa-0000-1111-2222-bbbbbbbbbbbb";
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void convertsManagedIdentityTokenToRedisCredentials() {
    AccessToken accessToken = accessToken("{\"oid\":\"" + OBJECT_ID + "\"}");

    Token redisToken = AzureRedisCredentialsProvider.asRedisToken(accessToken, objectMapper);

    assertThat(redisToken.getUser()).isEqualTo(OBJECT_ID);
    assertThat(redisToken.getValue()).isEqualTo(accessToken.getToken());
    assertThat(redisToken.getExpiresAt())
        .isEqualTo(accessToken.getExpiresAt().toInstant().toEpochMilli());
    assertThat(redisToken.tryGet("oid", String.class)).isEqualTo(OBJECT_ID);
  }

  @Test
  public void requestsTheManagedRedisTokenScope() {
    TokenCredential credential = mock(TokenCredential.class);
    AccessToken accessToken = accessToken("{\"oid\":\"" + OBJECT_ID + "\"}");
    when(credential.getTokenSync(any(TokenRequestContext.class))).thenReturn(accessToken);

    try (TokenBasedRedisCredentialsProvider provider =
        AzureRedisCredentialsProvider.create(credential, objectMapper, Duration.ofSeconds(2))) {
      RedisCredentials credentials = provider.resolveCredentials().block(Duration.ofSeconds(5));

      assertThat(credentials).isNotNull();
      assertThat(credentials.getUsername()).isEqualTo(OBJECT_ID);

      ArgumentCaptor<TokenRequestContext> request =
          ArgumentCaptor.forClass(TokenRequestContext.class);
      verify(credential).getTokenSync(request.capture());
      assertThat(request.getValue().getScopes())
          .containsExactly(AzureRedisCredentialsProvider.REDIS_SCOPE);
    }
  }

  @Test
  public void rejectsManagedIdentityTokensWithoutAnObjectId() {
    AccessToken accessToken = accessToken("{\"sub\":\"missing-object-id\"}");

    assertThatThrownBy(() -> AzureRedisCredentialsProvider.asRedisToken(accessToken, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Azure Redis access token is missing its object ID");
  }

  private AccessToken accessToken(String claims) {
    String encodedClaims =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    return new AccessToken(
        "header." + encodedClaims + ".signature", OffsetDateTime.now().plusHours(1));
  }
}
