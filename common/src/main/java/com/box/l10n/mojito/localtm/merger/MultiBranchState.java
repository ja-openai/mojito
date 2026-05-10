package com.box.l10n.mojito.localtm.merger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiBranchState(
    @JsonProperty("branchStateTextUnits") ImmutableList<BranchStateTextUnit> branchStateTextUnits,
    @JsonProperty("branches") ImmutableSet<Branch> branches) {

  public MultiBranchState {
    branchStateTextUnits =
        branchStateTextUnits == null
            ? ImmutableList.of()
            : ImmutableList.copyOf(branchStateTextUnits);
    branches = branches == null ? ImmutableSet.of() : ImmutableSet.copyOf(branches);
  }

  public static MultiBranchState of() {
    return new MultiBranchState(ImmutableList.of(), ImmutableSet.of());
  }

  public static Builder builder() {
    return new Builder();
  }

  public ImmutableList<BranchStateTextUnit> getBranchStateTextUnits() {
    return branchStateTextUnits;
  }

  public ImmutableSet<Branch> getBranches() {
    return branches;
  }

  public MultiBranchState withBranchStateTextUnits(
      ImmutableList<BranchStateTextUnit> branchStateTextUnits) {
    return new MultiBranchState(branchStateTextUnits, branches);
  }

  public MultiBranchState withBranches(ImmutableSet<Branch> branches) {
    return new MultiBranchState(branchStateTextUnits, branches);
  }

  public static class Builder {
    private ImmutableList<BranchStateTextUnit> branchStateTextUnits = ImmutableList.of();
    private ImmutableSet<Branch> branches = ImmutableSet.of();

    public Builder branchStateTextUnits(ImmutableList<BranchStateTextUnit> branchStateTextUnits) {
      this.branchStateTextUnits = Objects.requireNonNull(branchStateTextUnits);
      return this;
    }

    public Builder branches(ImmutableSet<Branch> branches) {
      this.branches = Objects.requireNonNull(branches);
      return this;
    }

    public MultiBranchState build() {
      return new MultiBranchState(branchStateTextUnits, branches);
    }
  }
}
