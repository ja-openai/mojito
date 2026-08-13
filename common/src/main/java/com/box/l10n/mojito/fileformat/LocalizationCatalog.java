package com.box.l10n.mojito.fileformat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, FormatJS-compatible canonical catalog shared by all native converters. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LocalizationCatalog {

  private final LocalizationFileFormat sourceFormat;
  private final Map<String, LocalizationMessage> messages = new LinkedHashMap<>();
  private String locale;

  LocalizationCatalog(LocalizationFileFormat sourceFormat) {
    this.sourceFormat = sourceFormat;
  }

  @JsonProperty
  public int schemaVersion() {
    return 1;
  }

  @JsonProperty
  public String sourceFormat() {
    return sourceFormat.id();
  }

  @JsonProperty
  public String locale() {
    return locale;
  }

  @JsonProperty
  public Map<String, LocalizationMessage> messages() {
    return Collections.unmodifiableMap(messages);
  }

  void setLocale(String locale) {
    this.locale = locale;
  }

  void add(String id, LocalizationMessage message) {
    if (id == null || id.isBlank()) {
      throw new LocalizationParseException("INVALID_MESSAGE_ID", "Message ID must not be empty");
    }
    if (messages.putIfAbsent(id, message) != null) {
      throw new LocalizationParseException("DUPLICATE_MESSAGE_ID", "Duplicate message ID: " + id);
    }
  }
}
