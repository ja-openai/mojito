package com.box.l10n.mojito.service.tm;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LocaleGenerationExcludedException extends RuntimeException {

  public LocaleGenerationExcludedException(String localeTag) {
    super(
        "Locale " + localeTag + " is excluded from localized file generation for this repository");
  }
}
