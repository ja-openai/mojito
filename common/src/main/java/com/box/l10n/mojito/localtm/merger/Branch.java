package com.box.l10n.mojito.localtm.merger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.ZonedDateTime;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Branch(
    @JsonProperty("name") String name, @JsonProperty("createdAt") ZonedDateTime createdAt) {

  public Branch {
    Objects.requireNonNull(name);
    Objects.requireNonNull(createdAt);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String getName() {
    return name;
  }

  public ZonedDateTime getCreatedAt() {
    return createdAt;
  }

  public static class Builder {
    private String name;
    private ZonedDateTime createdAt;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder createdAt(ZonedDateTime createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Branch build() {
      return new Branch(name, createdAt);
    }
  }
}
