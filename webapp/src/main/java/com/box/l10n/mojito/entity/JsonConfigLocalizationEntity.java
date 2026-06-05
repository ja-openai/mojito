package com.box.l10n.mojito.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "json_config_localization",
    indexes = {
      @Index(name = "I__JSON_CONFIG_LOCALIZATION__REPOSITORY", columnList = "repository_id"),
      @Index(
          name = "UK__JSON_CONFIG_LOCALIZATION__REPOSITORY_NAME",
          columnList = "repository_id,name",
          unique = true),
      @Index(
          name = "UK__JSON_CONFIG_LOCALIZATION__REPOSITORY_ASSET_PATH",
          columnList = "repository_id,asset_path",
          unique = true),
      @Index(name = "I__JSON_CONFIG_LOCALIZATION__NAME", columnList = "name")
    })
public class JsonConfigLocalizationEntity extends AuditableEntity {

  public static final int NAME_MAX_LENGTH = 255;
  public static final int PROVIDER_MAX_LENGTH = 32;
  public static final int PROVIDER_CONFIG_ID_MAX_LENGTH = 255;
  public static final int ASSET_PATH_MAX_LENGTH = 255;
  public static final int AUTOMATION_CRON_EXPRESSION_MAX_LENGTH = 255;
  public static final int AUTOMATION_TIME_ZONE_MAX_LENGTH = 128;

  @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
  private String name;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "repository_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__JSON_CONFIG_LOCALIZATION__REPOSITORY"))
  private Repository repository;

  @Column(name = "asset_path", nullable = false, length = ASSET_PATH_MAX_LENGTH)
  private String assetPath;

  @Column(name = "provider", nullable = false, length = PROVIDER_MAX_LENGTH)
  private String provider;

  @Column(name = "provider_config_id", length = PROVIDER_CONFIG_ID_MAX_LENGTH)
  private String providerConfigId;

  @Column(name = "schema_json", length = Integer.MAX_VALUE)
  private String schemaJson;

  @Column(name = "source_config_json", length = Integer.MAX_VALUE)
  private String sourceConfigJson;

  @Column(name = "extraction_mapping_json", length = Integer.MAX_VALUE)
  private String extractionMappingJson;

  @Column(name = "output_locale_mapping_json", length = Integer.MAX_VALUE)
  private String outputLocaleMappingJson;

  @Column(name = "automation_enabled", nullable = false)
  private Boolean automationEnabled = false;

  @Column(name = "automation_cron_expression", length = AUTOMATION_CRON_EXPRESSION_MAX_LENGTH)
  private String automationCronExpression;

  @Column(name = "automation_time_zone", length = AUTOMATION_TIME_ZONE_MAX_LENGTH)
  private String automationTimeZone;

  @Column(name = "automation_options_json", length = Integer.MAX_VALUE)
  private String automationOptionsJson;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Repository getRepository() {
    return repository;
  }

  public void setRepository(Repository repository) {
    this.repository = repository;
  }

  public String getAssetPath() {
    return assetPath;
  }

  public void setAssetPath(String assetPath) {
    this.assetPath = assetPath;
  }

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getProviderConfigId() {
    return providerConfigId;
  }

  public void setProviderConfigId(String providerConfigId) {
    this.providerConfigId = providerConfigId;
  }

  public String getSchemaJson() {
    return schemaJson;
  }

  public void setSchemaJson(String schemaJson) {
    this.schemaJson = schemaJson;
  }

  public String getSourceConfigJson() {
    return sourceConfigJson;
  }

  public void setSourceConfigJson(String sourceConfigJson) {
    this.sourceConfigJson = sourceConfigJson;
  }

  public String getExtractionMappingJson() {
    return extractionMappingJson;
  }

  public void setExtractionMappingJson(String extractionMappingJson) {
    this.extractionMappingJson = extractionMappingJson;
  }

  public String getOutputLocaleMappingJson() {
    return outputLocaleMappingJson;
  }

  public void setOutputLocaleMappingJson(String outputLocaleMappingJson) {
    this.outputLocaleMappingJson = outputLocaleMappingJson;
  }

  public Boolean getAutomationEnabled() {
    return automationEnabled;
  }

  public void setAutomationEnabled(Boolean automationEnabled) {
    this.automationEnabled = automationEnabled;
  }

  public String getAutomationCronExpression() {
    return automationCronExpression;
  }

  public void setAutomationCronExpression(String automationCronExpression) {
    this.automationCronExpression = automationCronExpression;
  }

  public String getAutomationTimeZone() {
    return automationTimeZone;
  }

  public void setAutomationTimeZone(String automationTimeZone) {
    this.automationTimeZone = automationTimeZone;
  }

  public String getAutomationOptionsJson() {
    return automationOptionsJson;
  }

  public void setAutomationOptionsJson(String automationOptionsJson) {
    this.automationOptionsJson = automationOptionsJson;
  }
}
