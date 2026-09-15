package com.box.l10n.mojito.entity.agentreview;

/** Whether completed findings immediately create projects or await an incident batch. */
public enum RoutingPolicy {
  IMMEDIATE,
  QUEUED
}
