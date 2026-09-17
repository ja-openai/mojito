package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.RepositoryLocale;
import com.box.l10n.mojito.service.repository.RepositoryLocaleRepository;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentService;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import com.box.l10n.mojito.service.security.user.UserService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Repository browsing reads saved TM state without creating projects, decisions, or draft rows. */
@Service
public class RepositoryContentService {
  static final int MAX_TARGET_CHARACTERS = 1_000_000;
  static final int MAX_TOTAL_TARGET_CHARACTERS = 5_000_000;
  static final int TARGET_QUERY_BATCH_SIZE = 20;

  private final UserService users;
  private final RepositoryRepository repositories;
  private final RepositoryLocaleRepository locales;
  private final RepositoryContentQueries queries;
  private final ReviewProjectDocumentService renderer;
  private final TransactionTemplate readTransaction;

  public RepositoryContentService(
      UserService users,
      RepositoryRepository repositories,
      RepositoryLocaleRepository locales,
      RepositoryContentQueries queries,
      ReviewProjectDocumentService renderer,
      PlatformTransactionManager transactions) {
    this.users = users;
    this.repositories = repositories;
    this.locales = locales;
    this.queries = queries;
    this.renderer = renderer;
    this.readTransaction = new TransactionTemplate(transactions);
    this.readTransaction.setReadOnly(true);
  }

  public RepositoryContentIndex list(
      Long repositoryId, Long branchId, String q, int offset, int limit) {
    return list(repositoryId, branchId, q, offset, limit, null, null, null, null, null);
  }

  public RepositoryContentIndex list(
      Long repositoryId,
      Long branchId,
      String q,
      int offset,
      int limit,
      String pagination,
      String after,
      String before,
      String searchMode,
      String directory) {
    return list(
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
        true);
  }

  /** All listings bound branch metadata; cursor mode also avoids OFFSET. */
  public RepositoryContentIndex list(
      Long repositoryId,
      Long branchId,
      String q,
      int offset,
      int limit,
      String pagination,
      String after,
      String before,
      String searchMode,
      String directory,
      boolean recursive) {
    authorize();
    boolean cursorMode = "cursor".equals(pagination);
    if ((pagination != null && !cursorMode && !"offset".equals(pagination))
        || (after != null && before != null)
        || (!cursorMode && (after != null || before != null))
        || (cursorMode && offset != 0)
        || offset < 0
        || limit < 1
        || limit > 100) {
      throw badRequest(
          "Use cursor pagination with either after or before, offset 0, and limit between 1 and 100");
    }
    var filter = filter(q, searchMode, directory, recursive);
    return readTransaction.execute(
        status -> {
          Repository repository = repository(repositoryId);
          var selected = selectBranch(repositoryId, branchId);
          var available = new ArrayList<>(queries.branches(repositoryId, 101));
          var warnings = new ArrayList<String>();
          if (available.size() > 100) {
            available.subList(100, available.size()).clear();
            warnings.add(
                "Showing the first 100 branches; an explicitly selected branch is also included.");
          }
          if (selected != null
              && available.stream().noneMatch(branch -> branch.id().equals(selected.id()))) {
            available.add(selected);
          }
          String scope = scope(repositoryId, selected == null ? null : selected.id(), filter);
          var position =
              RepositoryContentCursor.decode(after != null ? after : before, scope, false);
          boolean backward = before != null;
          List<ReviewProjectDocumentTemplate> found =
              selected == null
                  ? List.of()
                  : cursorMode
                      ? queries.seek(
                          repositoryId, selected.id(), filter, position, backward, limit + 1)
                      : queries.list(repositoryId, selected.id(), filter, offset, limit + 1);
          var page = new ArrayList<>(found.subList(0, Math.min(limit, found.size())));
          if (backward) Collections.reverse(page);
          boolean previous = false;
          boolean next = false;
          if (!page.isEmpty()) {
            var first =
                new RepositoryContentCursor(page.getFirst().assetPath(), page.getFirst().assetId());
            var last =
                new RepositoryContentCursor(page.getLast().assetPath(), page.getLast().assetId());
            previous =
                backward
                    ? found.size() > limit
                    : !queries.seek(repositoryId, selected.id(), filter, first, true, 1).isEmpty();
            next =
                !backward
                    ? found.size() > limit
                    : !queries.seek(repositoryId, selected.id(), filter, last, false, 1).isEmpty();
          }
          return new RepositoryContentIndex(
              repositoryId,
              repository.getSourceLocale().getBcp47Tag(),
              selected == null ? null : selected.id(),
              selected == null ? null : selected.name(),
              List.copyOf(available),
              page.stream()
                  .map(
                      template ->
                          new RepositoryContentIndex.Asset(
                              template.assetId(),
                              template.assetPath(),
                              template.branchId(),
                              template.branchName(),
                              template.contentMd5()))
                  .toList(),
              cursorMode ? 0 : offset,
              next,
              List.copyOf(warnings),
              next ? cursor(page.getLast(), scope) : null,
              previous ? cursor(page.getFirst(), scope) : null);
        });
  }

