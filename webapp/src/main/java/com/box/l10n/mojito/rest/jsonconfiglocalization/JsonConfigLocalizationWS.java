package com.box.l10n.mojito.rest.jsonconfiglocalization;

import com.box.l10n.mojito.service.asset.VirtualAssetBadRequestException;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.DetectMappingInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.DetectMappingResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExportResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractForRepositoryInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractForRepositoryResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractionInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationProcessorService.ExtractionResult;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationRunService;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationRunService.RunSummary;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalization;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalizationConflictException;
import com.box.l10n.mojito.service.jsonconfiglocalization.JsonConfigLocalizationService.JsonConfigLocalizationInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPullInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPushInput;
import com.box.l10n.mojito.service.jsonconfiglocalization.StatsigJsonConfigLocalizationService.StatsigPushResult;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/json-config-localizations")
public class JsonConfigLocalizationWS {

  private final JsonConfigLocalizationService jsonConfigLocalizationService;
  private final JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService;
  private final StatsigJsonConfigLocalizationService statsigJsonConfigLocalizationService;
  private final JsonConfigLocalizationRunService jsonConfigLocalizationRunService;

  public JsonConfigLocalizationWS(
      JsonConfigLocalizationService jsonConfigLocalizationService,
      JsonConfigLocalizationProcessorService jsonConfigLocalizationProcessorService,
      StatsigJsonConfigLocalizationService statsigJsonConfigLocalizationService,
      JsonConfigLocalizationRunService jsonConfigLocalizationRunService) {
    this.jsonConfigLocalizationService = jsonConfigLocalizationService;
    this.jsonConfigLocalizationProcessorService = jsonConfigLocalizationProcessorService;
    this.statsigJsonConfigLocalizationService = statsigJsonConfigLocalizationService;
    this.jsonConfigLocalizationRunService = jsonConfigLocalizationRunService;
  }

