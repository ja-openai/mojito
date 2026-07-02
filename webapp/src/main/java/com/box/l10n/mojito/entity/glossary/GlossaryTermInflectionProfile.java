package com.box.l10n.mojito.entity.glossary;

import com.box.l10n.mojito.entity.AuditableEntity;
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
    name = "glossary_term_inflection_profile",
    indexes = {
      @Index(
          name = "UK__GLOSSARY_TERM_INFLECTION_PROFILE__TERM_LOCALE",
          columnList = "glossary_term_metadata_id, locale_tag",
          unique = true),
      @Index(
          name = "I__GLOSSARY_TERM_INFLECTION_PROFILE__TERM",
          columnList = "glossary_term_metadata_id"),
      @Index(
          name = "I__GLOSSARY_TERM_INFLECTION_PROFILE__LOCALE_STATUS",
          columnList = "locale_tag, status")
    })
public class GlossaryTermInflectionProfile extends AuditableEntity {

  public static final int LOCALE_TAG_MAX_LENGTH = 64;
  public static final int SCHEMA_MAX_LENGTH = 128;
  public static final int STATUS_MAX_LENGTH = 32;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "glossary_term_metadata_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "FK__GLOSSARY_TERM_INFLECTION_PROFILE__TERM_METADATA"))
  private GlossaryTermMetadata glossaryTermMetadata;

  @Column(name = "locale_tag", nullable = false, length = LOCALE_TAG_MAX_LENGTH)
  private String localeTag;

  @Column(name = "profile_schema", nullable = false, length = SCHEMA_MAX_LENGTH)
  private String schema;

  @Column(name = "status", nullable = false, length = STATUS_MAX_LENGTH)
  private String status;

  @Column(name = "morphology_json", nullable = false, length = Integer.MAX_VALUE)
  private String morphologyJson;

  @Column(name = "forms_json", nullable = false, length = Integer.MAX_VALUE)
  private String formsJson;

  @Column(name = "diagnostics_json", nullable = false, length = Integer.MAX_VALUE)
  private String diagnosticsJson;

  @Column(name = "provenance_json", nullable = false, length = Integer.MAX_VALUE)
  private String provenanceJson;

  public GlossaryTermMetadata getGlossaryTermMetadata() {
    return glossaryTermMetadata;
  }

  public void setGlossaryTermMetadata(GlossaryTermMetadata glossaryTermMetadata) {
    this.glossaryTermMetadata = glossaryTermMetadata;
  }

  public String getLocaleTag() {
    return localeTag;
  }

  public void setLocaleTag(String localeTag) {
    this.localeTag = localeTag;
  }

  public String getSchema() {
    return schema;
  }

  public void setSchema(String schema) {
    this.schema = schema;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getMorphologyJson() {
    return morphologyJson;
  }

  public void setMorphologyJson(String morphologyJson) {
    this.morphologyJson = morphologyJson;
  }

  public String getFormsJson() {
    return formsJson;
  }

  public void setFormsJson(String formsJson) {
    this.formsJson = formsJson;
  }

  public String getDiagnosticsJson() {
    return diagnosticsJson;
  }

  public void setDiagnosticsJson(String diagnosticsJson) {
    this.diagnosticsJson = diagnosticsJson;
  }

  public String getProvenanceJson() {
    return provenanceJson;
  }

  public void setProvenanceJson(String provenanceJson) {
    this.provenanceJson = provenanceJson;
  }
}
