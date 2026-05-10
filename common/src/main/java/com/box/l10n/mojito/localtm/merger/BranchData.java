package com.box.l10n.mojito.localtm.merger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BranchData(@JsonProperty("usages") ImmutableSet<String> usages) {

  public BranchData {
    usages = usages == null ? ImmutableSet.of() : ImmutableSet.copyOf(usages);
  }

  public static BranchData of() {
    return new BranchData(ImmutableSet.of());
  }

  public static Builder builder() {
    return new Builder();
  }

  public ImmutableSet<String> getUsages() {
    return usages;
  }

  public BranchData withUsages(ImmutableSet<String> usages) {
    return new BranchData(usages);
  }

  public static class Builder {
    private ImmutableSet<String> usages = ImmutableSet.of();

    public Builder usages(ImmutableSet<String> usages) {
      this.usages = Objects.requireNonNull(usages);
      return this;
    }

    public BranchData build() {
      return new BranchData(usages);
    }
  }
}
