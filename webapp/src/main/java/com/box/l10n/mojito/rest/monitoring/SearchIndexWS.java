package com.box.l10n.mojito.rest.monitoring;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.searchindex.SearchIndexReindexJobService;
import com.box.l10n.mojito.service.searchindex.SearchIndexService;
import com.box.l10n.mojito.service.security.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/monitoring/search-index")
public class SearchIndexWS {

  private final SearchIndexService searchIndexService;
  private final SearchIndexReindexJobService searchIndexReindexJobService;
  private final UserService userService;

  public SearchIndexWS(
      SearchIndexService searchIndexService,
      SearchIndexReindexJobService searchIndexReindexJobService,
      UserService userService) {
    this.searchIndexService = searchIndexService;
    this.searchIndexReindexJobService = searchIndexReindexJobService;
    this.userService = userService;
  }

  @GetMapping
  public SearchIndexService.SearchIndexStatus getStatus() {
    assertCurrentUserIsAdmin();
    return searchIndexService.getStatus();
  }

  @PostMapping("/bootstrap")
  @ResponseStatus(HttpStatus.OK)
  public SearchIndexService.SearchIndexStatus bootstrapIndex() {
    assertCurrentUserIsAdmin();
    return searchIndexService.bootstrapIndex();
  }

  @PostMapping("/reindex")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartReindexResponse reindex(
      @RequestBody(required = false) SearchIndexService.SearchIndexReindexRequest request) {
    assertCurrentUserIsAdmin();
    return new StartReindexResponse(searchIndexReindexJobService.scheduleReindex(request));
  }

  @GetMapping("/reindex")
  public StartReindexResponse getActiveReindexTask() {
    assertCurrentUserIsAdmin();
    return new StartReindexResponse(
        searchIndexReindexJobService.getActiveReindexTask().orElse(null));
  }

  @PostMapping("/search")
  @ResponseStatus(HttpStatus.OK)
  public SearchIndexService.SearchIndexSearchResult search(
      @RequestBody SearchIndexService.SearchIndexSearchRequest request) {
    assertCurrentUserIsAdmin();
    return searchIndexService.search(request);
  }

  private void assertCurrentUserIsAdmin() {
    if (!userService.isCurrentUserAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
    }
  }

  public record StartReindexResponse(PollableTask pollableTask) {}
}
