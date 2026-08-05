package com.box.l10n.mojito.service.blobstorage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("l10n.blob-storage")
public class BlobStorageConfigurationProperties {

  private static final Logger logger =
      LoggerFactory.getLogger(BlobStorageConfigurationProperties.class);

  BlobStorageType defaultType;

  BlobStorageType type;

  Routing routing = new Routing();

  public BlobStorageType getDefaultType() {
    return defaultType != null ? defaultType : type != null ? type : BlobStorageType.DATABASE;
  }

  public void setDefaultType(BlobStorageType defaultType) {
    this.defaultType = defaultType;
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "l10n.blob-storage.default-type")
  public BlobStorageType getType() {
    return getDefaultType();
  }

  @Deprecated
  public void setType(BlobStorageType type) {
    this.type = type;
    logger.warn(
        "Configuration property 'l10n.blob-storage.type' is deprecated; use 'l10n.blob-storage.default-type' instead");
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
