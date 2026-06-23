package com.box.l10n.mojito.service.cms;

public class CmsContentNotFoundException extends IllegalArgumentException {

  public CmsContentNotFoundException(String message) {
    super(message);
  }
}
