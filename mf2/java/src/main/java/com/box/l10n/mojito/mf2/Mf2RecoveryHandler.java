package com.box.l10n.mojito.mf2;

@FunctionalInterface
public interface Mf2RecoveryHandler {
    String recover(Mf2RecoveryContext context);
}
