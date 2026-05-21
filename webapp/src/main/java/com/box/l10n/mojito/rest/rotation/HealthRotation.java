package com.box.l10n.mojito.rest.rotation;

import jakarta.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * @author jaurambault
 */
@Component
public class HealthRotation implements HealthIndicator {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(HealthRotation.class);

  Boolean inRotation = true;

  String host;

  @Autowired
  @Qualifier("asyncExecutor")
  AsyncTaskExecutor asyncExecutor;

  @PostConstruct
  public void init() {
    host = getHost();
  }

  @Override
  public Health health() {
    Health.Builder builder;

    if (inRotation) {
      builder = Health.up();
    } else {
      builder = Health.down();
    }
    return builder.withDetail("host", host).build();
  }

  public void setInRotation(Boolean inRotation) {
    this.inRotation = inRotation;
  }

  String getHost() {
    String host;
    try {
      host = asyncExecutor.submit(this::getHostAsync).get(1, TimeUnit.SECONDS);
    } catch (Exception e) {
      host = "Can't get host";
    }
    return host;
  }

  String getHostAsync() throws UnknownHostException {
    return InetAddress.getLocalHost().getHostName();
  }
}