  public RepositoryContentDirectories directories(
      Long repositoryId, Long branchId, String directory, String after, int limit) {
    authorize();
    if (limit < 1 || limit > 50) throw badRequest("Use a directory limit between 1 and 50");
    String normalized = normalizeDirectory(directory);
    return readTransaction.execute(
        status -> {
          repository(repositoryId);
          var selected = selectBranch(repositoryId, branchId);
          String scope =
              RepositoryContentCursor.scope(
                  "directories", repositoryId, selected == null ? null : selected.id(), normalized);
          var cursor = RepositoryContentCursor.decode(after, scope, true);
          var found =
              selected == null
                  ? List.<String>of()
                  : queries.directories(
                      repositoryId,
                      selected.id(),
                      normalized,
                      cursor == null ? null : cursor.path(),
                      limit + 1);
          var page = found.subList(0, Math.min(limit, found.size()));
          return new RepositoryContentDirectories(
              repositoryId,
              selected == null ? null : selected.id(),
              normalized,
              List.copyOf(page),
              found.size() > limit
                  ? new RepositoryContentCursor(page.getLast(), 0).encode(scope)
                  : null);
        });
  }

  private static RepositoryContentQueries.Filter filter(
      String q, String searchMode, String directory, boolean recursive) {
    String mode = searchMode == null ? "contains" : searchMode;
    if (!Set.of("prefix", "exact", "contains").contains(mode) || (q != null && q.length() > 255)) {
      throw badRequest("Use prefix, exact, or contains path search with up to 255 characters");
    }
    String search = q == null ? "" : q;
    if (mode.equals("contains")) search = search.strip().toLowerCase(Locale.ROOT);
    return new RepositoryContentQueries.Filter(
        search, mode, normalizeDirectory(directory), recursive);
  }

  static String normalizeDirectory(String directory) {
    if (directory == null || directory.isEmpty()) return "";
    if (directory.length() > 255
        || directory.startsWith("/")
        || directory.contains("\\")
        || directory.chars().anyMatch(Character::isISOControl)) {
      throw badRequest("Use a relative directory path up to 255 characters");
    }
    String normalized = directory.replaceAll("/+", "/");
    for (String part : normalized.split("/")) {
      if (part.equals(".") || part.equals(".."))
        throw badRequest("Directory paths cannot contain . or .. segments");
    }
    if (!normalized.endsWith("/")) normalized += "/";
    if (normalized.length() > 255) throw badRequest("Use a directory path up to 255 characters");
    return normalized;
  }

  private static String scope(
      Long repositoryId, Long branchId, RepositoryContentQueries.Filter filter) {
    // Keep existing recursive cursors valid while separating direct-child listings.
    return RepositoryContentCursor.scope(
        filter.recursive() ? "assets" : "direct-assets",
        repositoryId,
        branchId,
        filter.searchMode(),
        filter.search(),
        filter.directory());
  }

  private static String cursor(ReviewProjectDocumentTemplate template, String scope) {
    return new RepositoryContentCursor(template.assetPath(), template.assetId()).encode(scope);
  }

