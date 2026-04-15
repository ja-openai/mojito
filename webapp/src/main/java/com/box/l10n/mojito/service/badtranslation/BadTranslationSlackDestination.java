package com.box.l10n.mojito.service.badtranslation;

public record BadTranslationSlackDestination(
    String source,
    String slackClientId,
    String slackChannelId,
    String threadTs,
    boolean canSend,
    String note) {}
