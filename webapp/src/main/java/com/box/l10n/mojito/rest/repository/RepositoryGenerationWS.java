package com.box.l10n.mojito.rest.repository;

import com.box.l10n.mojito.service.repository.RepositoryGenerationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/generation-excluded-locales")
public class RepositoryGenerationWS {

  private final RepositoryGenerationService repositoryGenerationService;

  public RepositoryGenerationWS(RepositoryGenerationService repositoryGenerationService) {
    this.repositoryGenerationService = repositoryGenerationService;
  }

  @GetMapping
  public List<String> getExcludedLocaleTags(@PathVariable Long repositoryId)
      throws RepositoryWithIdNotFoundException {
    return repositoryGenerationService.getExcludedLocaleTags(repositoryId);
  }

  @PutMapping
  public List<String> updateExcludedLocaleTags(
      @PathVariable Long repositoryId, @RequestBody List<String> localeTags)
      throws RepositoryWithIdNotFoundException {
    try {
      return repositoryGenerationService.updateExcludedLocaleTags(repositoryId, localeTags);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