  private RepositoryContentIndex.Branch selectBranch(Long repositoryId, Long branchId) {
    if (branchId != null) {
      var selected = queries.branch(repositoryId, branchId);
      if (selected == null) throw notFound("Branch not found in this repository");
      return selected;
    }
    var defaults = queries.defaultBranches(repositoryId);
    if (defaults.size() > 1)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Select an explicit branch ID; this repository has multiple unnamed branches");
    return defaults.isEmpty() ? null : defaults.getFirst();
  }

  public RepositoryContentPreview preview(
      Long repositoryId, Long assetId, Long branchId, String localeTag) {
    authorize();
    PreviewSnapshot snapshot =
        readTransaction.execute(
            status -> {
              Repository repository = repository(repositoryId);
              var branch = selectBranch(repositoryId, branchId);
              if (branch == null) throw notFound("Default branch not found");
              String sourceLocaleTag = repository.getSourceLocale().getBcp47Tag();
              String requestedLocale = localeTag == null ? sourceLocaleTag : localeTag;
              RepositoryLocale locale =
                  locales.findByRepositoryAndLocale_Bcp47Tag(repository, requestedLocale);
              if (locale == null) throw badRequest("Locale is not configured for this repository");
              ReviewProjectDocumentTemplate template =
                  queries.find(repositoryId, branch.id(), assetId);
              if (template == null
                  || !Objects.equals(template.repositoryId(), repositoryId)
                  || !Objects.equals(template.branchId(), branch.id())
                  || !Objects.equals(template.assetId(), assetId)) {
                throw notFound("MDX asset not found on this repository branch");
              }
              return new PreviewSnapshot(
                  template,
                  sourceLocaleTag,
                  locale.getLocale().getId(),
                  locale.getLocale().getBcp47Tag());
            });

    // The authorization/metadata transaction finishes before the shared renderer reads blobs.
    var rendered = renderer.renderRepositoryDocument(snapshot.template());
    List<String> warnings = new ArrayList<>(rendered.view().warnings());
    ReviewProjectDocumentView.Document document =
        rendered.view().documents().stream().findFirst().orElse(null);
    if (document != null) {
      Map<AssetName, List<RepositoryContentTextUnit>> textUnits =
          loadTextUnits(snapshot, rendered, document, warnings);
      TargetBudget budget = new TargetBudget();
      List<ReviewProjectDocumentView.Block> blocks =
          document.blocks().stream()
              .map(
                  block ->
                      withSavedTarget(
                          block,
                          textUnits,
                          snapshot.sourceLocaleTag().equals(snapshot.localeTag()),
                          budget))
              .toList();
      if (budget.exceeded)
        warnings.add(
            "Some translations exceeded the preview text size limit and remain read-only context.");
      document =
          new ReviewProjectDocumentView.Document(
              document.assetId(),
              document.assetPath(),
              document.repositoryId(),
              document.branchName(),
              document.sourceContentMd5(),
              blocks,
              document.warnings(),
              document.branchId(),
              snapshot.sourceLocaleTag());
    }
    return new RepositoryContentPreview(
        repositoryId,
        snapshot.template().branchId(),
        snapshot.template().branchName(),
        snapshot.sourceLocaleTag(),
        snapshot.localeTag(),
        document,
        List.copyOf(warnings));
  }

