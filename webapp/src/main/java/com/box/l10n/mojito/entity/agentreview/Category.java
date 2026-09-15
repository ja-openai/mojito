package com.box.l10n.mojito.entity.agentreview;

public enum Category {
  /** An incident awaiting human assessment; no error classification is asserted. */
  HUMAN_REVIEW,
  OBVIOUS_ERROR,
  CONSISTENCY_ERROR,
  OPTIONAL_IMPROVEMENT
}
