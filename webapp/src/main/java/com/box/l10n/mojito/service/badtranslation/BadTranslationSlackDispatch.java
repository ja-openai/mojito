package com.box.l10n.mojito.service.badtranslation;

public record BadTranslationSlackDispatch(
    boolean requested, boolean sent, String messageTs, String threadTs, String note) {}
