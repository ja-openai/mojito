package com.box.l10n.mojito.entity.agentreview;

public enum Readiness {
  READY,
  /** Human intake without an agent verdict or independently verified suggestion. */
  HUMAN_REVIEW,
  HOLD,
  OPTIONAL
}
