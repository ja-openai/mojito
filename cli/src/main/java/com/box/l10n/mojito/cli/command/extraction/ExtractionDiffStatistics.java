package com.box.l10n.mojito.cli.command.extraction;

import java.util.List;

public record ExtractionDiffStatistics(
    int added,
    int removed,
    int base,
    int current,
    List<String> addedStrings,
    List<String> removedStrings) {

  public ExtractionDiffStatistics {
    addedStrings = addedStrings == null ? List.of() : List.copyOf(addedStrings);
    removedStrings = removedStrings == null ? List.of() : List.copyOf(removedStrings);
  }

  public static Builder builder() {
    return new Builder();
  }

  public int getAdded() {
    return added;
  }

  public int getRemoved() {
    return removed;
  }

  public int getBase() {
    return base;
  }

  public int getCurrent() {
    return current;
  }

  public List<String> getAddedStrings() {
    return addedStrings;
  }

  public List<String> getRemovedStrings() {
    return removedStrings;
  }

  public ExtractionDiffStatistics withAdded(int added) {
    return new ExtractionDiffStatistics(
        added, removed, base, current, addedStrings, removedStrings);
  }

  public ExtractionDiffStatistics withRemoved(int removed) {
    return new ExtractionDiffStatistics(
        added, removed, base, current, addedStrings, removedStrings);
  }

  public ExtractionDiffStatistics withAddedStrings(List<String> addedStrings) {
    return new ExtractionDiffStatistics(
        added, removed, base, current, addedStrings, removedStrings);
  }

  public ExtractionDiffStatistics withRemovedStrings(List<String> removedStrings) {
    return new ExtractionDiffStatistics(
        added, removed, base, current, addedStrings, removedStrings);
  }

  public static class Builder {
    private int added;
    private int removed;
    private int base;
    private int current;
    private List<String> addedStrings = List.of();
    private List<String> removedStrings = List.of();

    public Builder added(int added) {
      this.added = added;
      return this;
    }

    public Builder removed(int removed) {
      this.removed = removed;
      return this;
    }

    public Builder base(int base) {
      this.base = base;
      return this;
    }

    public Builder current(int current) {
      this.current = current;
      return this;
    }

    public Builder addedStrings(List<String> addedStrings) {
      this.addedStrings = addedStrings;
      return this;
    }

    public Builder removedStrings(List<String> removedStrings) {
      this.removedStrings = removedStrings;
      return this;
    }

    public ExtractionDiffStatistics build() {
      return new ExtractionDiffStatistics(
          added, removed, base, current, addedStrings, removedStrings);
    }
  }
}
