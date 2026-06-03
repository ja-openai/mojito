package com.box.l10n.mojito.service.blobstorage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.blob-storage")
public class BlobStorageConfigurationProperties {

  BlobStorageType type = BlobStorageType.DATABASE;

  Routing routing = new Routing();

  public BlobStorageType getType() {
    return type;
  }

  public void setType(BlobStorageType type) {
    this.type = type;
  }

  public Routing getRouting() {
    return routing;
  }

  public void setRouting(Routing routing) {
    this.routing = routing;
  }

  public Optional<BlobStorageType> getStorageTypeForPrefix(StructuredBlobStorage.Prefix prefix) {
    String normalizedPrefix = normalize(prefix.name());
    return routing.getPrefixes().entrySet().stream()
        .filter(entry -> normalize(entry.getKey()).equals(normalizedPrefix))
        .map(Map.Entry::getValue)
        .findFirst();
  }

  static String normalize(String value) {
    return value.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
  }

  public static class Routing {

    Map<String, BlobStorageType> prefixes = new LinkedHashMap<>();

    public Map<String, BlobStorageType> getPrefixes() {
      return prefixes;
    }

    public void setPrefixes(Map<String, BlobStorageType> prefixes) {
      this.prefixes = prefixes;
    }
  }
}
