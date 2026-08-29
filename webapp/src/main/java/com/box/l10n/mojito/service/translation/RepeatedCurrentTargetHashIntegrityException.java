package com.box.l10n.mojito.service.translation;

/** Raised when stored target hashes cannot safely prefilter an exact-target scan. */
public class RepeatedCurrentTargetHashIntegrityException extends RuntimeException {

  private final long invalidCurrentTargetHashCount;

  public RepeatedCurrentTargetHashIntegrityException(long invalidCurrentTargetHashCount) {
    super(
        "Cannot run a complete repeated-target scan: "
            + invalidCurrentTargetHashCount
            + " current targets have a missing or stale content_md5");
    this.invalidCurrentTargetHashCount = invalidCurrentTargetHashCount;
  }

  public long getInvalidCurrentTargetHashCount() {
    return invalidCurrentTargetHashCount;
  }
}
