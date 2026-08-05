package com.box.l10n.mojito.service.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.redis")
public class RedisConfigurationProperties {

  private boolean enabled;
  private String host = "localhost";
  private int port = 6379;
  private int database;
  private String username;
  private String password;
  private boolean ssl;
  private boolean managedIdentity;
  private Duration timeout = Duration.ofSeconds(2);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getHost() {
    return host;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public int getDatabase() {
    return database;
  }

  public void setDatabase(int database) {
    this.database = database;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public boolean isSsl() {
    return ssl;
  }

  public void setSsl(boolean ssl) {
    this.ssl = ssl;
  }

  public boolean isManagedIdentity() {
    return managedIdentity;
  }

  public void setManagedIdentity(boolean managedIdentity) {
    this.managedIdentity = managedIdentity;
  }

  public Duration getTimeout() {
    return timeout;
  }

  public void setTimeout(Duration timeout) {
    this.timeout = timeout;
  }
}
