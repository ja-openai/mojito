package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.rest.EntityWithIdNotFoundException;

/**
 * @author jeanaurambault
 */
public class TMTextUnitWithIdNotFoundException extends EntityWithIdNotFoundException {

  public TMTextUnitWithIdNotFoundException(Long id) {
    super("TMTextUnit", id);
  }
}
