package com.box.l10n.mojito.json;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.jackson")
public class JacksonConfigurationProperties {

  private int maxStringLength = 30_000_000;

  public int getMaxStringLength() {
    return maxStringLength;
  }

  public void setMaxStringLength(int maxStringLength) {
    this.maxStringLength = maxStringLength;
  }

  public void configure(ObjectMapper objectMapper) {
    objectMapper
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(maxStringLength).build());
  }
}
