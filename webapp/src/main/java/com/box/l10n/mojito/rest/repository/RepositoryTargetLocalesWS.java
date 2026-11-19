package com.box.l10n.mojito.rest.repository;

import com.box.l10n.mojito.service.repository.RepositoryLocaleCreationException;
import com.box.l10n.mojito.service.repository.RepositoryTargetLocalesService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/repositories/{repositoryId}/target-locales")
public class RepositoryTargetLocalesWS {

  private final RepositoryTargetLocalesService service;

  public RepositoryTargetLocalesWS(RepositoryTargetLocalesService service) {
    this.service = service;
  }

  @GetMapping
  public RepositoryTargetLocalesResponse getTargetLocales(@PathVariable Long repositoryId)
      throws RepositoryWithIdNotFoundException {
    return service.getTargetLocales(repositoryId);
  }

  @PutMapping
  public RepositoryTargetLocalesResponse updateTargetLocales(
      @PathVariable Long repositoryId, @RequestBody List<String> tags)
      throws RepositoryWithIdNotFoundException, RepositoryLocaleCreationException {
    return service.updateTargetLocales(repositoryId, tags);
  }

  @ExceptionHandler({IllegalArgumentException.class, RepositoryLocaleCreationException.class})
  public ResponseEntity<String> invalidTargetLocales(Exception exception) {
    return ResponseEntity.badRequest().body(exception.getMessage());
  }
}
