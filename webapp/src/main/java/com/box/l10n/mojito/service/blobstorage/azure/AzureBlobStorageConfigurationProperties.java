package com.box.l10n.mojito.service.blobstorage.azure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.blob-storage.azure")
public class AzureBlobStorageConfigurationProperties {

  String prefix = "mojito";

  public String getPrefix() {
    return prefix;
  }

  public void setPrefix(String prefix) {
    this.prefix = prefix;
  }
}
