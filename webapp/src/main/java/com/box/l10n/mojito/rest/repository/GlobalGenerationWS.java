package com.box.l10n.mojito.rest.repository;

import com.box.l10n.mojito.service.repository.GlobalGenerationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/generation-excluded-locales")
public class GlobalGenerationWS {

  private final GlobalGenerationService globalGenerationService;

  public GlobalGenerationWS(GlobalGenerationService globalGenerationService) {
    this.globalGenerationService = globalGenerationService;
  }

  @GetMapping
  public List<String> getExcludedLocaleTags() {
    return globalGenerationService.getExcludedLocaleTags();
  }

  @PutMapping
  public List<String> updateExcludedLocaleTags(@RequestBody List<String> localeTags) {
    try {
      return globalGenerationService.updateExcludedLocaleTags(localeTags);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }
}