  private Map<AssetName, List<RepositoryContentTextUnit>> loadTextUnits(
      PreviewSnapshot snapshot,
      ReviewProjectDocumentService.RenderedDocuments rendered,
      ReviewProjectDocumentView.Document document,
      List<String> warnings) {
    Map<Long, Set<String>> namesByAsset = new HashMap<>();
    for (var block : document.blocks()) {
      if (block.translatable())
        namesByAsset
            .computeIfAbsent(block.assetId(), ignored -> new LinkedHashSet<>())
            .add(block.id());
    }
    Map<AssetName, List<RepositoryContentTextUnit>> result = new HashMap<>();
    int loadedCharacters = 0;
    boolean omitted = false;
    for (var template : rendered.templates()) {
      List<String> names = new ArrayList<>(namesByAsset.getOrDefault(template.assetId(), Set.of()));
      for (int start = 0; start < names.size(); start += TARGET_QUERY_BATCH_SIZE) {
        List<String> batch =
            names.subList(start, Math.min(names.size(), start + TARGET_QUERY_BATCH_SIZE));
        // Limit both SQL payloads and response amplification. A batch shares the remaining
        // character budget, so an adversarial saved translation cannot force an unbounded fetch.
        int quota =
            Math.min(
                MAX_TARGET_CHARACTERS,
                (MAX_TOTAL_TARGET_CHARACTERS - loadedCharacters) / batch.size());
        if (quota <= 0) {
          omitted = true;
          break;
        }
        var rows =
            readTransaction.execute(
                status -> queries.textUnits(template, snapshot.localeId(), batch, quota + 1));
        for (var row : rows) {
          int length = row.targetContent() == null ? 0 : row.targetContent().length();
          loadedCharacters += Math.min(length, MAX_TOTAL_TARGET_CHARACTERS - loadedCharacters);
          if (length > quota) {
            omitted = true;
            continue;
          }
          if (Objects.equals(row.assetId(), template.assetId()) && batch.contains(row.name())) {
            result
                .computeIfAbsent(
                    new AssetName(row.assetId(), row.name()), ignored -> new ArrayList<>())
                .add(row);
          }
        }
      }
    }
    if (omitted)
      warnings.add(
          "Some translations exceeded the preview text size limit and remain read-only context.");
    return result;
  }

  private ReviewProjectDocumentView.Block withSavedTarget(
      ReviewProjectDocumentView.Block block,
      Map<AssetName, List<RepositoryContentTextUnit>> rows,
      boolean sourceLocale,
      TargetBudget budget) {
    var candidates = rows.getOrDefault(new AssetName(block.assetId(), block.id()), List.of());
    var exact =
        candidates.stream().filter(row -> Objects.equals(row.source(), block.source())).toList();
    RepositoryContentTextUnit match =
        block.translatable() && exact.size() == 1 ? exact.getFirst() : null;
    MappingStatus mapping =
        !block.translatable()
            ? MappingStatus.CONTEXT
            : match != null
                ? MappingStatus.MATCHED
                : candidates.isEmpty() ? MappingStatus.NOT_FOUND : MappingStatus.SOURCE_CHANGED;
    String target = match == null ? null : sourceLocale ? block.source() : match.targetContent();
    if (target != null && target.length() > MAX_TOTAL_TARGET_CHARACTERS - budget.characters) {
      target = null;
      match = null;
      mapping = MappingStatus.NOT_FOUND;
      budget.exceeded = true;
    }
    if (target != null) budget.characters += target.length();
    return new ReviewProjectDocumentView.Block(
        block.id(),
        block.type(),
        block.depth(),
        block.source(),
        block.line(),
        block.translatable(),
        block.marker(),
        null,
        match == null ? null : match.tmTextUnitId(),
        mapping,
        block.assetId(),
        block.assetPath(),
        block.moduleDepth(),
        block.occurrenceId(),
        block.moduleStatus(),
        block.modulePath(),
        block.moduleWarning(),
        target,
        match == null || sourceLocale ? null : match.tmTextUnitVariantId(),
        match == null || sourceLocale ? null : match.targetStatus(),
        block.previewArgs());
  }

  private void authorize() {
    if (!users.isCurrentUserAdmin())
      throw new AccessDeniedException("Content browsing requires an administrator");
  }

  private Repository repository(Long id) {
    return repositories
        .findNoGraphById(id)
        .filter(repository -> !Boolean.TRUE.equals(repository.getDeleted()))
        .orElseThrow(() -> notFound("Repository not found"));
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record PreviewSnapshot(
      ReviewProjectDocumentTemplate template,
      String sourceLocaleTag,
      Long localeId,
      String localeTag) {}

  private record AssetName(Long assetId, String name) {}

  private static final class TargetBudget {
    int characters;
    boolean exceeded;
  }
}
