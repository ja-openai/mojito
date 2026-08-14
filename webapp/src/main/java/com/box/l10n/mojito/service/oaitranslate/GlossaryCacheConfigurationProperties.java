package com.box.l10n.mojito.service.oaitranslate;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.glossary.cache")
public class GlossaryCacheConfigurationProperties {

  private Duration ttl = Duration.ofMinutes(5);

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }
}
