package com.box.l10n.mojito.rest.security;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.User;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.Optional;
import org.junit.Test;
import org.springframework.http.ResponseEntity;

public class UserWSUnitTest {

  @Test
  public void currentUserProfileUsesRehydratedCurrentUser() {
    UserWS instance = new UserWS();
    UserProfileMapper userProfileMapper = mock(UserProfileMapper.class);
    UserService userService = mock(UserService.class);
    User currentUser = new User();
    UserProfile expectedUserProfile = new UserProfile();
    when(userService.getCurrentUser()).thenReturn(Optional.of(currentUser));
    when(userProfileMapper.toUserProfile(currentUser)).thenReturn(expectedUserProfile);
    instance.userProfileMapper = userProfileMapper;
    instance.userService = userService;

    ResponseEntity<UserProfile> response = instance.getCurrentUser();

    assertSame(expectedUserProfile, response.getBody());
    verify(userProfileMapper).toUserProfile(currentUser);
  }
}
