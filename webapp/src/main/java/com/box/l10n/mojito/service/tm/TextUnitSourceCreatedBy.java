package com.box.l10n.mojito.service.tm;

public record TextUnitSourceCreatedBy(
    Long tmTextUnitId,
    Long userId,
    String username,
    String givenName,
    String surname,
    String commonName) {}
