package com.box.l10n.mojito.rest.repository;

import com.box.l10n.mojito.service.content.RepositoryContentDirectories;
import com.box.l10n.mojito.service.content.RepositoryContentIndex;
import com.box.l10n.mojito.service.content.RepositoryContentPreview;
import com.box.l10n.mojito.service.content.RepositoryContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/content")
public class RepositoryContentWS {
  private final RepositoryContentService content;

  public RepositoryContentWS(RepositoryContentService content) {
    this.content = content;
  }

  @GetMapping
  public RepositoryContentIndex list(
      @PathVariable Long repositoryId,
      @RequestParam(required = false) Long branchId,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String pagination,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) String before,
      @RequestParam(required = false) String searchMode,
      @RequestParam(required = false) String directory,
      @RequestParam(defaultValue = "true") boolean recursive) {
    return content.list(
        repositoryId,
        branchId,
        q,
        offset,
        limit,
        pagination,
        after,
        before,
        searchMode,
        directory,
        recursive);
  }

  @GetMapping("/directories")
  public RepositoryContentDirectories directories(
      @PathVariable Long repositoryId,
      @RequestParam(required = false) Long branchId,
      @RequestParam(required = false) String directory,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "50") int limit) {
    return content.directories(repositoryId, branchId, directory, after, limit);
  }

  @GetMapping("/{assetId}")
  public RepositoryContentPreview preview(
      @PathVariable Long repositoryId,
      @PathVariable Long assetId,
      @RequestParam(required = false) Long branchId,
      @RequestParam(name = "locale", required = false) String localeTag) {
    return content.preview(repositoryId, assetId, branchId, localeTag);
  }
}
