package com.box.l10n.mojito.rest.stringauthoring;

import com.box.l10n.mojito.okapi.asset.UnsupportedAssetFilterTypeException;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.BranchScope;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringAsset;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringBranch;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringDeleteBranchResult;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringSaveRequest;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringSaveResult;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringStringRow;
import com.box.l10n.mojito.service.stringauthoring.StringAuthoringService.StringAuthoringUsedFilter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/string-authoring")
public class StringAuthoringWS {

  private final StringAuthoringService stringAuthoringService;

  public StringAuthoringWS(StringAuthoringService stringAuthoringService) {
    this.stringAuthoringService = stringAuthoringService;
  }

  @GetMapping("/repositories/{repositoryId}/assets")
  @ResponseStatus(HttpStatus.OK)
  public List<StringAuthoringAsset> getAssets(
      @PathVariable Long repositoryId,
      @RequestParam(value = "search", required = false) String search,
      @RequestParam(value = "limit", required = false) Integer limit) {
    try {
      return stringAuthoringService.getAssets(repositoryId, search, limit);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @GetMapping("/repositories/{repositoryId}/branches")
  @ResponseStatus(HttpStatus.OK)
  public List<StringAuthoringBranch> getBranches(
      @PathVariable Long repositoryId,
      @RequestParam(value = "scope", required = false, defaultValue = "AUTHORING")
          BranchScope scope) {
    try {
      return stringAuthoringService.getBranches(repositoryId, scope);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @DeleteMapping("/repositories/{repositoryId}/branches/{branchId}")
  @ResponseStatus(HttpStatus.OK)
  public StringAuthoringDeleteBranchResult deleteBranch(
      @PathVariable Long repositoryId, @PathVariable Long branchId) {
    try {
      return stringAuthoringService.deleteBranch(repositoryId, branchId);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  @PostMapping("/repositories/{repositoryId}/strings")
  @ResponseStatus(HttpStatus.OK)
  public StringAuthoringSaveResult saveStrings(
      @PathVariable Long repositoryId, @RequestBody StringAuthoringSaveRequest request) {
    try {
      return stringAuthoringService.saveStrings(repositoryId, request);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    } catch (ExecutionException ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save source strings", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted while saving source strings", ex);
    } catch (UnsupportedAssetFilterTypeException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
    }
  }

  @GetMapping("/repositories/{repositoryId}/strings")
  @ResponseStatus(HttpStatus.OK)
  public List<StringAuthoringStringRow> getStrings(
      @PathVariable Long repositoryId,
      @RequestParam(value = "branchName") String branchName,
      @RequestParam(value = "assetPath", required = false) String assetPath,
      @RequestParam(value = "usedFilter", required = false, defaultValue = "USED")
          StringAuthoringUsedFilter usedFilter,
      @RequestParam(value = "limit", required = false) Integer limit) {
    try {
      return stringAuthoringService.getStrings(
          repositoryId, branchName, assetPath, usedFilter, limit);
    } catch (IllegalArgumentException ex) {
      throw toStatusException(ex);
    }
  }

  private ResponseStatusException toStatusException(IllegalArgumentException ex) {
    HttpStatus status =
        ex.getMessage() != null && ex.getMessage().startsWith("Repository not found")
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
    return new ResponseStatusException(status, ex.getMessage(), ex);
  }
}
