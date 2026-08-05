package com.box.l10n.mojito.service.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.service.monitoring.RedisMonitoringService.RedisSnapshot;
import com.box.l10n.mojito.service.redis.RedisConfigurationProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;

public class RedisMonitoringServiceTest {

  @SuppressWarnings("unchecked")
  private final ObjectProvider<RedisClient> redisClientProvider = mock(ObjectProvider.class);

  private final RedisClient redisClient = mock(RedisClient.class);

  @SuppressWarnings("unchecked")
  private final StatefulRedisConnection<String, String> connection =
      mock(StatefulRedisConnection.class);

  @SuppressWarnings("unchecked")
  private final RedisCommands<String, String> commands = mock(RedisCommands.class);

  private final RedisConfigurationProperties properties = new RedisConfigurationProperties();

  private final RedisMonitoringService service =
      new RedisMonitoringService(redisClientProvider, properties);

  @Test
  public void reportsDisabledRedisWithoutRequiringAClient() {
    RedisSnapshot snapshot = service.getStatus();

    assertThat(snapshot.enabled()).isFalse();
    assertThat(snapshot.status()).isEqualTo("NOT_CONFIGURED");
    assertThat(snapshot.endpoint()).isEqualTo("redis://localhost:6379");
    assertThat(snapshot.metrics()).isNull();
    assertThat(snapshot.checks()).isEmpty();
    verifyNoInteractions(redisClientProvider);
  }

  @Test
  public void reportsEnabledRedisWithoutAnAvailableClient() {
    properties.setEnabled(true);

    RedisSnapshot snapshot = service.getStatus();

    assertThat(snapshot.status()).isEqualTo("UNAVAILABLE");
    assertThat(snapshot.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.name()).isEqualTo("Redis client");
              assertThat(check.success()).isFalse();
            });
  }

  @Test
  public void reportsServerMetricsForTheConfiguredDatabase() {
    enableRedis();
    properties.setDatabase(3);
    when(commands.ping()).thenReturn("PONG");
    when(commands.info())
        .thenReturn(
            "# Server\r\nredis_version:7.4.5\r\nuptime_in_seconds:123\r\n"
                + "# Clients\r\nconnected_clients:4\r\n"
                + "# Memory\r\nused_memory:1048576\r\nused_memory_human:1.00M\r\n"
                + "maxmemory:2097152\r\n");
    when(commands.dbsize()).thenReturn(9L);

    RedisSnapshot snapshot = service.getStatus();

    assertThat(snapshot.status()).isEqualTo("READY");
    assertThat(snapshot.database()).isEqualTo(3);
    assertThat(snapshot.metrics().version()).isEqualTo("7.4.5");
    assertThat(snapshot.metrics().uptimeSeconds()).isEqualTo(123);
    assertThat(snapshot.metrics().usedMemoryBytes()).isEqualTo(1_048_576);
    assertThat(snapshot.metrics().usedMemoryHuman()).isEqualTo("1.00M");
    assertThat(snapshot.metrics().maxMemoryBytes()).isEqualTo(2_097_152);
    assertThat(snapshot.metrics().connectedClients()).isEqualTo(4);
    assertThat(snapshot.metrics().keyCount()).isEqualTo(9);
    assertThat(snapshot.checks()).extracting("name").containsExactly("PING", "Server info");
    verify(connection).close();
  }

  @Test
  public void reportsConnectionFailureWithoutThrowing() {
    enableRedis();
    when(redisClient.connect()).thenThrow(new IllegalStateException("unavailable"));

    RedisSnapshot snapshot = service.getStatus();

    assertThat(snapshot.status()).isEqualTo("UNAVAILABLE");
    assertThat(snapshot.checks())
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.name()).isEqualTo("Connection");
              assertThat(check.success()).isFalse();
              assertThat(check.message()).isEqualTo("IllegalStateException");
            });
  }

  @Test
  public void probesWriteReadAndDeleteWithAnExpiringKey() {
    enableRedis();
    when(commands.ping()).thenReturn("PONG");
    when(commands.info()).thenReturn("redis_version:7.4.5\r\n");
    when(commands.dbsize()).thenReturn(0L);
    when(commands.setex(anyString(), anyLong(), anyString())).thenReturn("OK");
    when(commands.get(anyString())).thenReturn("mojito-redis-monitoring");
    when(commands.del(anyString())).thenReturn(1L);

    RedisSnapshot snapshot = service.runProbe();

    assertThat(snapshot.status()).isEqualTo("READY");
    assertThat(snapshot.checks())
        .extracting("name")
        .containsExactly("PING", "Server info", "Write probe", "Read probe", "Delete probe");
    verify(commands).setex(anyString(), anyLong(), anyString());
    verify(commands).del(anyString());
  }

  @Test
  public void deletesTheProbeEvenWhenItsReadFails() {
    enableRedis();
    when(commands.ping()).thenReturn("PONG");
    when(commands.info()).thenReturn("redis_version:7.4.5\r\n");
    when(commands.dbsize()).thenReturn(0L);
    when(commands.setex(anyString(), anyLong(), anyString())).thenReturn("OK");
    when(commands.get(anyString())).thenReturn("unexpected content");
    when(commands.del(anyString())).thenReturn(1L);

    RedisSnapshot snapshot = service.runProbe();

    assertThat(snapshot.status()).isEqualTo("UNAVAILABLE");
    assertThat(snapshot.checks())
        .filteredOn(check -> check.name().equals("Read probe"))
        .singleElement()
        .satisfies(
            check -> {
              assertThat(check.success()).isFalse();
              assertThat(check.message()).isEqualTo("Redis returned an unexpected response");
            });
    verify(commands).del(anyString());
  }

  private void enableRedis() {
    properties.setEnabled(true);
    when(redisClientProvider.getIfAvailable()).thenReturn(redisClient);
    when(redisClient.connect()).thenReturn(connection);
    when(connection.sync()).thenReturn(commands);
  }
}