  @GetMapping
  @ResponseStatus(HttpStatus.OK)
  public List<JsonConfigLocalization> getAll() {
    try {
      return jsonConfigLocalizationService.getAll();
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @GetMapping("/repositories/{repositoryId}")
  @ResponseStatus(HttpStatus.OK)
  public JsonConfigLocalization getByRepositoryId(@PathVariable Long repositoryId) {
    try {
      return jsonConfigLocalizationService.getByRepositoryId(repositoryId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @GetMapping("/repositories/{repositoryId}/setups")
  @ResponseStatus(HttpStatus.OK)
  public List<JsonConfigLocalization> getAllByRepositoryId(@PathVariable Long repositoryId) {
    try {
      return jsonConfigLocalizationService.getAllByRepositoryId(repositoryId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @GetMapping("/setups/{setupId}")
  @ResponseStatus(HttpStatus.OK)
  public JsonConfigLocalization getById(@PathVariable Long setupId) {
    try {
      return jsonConfigLocalizationService.getById(setupId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @GetMapping("/setups/{setupId}/automation-runs")
  @ResponseStatus(HttpStatus.OK)
  public List<RunSummary> getAutomationRuns(@PathVariable Long setupId) {
    try {
      jsonConfigLocalizationService.getById(setupId);
      return jsonConfigLocalizationRunService.getRecentRuns(
          setupId, JsonConfigLocalizationRunService.DEFAULT_RECENT_RUN_LIMIT);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PutMapping("/repositories/{repositoryId}")
  @ResponseStatus(HttpStatus.OK)
  public JsonConfigLocalization upsertForRepository(
      @PathVariable Long repositoryId, @RequestBody JsonConfigLocalizationInput input) {
    try {
      return jsonConfigLocalizationService.upsertForRepository(repositoryId, input);
    } catch (JsonConfigLocalizationConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/repositories/{repositoryId}")
  @ResponseStatus(HttpStatus.CREATED)
  public JsonConfigLocalization createForRepository(
      @PathVariable Long repositoryId, @RequestBody JsonConfigLocalizationInput input) {
    try {
      return jsonConfigLocalizationService.createForRepository(repositoryId, input);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PutMapping("/setups/{setupId}")
  @ResponseStatus(HttpStatus.OK)
  public JsonConfigLocalization update(
      @PathVariable Long setupId, @RequestBody JsonConfigLocalizationInput input) {
    try {
      return jsonConfigLocalizationService.update(setupId, input);
    } catch (JsonConfigLocalizationConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @DeleteMapping("/repositories/{repositoryId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteForRepository(@PathVariable Long repositoryId) {
    try {
      jsonConfigLocalizationService.deleteForRepository(repositoryId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @DeleteMapping("/setups/{setupId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long setupId) {
    try {
      jsonConfigLocalizationService.delete(setupId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/detect-mapping")
  @ResponseStatus(HttpStatus.OK)
  public DetectMappingResult detectMapping(@RequestBody DetectMappingInput input) {
    try {
      return jsonConfigLocalizationProcessorService.detectMapping(input);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/extract")
  @ResponseStatus(HttpStatus.OK)
  public ExtractionResult extract(@RequestBody ExtractionInput input) {
    try {
      return jsonConfigLocalizationProcessorService.extract(input);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/repositories/{repositoryId}/extract")
  @ResponseStatus(HttpStatus.OK)
  public ExtractForRepositoryResult extractForRepository(
      @PathVariable Long repositoryId, @RequestBody ExtractForRepositoryInput input) {
    try {
      return jsonConfigLocalizationProcessorService.extractForRepository(repositoryId, input);
    } catch (JsonConfigLocalizationConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (VirtualAssetBadRequestException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/setups/{setupId}/extract")
  @ResponseStatus(HttpStatus.OK)
  public ExtractForRepositoryResult extractForSetup(
      @PathVariable Long setupId, @RequestBody ExtractForRepositoryInput input) {
    try {
      return jsonConfigLocalizationProcessorService.extractForSetup(setupId, input);
    } catch (JsonConfigLocalizationConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (VirtualAssetBadRequestException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @GetMapping("/repositories/{repositoryId}/export")
  @ResponseStatus(HttpStatus.OK)
  public ExportResult exportForRepository(@PathVariable Long repositoryId) {
    try {
      return jsonConfigLocalizationProcessorService.exportForRepository(repositoryId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @GetMapping("/setups/{setupId}/export")
  @ResponseStatus(HttpStatus.OK)
  public ExportResult exportForSetup(@PathVariable Long setupId) {
    try {
      return jsonConfigLocalizationProcessorService.exportForSetup(setupId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/repositories/{repositoryId}/statsig/pull")
  @ResponseStatus(HttpStatus.OK)
  public ExtractForRepositoryResult pullFromStatsig(
      @PathVariable Long repositoryId, @RequestBody StatsigPullInput input) {
    try {
      return statsigJsonConfigLocalizationService.pull(repositoryId, input);
    } catch (JsonConfigLocalizationConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (VirtualAssetBadRequestException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/setups/{setupId}/statsig/pull")
  @ResponseStatus(HttpStatus.OK)
  public ExtractForRepositoryResult pullFromStatsigForSetup(
      @PathVariable Long setupId, @RequestBody StatsigPullInput input) {
    try {
      return statsigJsonConfigLocalizationService.pullForSetup(setupId, input);
    } catch (JsonConfigLocalizationConflictException ex) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (VirtualAssetBadRequestException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/repositories/{repositoryId}/statsig/push")
  @ResponseStatus(HttpStatus.OK)
  public StatsigPushResult pushToStatsig(
      @PathVariable Long repositoryId, @RequestBody StatsigPushInput input) {
    try {
      return statsigJsonConfigLocalizationService.push(repositoryId, input);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  @PostMapping("/setups/{setupId}/statsig/push")
  @ResponseStatus(HttpStatus.OK)
  public StatsigPushResult pushToStatsigForSetup(
      @PathVariable Long setupId, @RequestBody StatsigPushInput input) {
    try {
      return statsigJsonConfigLocalizationService.pushForSetup(setupId, input);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (AccessDeniedException ex) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
    }
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    String message = ex.getMessage();
    if (message != null
        && (message.startsWith("Repository not found:")
            || message.startsWith("JSON config localization not found"))) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, message, ex);
    }
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message, ex);
  }
}
