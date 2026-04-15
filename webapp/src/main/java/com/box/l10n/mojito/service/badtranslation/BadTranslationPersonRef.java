package com.box.l10n.mojito.service.badtranslation;

public record BadTranslationPersonRef(
    Long userId, String username, String slackUserId, String slackMention) {}
