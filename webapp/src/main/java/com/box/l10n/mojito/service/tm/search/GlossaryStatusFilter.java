package com.box.l10n.mojito.service.tm.search;

/** Filters text units by glossary term lifecycle status when glossary metadata is present. */
public enum GlossaryStatusFilter {
  ALL,
  APPROVED,
  CANDIDATE,
  REJECTED,
  DEPRECATED
}
