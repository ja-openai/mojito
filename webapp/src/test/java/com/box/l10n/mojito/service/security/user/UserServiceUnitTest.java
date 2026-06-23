package com.box.l10n.mojito.service.security.user;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.security.user.User;
import org.junit.Test;
import org.springframework.dao.DataIntegrityViolationException;

public class UserServiceUnitTest {

  @Test
  public void partialUserCreationReloadsConcurrentWinner() {
    String username = "header-user";
    User concurrentUser = new User();
    UserService userService = spy(new UserService());
    UserRepository userRepository = mock(UserRepository.class);
    when(userRepository.findByUsername(username)).thenReturn(null, concurrentUser);
    doThrow(new DataIntegrityViolationException(User.USERNAME_UNIQUE_INDEX_NAME))
        .when(userService)
        .createBasicUser(username, null, null, null, true);
    userService.userRepository = userRepository;

    User user = userService.getOrCreatePartialBasicUser(username);

    assertSame(concurrentUser, user);
    verify(userRepository, times(2)).findByUsername(username);
  }

  @Test
  public void partialUserCreationDoesNotHideUnrelatedIntegrityViolation() {
    String username = "header-user";
    User concurrentUser = new User();
    UserService userService = spy(new UserService());
    UserRepository userRepository = mock(UserRepository.class);
    when(userRepository.findByUsername(username)).thenReturn(null, concurrentUser);
    doThrow(new DataIntegrityViolationException("other constraint"))
        .when(userService)
        .createBasicUser(username, null, null, null, true);
    userService.userRepository = userRepository;

    assertThatThrownBy(() -> userService.getOrCreatePartialBasicUser(username))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("other constraint");
  }
}
