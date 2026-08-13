package com.box.l10n.mojito.rest.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.searchindex.SearchIndexReindexJobService;
import com.box.l10n.mojito.service.searchindex.SearchIndexService;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RunWith(MockitoJUnitRunner.class)
public class SearchIndexWSTest {

  @Mock private SearchIndexService searchIndexService;
  @Mock private SearchIndexReindexJobService searchIndexReindexJobService;
  @Mock private UserService userService;

  private SearchIndexWS searchIndexWS;

  @Before
  public void setUp() {
    searchIndexWS =
        new SearchIndexWS(searchIndexService, searchIndexReindexJobService, userService);
  }

  @Test
  public void startsReindexingByReturningItsPollableTask() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    SearchIndexService.SearchIndexReindexRequest request =
        new SearchIndexService.SearchIndexReindexRequest(List.of(7L), 500, 200);
    PollableTask task = new PollableTask();
    task.setId(42L);
    when(searchIndexReindexJobService.scheduleReindex(request)).thenReturn(task);

    SearchIndexWS.StartReindexResponse response = searchIndexWS.reindex(request);

    assertThat(response.pollableTask()).isSameAs(task);
    verifyNoInteractions(searchIndexService);
  }

  @Test
  public void wrapsAnAbsentActiveTaskAsValidJson() {
    when(userService.isCurrentUserAdmin()).thenReturn(true);
    when(searchIndexReindexJobService.getActiveReindexTask()).thenReturn(Optional.empty());

    assertThat(searchIndexWS.getActiveReindexTask().pollableTask()).isNull();
  }

  @Test
  public void rejectsReindexingForNonAdministrators() {
    assertThatThrownBy(() -> searchIndexWS.reindex(null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    verify(userService).isCurrentUserAdmin();
    verifyNoInteractions(searchIndexService, searchIndexReindexJobService);
  }
}
