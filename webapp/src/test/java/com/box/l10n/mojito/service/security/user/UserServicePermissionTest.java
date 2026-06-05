package com.box.l10n.mojito.service.security.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.Authority;
import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.security.Role;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;

public class UserServicePermissionTest {

  @Test
  public void pmCannotCreateCmsDeliveryUser() {
    UserService userService = userServiceForCurrentRole(Role.ROLE_PM);

    assertThatThrownBy(
            () -> userService.createUserWithRole("cms-delivery", "test", Role.ROLE_CMS_DELIVERY))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("admin-only users");
  }

  @Test
  public void pmCannotEditExistingCmsDeliveryUser() {
    UserService userService = userServiceForCurrentRole(Role.ROLE_PM);
    User cmsDeliveryUser = userWithRole("cms-delivery", Role.ROLE_CMS_DELIVERY);

    assertThatThrownBy(
            () ->
                userService.saveUserWithRole(
                    cmsDeliveryUser, null, Role.ROLE_USER, null, null, null, null, true, false))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("admin-only users");
  }

  @Test
  public void pmCannotDeleteExistingCmsDeliveryUser() {
    UserService userService = userServiceForCurrentRole(Role.ROLE_PM);
    User cmsDeliveryUser = userWithRole("cms-delivery", Role.ROLE_CMS_DELIVERY);

    assertThatThrownBy(() -> userService.deleteUser(cmsDeliveryUser))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("admin-only users");
  }

  @Test
  public void cmsDeliveryUserCannotCreateUser() {
    UserService userService = userServiceForCurrentRole(Role.ROLE_CMS_DELIVERY);

    assertThatThrownBy(() -> userService.createUserWithRole("user", "test", Role.ROLE_USER))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("CMS delivery users");
  }

  private UserService userServiceForCurrentRole(Role role) {
    UserService userService = new UserService();
    AuditorAwareImpl auditorAwareImpl = mock(AuditorAwareImpl.class);
    when(auditorAwareImpl.getCurrentAuditor())
        .thenReturn(Optional.of(userWithRole("current", role)));
    userService.auditorAwareImpl = auditorAwareImpl;
    return userService;
  }

  private User userWithRole(String username, Role role) {
    User user = new User();
    user.setUsername(username);
    Authority authority = new Authority();
    authority.setUser(user);
    authority.setAuthority(role.name());
    user.setAuthorities(Set.of(authority));
    return user;
  }
}
