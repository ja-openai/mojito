package com.box.l10n.mojito.security;

import org.junit.Assert;
import org.junit.Test;

/**
 * @author jaurambault
 */
public class LdapConfigTest {

  @Test
  public void defaults() {
    LdapConfig ldapConfig = new LdapConfig();

    Assert.assertEquals("cn", ldapConfig.getGroupRoleAttribute());
    Assert.assertEquals("", ldapConfig.getGroupSearchBase());
    Assert.assertEquals("(uniqueMember={0})", ldapConfig.getGroupSearchFilter());
    Assert.assertNull(ldapConfig.getManagerDn());
    Assert.assertNull(ldapConfig.getManagerPassword());
    Assert.assertNull(ldapConfig.getPort());
    Assert.assertNull(ldapConfig.getRoot());
    Assert.assertNull(ldapConfig.getUrl());
    Assert.assertEquals("", ldapConfig.getUserSearchBase());
    Assert.assertNull(ldapConfig.getUserSearchFilter());
  }
}
