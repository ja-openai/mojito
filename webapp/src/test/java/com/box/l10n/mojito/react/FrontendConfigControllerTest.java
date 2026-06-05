package com.box.l10n.mojito.react;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.Authority;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.rest.security.UserProfile;
import com.box.l10n.mojito.rest.security.UserProfileMapper;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.security.Role;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

/**
 * @author jaurambault
 */
public class FrontendConfigControllerTest {

  @Test
  public void testGetValidLocalFromCookie() {
    String localeCookieValue = "fr-FR";
    FrontendConfigController instance = new FrontendConfigController();

    String expResult = "fr-FR";
    String result = instance.getValidLocaleFromCookie(localeCookieValue);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetValidLocalFromCookieInvalidValue() {
    String localeCookieValue = "dsfsazfsdf dsfdsfsfdsf";
    FrontendConfigController instance = new FrontendConfigController();

    String expResult = "en";
    String result = instance.getValidLocaleFromCookie(localeCookieValue);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetValidLocalFromCookieNullValue() {
    String localeCookieValue = null;
    FrontendConfigController instance = new FrontendConfigController();

    String expResult = "en";
    String result = instance.getValidLocaleFromCookie(localeCookieValue);
    assertEquals(expResult, result);
  }

  @Test
  public void cmsDeliveryProfileIsNotExposedThroughPublicFrontendConfig() {
    FrontendConfigController instance = new FrontendConfigController();
    AuditorAwareImpl auditorAwareImpl = mock(AuditorAwareImpl.class);
    UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
    User cmsDeliveryUser = userWithRole(Role.ROLE_CMS_DELIVERY);
    when(auditorAwareImpl.getCurrentAuditor()).thenReturn(Optional.of(cmsDeliveryUser));
    instance.auditorAwareImpl = auditorAwareImpl;
    instance.userProfileMapper = userProfileMapper;

    UserProfile userProfile = instance.getUserProfile();

    assertNull(userProfile.getUsername());
    verifyNoInteractions(userProfileMapper);
  }

  private User userWithRole(Role role) {
    User user = new User();
    Authority authority = new Authority();
    authority.setUser(user);
    authority.setAuthority(role.name());
    user.setAuthorities(Set.of(authority));
    return user;
  }
}
