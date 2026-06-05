package com.box.l10n.mojito.security;

/**
 * @author wyau
 */
public enum Role {
  /** Project Manager */
  ROLE_PM,

  /** Translator in Mojito */
  ROLE_TRANSLATOR,

  /** Administrator of Mojito. */
  ROLE_ADMIN,

  /** Read-only CMS snapshot delivery service account. */
  ROLE_CMS_DELIVERY,

  /**
   * User does not have much authorities. Any new user who is logging in for the first time will
   * have this role
   */
  ROLE_USER;
}
