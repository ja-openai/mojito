package com.box.l10n.mojito.service.monitoring;

import com.box.l10n.mojito.service.redis.RedisConfigurationProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class RedisMonitoringService {

  private static final String PROBE_CONTENT = "mojito-redis-monitoring";
  private static final long PROBE_TTL_SECONDS = 60;

  private final ObjectProvider<RedisClient> redisClientProvider;
  private final RedisConfigurationProperties properties;

  public RedisMonitoringService(
      ObjectProvider<RedisClient> redisClientProvider, RedisConfigurationProperties properties) {
    this.redisClientProvider = redisClientProvider;
    this.properties = properties;
  }

  public RedisSnapshot getStatus() {
    return inspect(false);
  }

  public RedisSnapshot runProbe() {
    return inspect(true);
  }

  private RedisSnapshot inspect(boolean probe) {
    if (!properties.isEnabled()) {
      return snapshot("NOT_CONFIGURED", null, List.of());
    }

    RedisClient redisClient = redisClientProvider.getIfAvailable();
    if (redisClient == null) {
      return snapshot(
          "UNAVAILABLE",
          null,
          List.of(new RedisCheck("Redis client", false, 0, "Redis client is unavailable")));
    }

    List<RedisCheck> checks = new ArrayList<>();
    RedisMetrics metrics = null;
    try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
      RedisCommands<String, String> commands = connection.sync();
      RedisCheck pingCheck = runCheck("PING", () -> "PONG".equals(commands.ping()));
      checks.add(pingCheck);
      if (!pingCheck.success()) {
        return snapshot("UNAVAILABLE", null, checks);
      }

      AtomicReference<String> info = new AtomicReference<>();
      AtomicLong keyCount = new AtomicLong();
      RedisCheck infoCheck =
          runCheck(
              "Server info",
              () -> {
                info.set(commands.info());
                keyCount.set(commands.dbsize());
                return info.get() != null;
              });
      checks.add(infoCheck);
      if (!infoCheck.success()) {
        return snapshot("UNAVAILABLE", null, checks);
      }
      metrics = metrics(info.get(), keyCount.get());

      if (probe) {
        runProbe(commands, checks);
      }
    } catch (RuntimeException exception) {
      checks.add(new RedisCheck("Connection", false, 0, exception.getClass().getSimpleName()));
      return snapshot("UNAVAILABLE", metrics, checks);
    }

    String status = checks.stream().allMatch(RedisCheck::success) ? "READY" : "UNAVAILABLE";
    return snapshot(status, metrics, checks);
  }

  private void runProbe(RedisCommands<String, String> commands, List<RedisCheck> checks) {
    String probeKey = "mojito:_monitoring:" + UUID.randomUUID();
    boolean written = false;
    try {
      RedisCheck writeCheck =
          runCheck(
              "Write probe",
              () -> "OK".equals(commands.setex(probeKey, PROBE_TTL_SECONDS, PROBE_CONTENT)));
      checks.add(writeCheck);
      written = writeCheck.success();

      if (written) {
        checks.add(runCheck("Read probe", () -> PROBE_CONTENT.equals(commands.get(probeKey))));
      }
    } finally {
      if (written) {
        checks.add(runCheck("Delete probe", () -> commands.del(probeKey) == 1));
      }
    }
  }

  private RedisMetrics metrics(String info, long keyCount) {
    Map<String, String> values = new HashMap<>();
    for (String line : info.split("\\R")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] entry = line.split(":", 2);
      if (entry.length == 2) {
        values.put(entry[0], entry[1]);
      }
    }

    return new RedisMetrics(
        values.get("redis_version"),
        parseLong(values.get("uptime_in_seconds")),
        parseLong(values.get("used_memory")),
        values.get("used_memory_human"),
        parseLong(values.get("maxmemory")),
        parseLong(values.get("connected_clients")),
        keyCount);
  }

  private Long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private RedisCheck runCheck(String name, CheckOperation operation) {
    long startNanos = System.nanoTime();
    try {
      boolean success = operation.run();
      return new RedisCheck(
          name,
          success,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
          success ? null : "Redis returned an unexpected response");
    } catch (RuntimeException exception) {
      return new RedisCheck(
          name,
          false,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
          exception.getClass().getSimpleName());
    }
  }

  private RedisSnapshot snapshot(String status, RedisMetrics metrics, List<RedisCheck> checks) {
    String scheme = properties.isSsl() ? "rediss" : "redis";
    return new RedisSnapshot(
        Instant.now(),
        properties.isEnabled(),
        status,
        scheme + "://" + properties.getHost() + ":" + properties.getPort(),
        properties.getDatabase(),
        properties.isSsl(),
        metrics,
        checks);
  }

  @FunctionalInterface
  interface CheckOperation {
    boolean run();
  }

  public record RedisSnapshot(
      Instant timestamp,
      boolean enabled,
      String status,
      String endpoint,
      int database,
      boolean ssl,
      RedisMetrics metrics,
      List<RedisCheck> checks) {}

  public record RedisMetrics(
      String version,
      Long uptimeSeconds,
      Long usedMemoryBytes,
      String usedMemoryHuman,
      Long maxMemoryBytes,
      Long connectedClients,
      long keyCount) {}

  public record RedisCheck(String name, boolean success, long latencyMs, String message) {}
}
