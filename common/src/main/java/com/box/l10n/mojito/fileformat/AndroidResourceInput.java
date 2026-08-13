package com.box.l10n.mojito.fileformat;

/** One original Android resource file and its explicit Gradle source-set priority. */
public record AndroidResourceInput(String sourceSet, String resourcePath, byte[] source) {}
