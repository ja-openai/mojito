package com.box.l10n.mojito.rest.repository;

import com.box.l10n.mojito.service.content.ContentTranslationCandidate;
import com.box.l10n.mojito.service.content.ContentTranslationCandidateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/content/translation-candidates")
public class RepositoryContentCandidateWS {
  private final ContentTranslationCandidateService candidates;

  public RepositoryContentCandidateWS(ContentTranslationCandidateService candidates) {
    this.candidates = candidates;
  }

  @GetMapping("/source")
  public ContentTranslationCandidate.Source source(
      @PathVariable Long repositoryId, @RequestParam Long branchId, @RequestParam Long assetId) {
    return candidates.source(repositoryId, branchId, assetId);
  }

  @PostMapping
  public ContentTranslationCandidate.Result create(
      @PathVariable Long repositoryId, @RequestBody ContentTranslationCandidate candidate) {
    return candidates.create(repositoryId, candidate);
  }
}
