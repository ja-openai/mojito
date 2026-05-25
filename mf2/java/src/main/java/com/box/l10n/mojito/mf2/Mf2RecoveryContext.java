package com.box.l10n.mojito.mf2;

public record Mf2RecoveryContext(
        String code,
        String message,
        String locale,
        String variableName,
        String functionName,
        String sourceExpression,
        String fallbackValue,
        Mf2Exception error) {}
