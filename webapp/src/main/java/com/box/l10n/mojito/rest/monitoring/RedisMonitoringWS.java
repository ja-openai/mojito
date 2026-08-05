package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.service.monitoring.RedisMonitoringService;
import com.box.l10n.mojito.service.monitoring.RedisMonitoringService.RedisSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitoring/redis")
public class RedisMonitoringWS {

  private final RedisMonitoringService redisMonitoringService;

  public RedisMonitoringWS(RedisMonitoringService redisMonitoringService) {
    this.redisMonitoringService = redisMonitoringService;
  }

  @GetMapping
  public RedisSnapshot getStatus() {
    return redisMonitoringService.getStatus();
  }

  @PostMapping("/probe")
  public RedisSnapshot runProbe() {
    return redisMonitoringService.runProbe();
  }
}
