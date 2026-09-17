package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.fileformat.MdxDocument;
import com.box.l10n.mojito.fileformat.MdxPreviewMessage;
import com.box.l10n.mojito.service.assetExtraction.AssetExtractionByBranchRepository;
import com.box.l10n.mojito.service.assetcontent.AssetContentBlobStorage;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.MappingStatus;
import com.box.l10n.mojito.service.review.ReviewProjectDocumentView.ModuleStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewProjectDocumentService {
  static final int MAX_DOCUMENTS = 100;
  static final int MAX_PROJECT_TEXT_UNITS = 2_000;
  static final int MAX_PROJECT_SOURCE_CHARACTERS = 10_000;
  static final int MAX_TEMPLATE_CHARACTERS = 1_000_000;
  static final int MAX_TOTAL_TEMPLATE_CHARACTERS = 5_000_000;
  static final int MAX_EMITTED_CHARACTERS = 5_000_000;
  static final int MAX_MODULE_DEPTH = 3;
  static final int MAX_MODULES = 100;
  static final int MAX_EXPANDED_BLOCKS = 2_000;
  static final int MAX_MODULE_PATH_CHARACTERS = 512;

  private final ReviewProjectRepository projects;
  private final ReviewProjectService projectService;
  private final ReviewProjectTextUnitRepository textUnits;
  private final AssetExtractionByBranchRepository templates;
  private final ReviewProjectDocumentTemplateCache templateCache;
  private final TransactionTemplate readTransaction;

  public ReviewProjectDocumentService(
      ReviewProjectRepository projects,
      ReviewProjectService projectService,
      ReviewProjectTextUnitRepository textUnits,
      AssetExtractionByBranchRepository templates,
      AssetContentBlobStorage blobs,
      PlatformTransactionManager transactionManager) {
    this.projects = projects;
    this.projectService = projectService;
    this.textUnits = textUnits;
    this.templates = templates;
    this.templateCache = new ReviewProjectDocumentTemplateCache(blobs);
    this.readTransaction = new TransactionTemplate(transactionManager);
    this.readTransaction.setReadOnly(true);
  }

  public ReviewProjectDocumentView getDocuments(Long projectId) {
    DocumentSnapshot snapshot = readTransaction.execute(status -> readSnapshot(projectId));
    if (snapshot.rows().isEmpty()) {
      return new ReviewProjectDocumentView(List.of(), snapshot.warnings());
    }
    Map<Long, List<ReviewProjectDocumentTextUnit>> rowsByAsset =
        snapshot.rows().stream()
            .collect(
                Collectors.groupingBy(
                    ReviewProjectDocumentTextUnit::assetId,
                    LinkedHashMap::new,
                    Collectors.toList()));
    var rendered = renderDocuments(rowsByAsset, snapshot.templates(), true).view();
    var warnings = new ArrayList<>(snapshot.warnings());
    warnings.addAll(rendered.warnings());
    return new ReviewProjectDocumentView(rendered.documents(), List.copyOf(warnings));
  }

  /** The caller must authorize the repository and validate this exact branch/asset first. */
  public RenderedDocuments renderRepositoryDocument(ReviewProjectDocumentTemplate template) {
    return renderDocuments(Map.of(), List.of(template), false);
  }

  public record RenderedDocuments(
      ReviewProjectDocumentView view, List<ReviewProjectDocumentTemplate> templates) {}

  private record DocumentSnapshot(
      List<ReviewProjectDocumentTextUnit> rows,
      List<ReviewProjectDocumentTemplate> templates,
      List<String> warnings) {}

  private DocumentSnapshot readSnapshot(Long projectId) {
    ReviewProject project =
        projects
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    projectService.assertCurrentUserCanReadProject(project);
    List<ReviewProjectDocumentTextUnit> found =
        textUnits.findDocumentTextUnitsByProjectId(
            projectId,
            MAX_PROJECT_SOURCE_CHARACTERS,
            PageRequest.of(0, MAX_PROJECT_TEXT_UNITS + 1));
    List<ReviewProjectDocumentTextUnit> rows =
        found.subList(0, Math.min(found.size(), MAX_PROJECT_TEXT_UNITS));
    List<String> warnings = new ArrayList<>();
    if (found.size() > MAX_PROJECT_TEXT_UNITS) {
      warnings.add(
          "Document preview includes only the first "
              + MAX_PROJECT_TEXT_UNITS
              + " project strings. Other strings remain available in the list view.");
    }
    if (rows.stream().anyMatch(row -> row.source() == null)) {
      warnings.add(
          "Project source strings longer than "
              + MAX_PROJECT_SOURCE_CHARACTERS
              + " characters remain read-only context in the preview and can be edited in the list view.");
    }
    return new DocumentSnapshot(
        rows,
        rows.isEmpty()
            ? List.of()
            : templates.findDocumentTemplatesByProjectId(
                projectId, PageRequest.of(0, MAX_DOCUMENTS + 1)),
        List.copyOf(warnings));
  }

  private RenderedDocuments renderDocuments(
      Map<Long, List<ReviewProjectDocumentTextUnit>> rowsByAsset,
      List<ReviewProjectDocumentTemplate> sources,
      boolean requireProjectRows) {
    List<String> warnings = new ArrayList<>();
    if (sources.size() > MAX_DOCUMENTS) {
      warnings.add("Only the first " + MAX_DOCUMENTS + " document templates are shown.");
    }
    ExpansionState state = new ExpansionState(rowsByAsset);
    List<ReviewProjectDocumentView.Document> documents = new ArrayList<>();
    Set<Long> shownAssets = new HashSet<>();
    for (ReviewProjectDocumentTemplate template : sources.stream().limit(MAX_DOCUMENTS).toList()) {
      List<ReviewProjectDocumentTextUnit> rows =
          rowsByAsset.getOrDefault(template.assetId(), List.of());
      if (requireProjectRows
          && (rows.isEmpty()
              || !Objects.equals(rows.getFirst().repositoryId(), template.repositoryId()))) {
        continue;
      }
      if (state.moduleCount >= MAX_MODULES
          || state.blockCount >= MAX_EXPANDED_BLOCKS
          || state.emittedCharacters >= MAX_EMITTED_CHARACTERS) {
        warnings.add("Document preview reached its size, module, or block limit.");
        break;
      }
      state.moduleCount++;
      ParsedTemplate loaded = state.load(template);
      if (loaded.document() == null) {
        warnings.add(template.assetPath() + ": " + loaded.warning());
        continue;
      }
      List<ReviewProjectDocumentView.Block> blocks = new ArrayList<>();
      Set<String> documentWarnings = new LinkedHashSet<>();
      Set<Long> matchedRows = new HashSet<>();
      Deque<Frame> work = new ArrayDeque<>();
      work.push(
          new Frame(
              template,
              loaded.document(),
              0,
              Set.of(template.assetId()),
              template.assetId() + ":" + template.branchId()));
      // Iterative depth-first traversal preserves document order. Budgets span the entire
      // response; only the current ancestry blocks cycles, so repeated sibling modules work.
      while (!work.isEmpty()) {
        Frame frame = work.peek();
        if (frame.nextBlock == frame.document.blocks().size()) {
          work.pop();
          continue;
        }
        if (state.blockCount >= MAX_EXPANDED_BLOCKS) {
          documentWarnings.add(
              "Document preview reached its " + MAX_EXPANDED_BLOCKS + " block limit.");
          break;
        }
        int index = frame.nextBlock++;
        MdxDocument.Block block = frame.document.blocks().get(index);
        if (block.source().length() > MAX_EMITTED_CHARACTERS - state.emittedCharacters) {
          documentWarnings.add("Document preview reached its rendered text size limit.");
          break;
        }
        state.emittedCharacters += block.source().length();
        String occurrence = frame.occurrence + "/" + index;
        ModuleExpansion module = state.expand(frame, block, occurrence);
        if (module != null && module.warning() != null) {
          documentWarnings.add(
              frame.template.assetPath() + ":" + block.line() + ": " + module.warning());
        }
        ReviewProjectDocumentView.Block view =
            "preview-message".equals(block.type())
                ? state.messageBlock(frame, block, occurrence)
                : state.toBlock(frame, block, occurrence, module);
        if ("preview-message".equals(block.type()) && view.moduleWarning() != null) {
          documentWarnings.add(
              frame.template.assetPath() + ":" + block.line() + ": " + view.moduleWarning());
        }
        blocks.add(view);
        state.blockCount++;
        shownAssets.add(view.assetId());
        if (view.reviewProjectTextUnitId() != null) {
          matchedRows.add(view.reviewProjectTextUnitId());
        }
        if (module != null && module.child() != null) {
          shownAssets.add(module.child().template.assetId());
          work.push(module.child());
        }
      }
      if (blocks.stream()
          .anyMatch(block -> block.mappingStatus() == MappingStatus.SOURCE_CHANGED)) {
        documentWarnings.add(
            "Some source segments have changed or map to more than one review row. They are shown as read-only context.");
      }
      if (rows.stream().anyMatch(row -> !matchedRows.contains(row.reviewProjectTextUnitId()))) {
        documentWarnings.add(
            "Some project strings are absent from this branch's current template. They remain available in the list view.");
      }
      documents.add(
          new ReviewProjectDocumentView.Document(
              template.assetId(),
              template.assetPath(),
              template.repositoryId(),
              template.branchName(),
              template.contentMd5(),
              blocks,
              List.copyOf(documentWarnings),
              template.branchId(),
              template.sourceLocaleTag()));
      shownAssets.add(template.assetId());
    }
    if (sources.size() <= MAX_DOCUMENTS) {
      Set<Long> candidateAssets =
          sources.stream().map(ReviewProjectDocumentTemplate::assetId).collect(Collectors.toSet());
      rowsByAsset.forEach(
          (assetId, rows) -> {
            if (!shownAssets.contains(assetId) && !candidateAssets.contains(assetId)) {
              warnings.add(
                  rows.getFirst().assetPath()
                      + (rows.getFirst().assetPath().endsWith(".mf2.json")
                          ? ": catalog strings without a page preview remain available in the list view."
                          : ": push the original MDX file to retain a document template."));
            }
          });
    }
    return new RenderedDocuments(
        new ReviewProjectDocumentView(documents, warnings),
        List.copyOf(state.loadedTemplates.values()));
  }

  private record ParsedTemplate(MdxDocument document, String warning, Map<String, String> catalog) {
    ParsedTemplate(MdxDocument document, String warning) {
      this(document, warning, null);
    }
  }

  private record TemplateKey(Long repositoryId, Long assetId, Long branchId, String contentMd5) {}

  private record ModuleKey(Long repositoryId, Long branchId, String assetPath) {}

  private record ModuleExpansion(ModuleStatus status, String path, String warning, Frame child) {
    static ModuleExpansion unavailable(String path, String warning) {
      return new ModuleExpansion(ModuleStatus.UNAVAILABLE, path, warning, null);
    }
  }

  private static final class Frame {
    final ReviewProjectDocumentTemplate template;
    final MdxDocument document;
    final int depth;
    final Set<Long> ancestors;
    final String occurrence;
    int nextBlock;

    Frame(
        ReviewProjectDocumentTemplate template,
        MdxDocument document,
        int depth,
        Set<Long> ancestors,
        String occurrence) {
      this.template = template;
      this.document = document;
      this.depth = depth;
      this.ancestors = ancestors;
      this.occurrence = occurrence;
    }
  }

  private final class ExpansionState {
    final Map<Long, Map<String, List<ReviewProjectDocumentTextUnit>>> rowsByAssetAndName =
        new HashMap<>();
    final Map<TemplateKey, ParsedTemplate> loaded = new HashMap<>();
    final Map<TemplateKey, ReviewProjectDocumentTemplate> loadedTemplates = new LinkedHashMap<>();
    final Map<ModuleKey, Optional<ReviewProjectDocumentTemplate>> resolved = new HashMap<>();
    int moduleCount;
    int blockCount;
    int loadedCharacters;
    int emittedCharacters;

    ExpansionState(Map<Long, List<ReviewProjectDocumentTextUnit>> rowsByAsset) {
      rowsByAsset.forEach(
          (assetId, rows) ->
              rowsByAssetAndName.put(
                  assetId,
                  rows.stream()
                      .collect(Collectors.groupingBy(ReviewProjectDocumentTextUnit::name))));
    }

    ParsedTemplate load(ReviewProjectDocumentTemplate template) {
      TemplateKey key =
          new TemplateKey(
              template.repositoryId(),
              template.assetId(),
              template.branchId(),
              template.contentMd5());
      return loaded.computeIfAbsent(
          key,
          ignored -> {
            if (template.contentMd5() == null) {
              return new ParsedTemplate(null, "source template is unavailable or out of date.");
            }
            if (loadedCharacters >= MAX_TOTAL_TEMPLATE_CHARACTERS) {
              return new ParsedTemplate(
                  null, "document preview reached its total source size limit.");
            }
            // The metadata transaction above (or below for includes) has finished before this read.
            var cached = templateCache.get(template);
            int remainingCharacters = MAX_TOTAL_TEMPLATE_CHARACTERS - loadedCharacters;
            // A cache hit still consumes this request's source budget.
            loadedCharacters += Math.min(cached.sourceCharacters(), remainingCharacters);
            if (cached.sourceCharacters() > remainingCharacters) {
              return new ParsedTemplate(
                  null, "document preview reached its total source size limit.");
            }
            if (cached.document() == null && cached.catalog() == null) {
              return new ParsedTemplate(null, cached.warning());
            }
            loadedTemplates.put(key, template);
            return new ParsedTemplate(cached.document(), null, cached.catalog());
          });
    }

    ModuleExpansion expand(Frame frame, MdxDocument.Block block, String occurrence) {
      MdxDocument.ComponentReference component = MdxDocument.componentReference(block);
      if (component == null
          || component.closing()
          || MdxDocument.PREVIEW_CHOICE.equals(component.name())) {
        return null;
      }
      String specifier = frame.document.imports().get(component.name());
      if (specifier != null && specifier.length() > MAX_MODULE_PATH_CHARACTERS) {
        return ModuleExpansion.unavailable(
            null, "Module path exceeds the " + MAX_MODULE_PATH_CHARACTERS + " character limit.");
      }
      if (!component.selfClosing()) {
        return ModuleExpansion.unavailable(
            specifier,
            "Paired components and React templates remain context; only self-closing MDX modules can be expanded.");
      }
      if (specifier == null) {
        return ModuleExpansion.unavailable(
            null,
            "This component remains context; module expansion requires a default relative MDX import.");
      }
      String path = resolveModulePath(frame.template.assetPath(), specifier);
      if (path == null) {
        return ModuleExpansion.unavailable(
            specifier, "Only relative .mdx imports within this repository can be expanded.");
      }
      if (frame.depth >= MAX_MODULE_DEPTH) {
        return ModuleExpansion.unavailable(
            path, "Module nesting is limited to " + MAX_MODULE_DEPTH + " levels.");
      }
      if (moduleCount >= MAX_MODULES || blockCount + 1 >= MAX_EXPANDED_BLOCKS) {
        return ModuleExpansion.unavailable(
            path, "Document preview reached its module or block limit.");
      }
      moduleCount++;
      ModuleKey key = new ModuleKey(frame.template.repositoryId(), frame.template.branchId(), path);
      // Workbench already permits authenticated source reads. The initial project ACL remains
      // mandatory; includes are restricted to its document's repository and exact branch. Only
      // rows actually belonging to the project receive editable IDs in toBlock below.
      Optional<ReviewProjectDocumentTemplate> reference =
          resolved.computeIfAbsent(
              key,
              ignored ->
                  readTransaction.execute(
                      status ->
                          templates.findDocumentTemplate(
                              key.repositoryId(), key.branchId(), key.assetPath())));
      if (reference.isEmpty()) {
        return ModuleExpansion.unavailable(
            path, "Module " + path + " is unavailable on this branch.");
      }
      ReviewProjectDocumentTemplate template = reference.get();
      if (!Objects.equals(template.repositoryId(), key.repositoryId())
          || !Objects.equals(template.branchId(), key.branchId())
          || !Objects.equals(template.assetPath(), key.assetPath())) {
        return ModuleExpansion.unavailable(
            path, "Module " + path + " is unavailable on this branch.");
      }
      if (frame.ancestors.contains(template.assetId())) {
        return ModuleExpansion.unavailable(path, "Module cycle detected for " + path + ".");
      }
      ParsedTemplate parsed;
      try {
        parsed = load(template);
      } catch (RuntimeException unavailable) {
        return ModuleExpansion.unavailable(
            path, "Module " + path + ": source template could not be loaded.");
      }
      if (parsed.document() == null) {
        return ModuleExpansion.unavailable(path, "Module " + path + ": " + parsed.warning());
      }
      Set<Long> ancestors = new HashSet<>(frame.ancestors);
      ancestors.add(template.assetId());
      return new ModuleExpansion(
          ModuleStatus.EXPANDED,
          path,
          null,
          new Frame(template, parsed.document(), frame.depth + 1, ancestors, occurrence));
    }

    ReviewProjectDocumentView.Block messageBlock(
        Frame frame, MdxDocument.Block block, String occurrence) {
      MdxPreviewMessage message = MdxPreviewMessage.parse(block.source());
      String path = resolveAssetPath(frame.template.assetPath(), message.resource(), ".mf2.json");
      String warning = null;
      ReviewProjectDocumentTemplate template = null;
      String pattern = null;
      if (path == null) {
        warning = "PreviewMessage requires a relative .mf2.json catalog within this repository.";
      } else if (moduleCount >= MAX_MODULES) {
        warning = "Document preview reached its module limit.";
      } else {
        moduleCount++;
        ModuleKey key =
            new ModuleKey(frame.template.repositoryId(), frame.template.branchId(), path);
        var reference =
            resolved.computeIfAbsent(
                key,
                ignored ->
                    readTransaction.execute(
                        status ->
                            templates.findDocumentTemplate(
                                key.repositoryId(), key.branchId(), key.assetPath())));
        if (reference.isEmpty()
            || !Objects.equals(reference.get().repositoryId(), key.repositoryId())
            || !Objects.equals(reference.get().branchId(), key.branchId())
            || !Objects.equals(reference.get().assetPath(), key.assetPath())) {
          warning = "Message catalog " + path + " is unavailable on this branch.";
        } else {
          template = reference.get();
          try {
            ParsedTemplate parsed = load(template);
            pattern = parsed.catalog() == null ? null : parsed.catalog().get(message.name());
            if (parsed.catalog() == null)
              warning = "Message catalog " + path + ": " + parsed.warning();
            else if (pattern == null)
              warning = "Message " + message.name() + " is unavailable in catalog " + path + ".";
          } catch (RuntimeException unavailable) {
            warning = "Message catalog " + path + " could not be loaded.";
          }
        }
      }
      if (warning == null && pattern.length() > MAX_EMITTED_CHARACTERS - emittedCharacters) {
        warning = "Document preview reached its rendered text size limit.";
      }
      if (warning != null)
        return toBlock(frame, block, occurrence, ModuleExpansion.unavailable(path, warning));
      emittedCharacters += pattern.length();
      MdxDocument.Block messageBlock =
          new MdxDocument.Block(
              message.name(), "mf2", block.depth(), pattern, block.line(), true, null);
      return toBlock(template, frame.depth, messageBlock, occurrence, null, message.args());
    }

    ReviewProjectDocumentView.Block toBlock(
        Frame frame, MdxDocument.Block block, String occurrence, ModuleExpansion module) {
      return toBlock(frame.template, frame.depth, block, occurrence, module, null);
    }

    ReviewProjectDocumentView.Block toBlock(
        ReviewProjectDocumentTemplate template,
        int moduleDepth,
        MdxDocument.Block block,
        String occurrence,
        ModuleExpansion module,
        Map<String, Object> previewArgs) {
      List<ReviewProjectDocumentTextUnit> candidates =
          block.id() == null
              ? List.of()
              : rowsByAssetAndName
                  .getOrDefault(template.assetId(), Map.of())
                  .getOrDefault(block.id(), List.of());
      List<ReviewProjectDocumentTextUnit> exact =
          candidates.stream()
              .filter(
                  row ->
                      Objects.equals(row.source(), block.source())
                          && Objects.equals(row.repositoryId(), template.repositoryId()))
              .toList();
      ReviewProjectDocumentTextUnit match = exact.size() == 1 ? exact.getFirst() : null;
      MappingStatus status =
          !block.translatable()
              ? MappingStatus.CONTEXT
              : match != null
                  ? MappingStatus.MATCHED
                  : candidates.isEmpty()
                      ? MappingStatus.NOT_IN_PROJECT
                      : MappingStatus.SOURCE_CHANGED;
      return new ReviewProjectDocumentView.Block(
          block.id(),
          block.type(),
          block.depth(),
          block.source(),
          block.line(),
          block.translatable(),
          block.marker(),
          match == null ? null : match.reviewProjectTextUnitId(),
          match == null ? null : match.tmTextUnitId(),
          status,
          template.assetId(),
          template.assetPath(),
          moduleDepth,
          occurrence,
          module == null ? null : module.status(),
          module == null ? null : module.path(),
          module == null ? null : module.warning(),
          null,
          null,
          null,
          previewArgs);
    }
  }

  /** Normalize repository-relative POSIX paths without accessing the host filesystem. */
  static String resolveModulePath(String importerPath, String specifier) {
    return resolveAssetPath(importerPath, specifier, ".mdx");
  }

  private static String resolveAssetPath(String importerPath, String specifier, String extension) {
    if (specifier == null
        || specifier.length() > MAX_MODULE_PATH_CHARACTERS
        || !(specifier.startsWith("./") || specifier.startsWith("../"))
        || !specifier.toLowerCase(Locale.ROOT).endsWith(extension)
        || specifier
            .chars()
            .anyMatch(
                value ->
                    value == '\\'
                        || value == '%'
                        || value == '#'
                        || value == '?'
                        || value == ':'
                        || Character.isISOControl(value))) {
      return null;
    }
    Deque<String> parts = new ArrayDeque<>();
    String combined =
        importerPath.substring(0, Math.max(0, importerPath.lastIndexOf('/') + 1)) + specifier;
    for (String part : combined.split("/")) {
      if (part.isEmpty() || ".".equals(part)) {
        continue;
      }
      if ("..".equals(part)) {
        if (parts.isEmpty()) {
          return null;
        }
        parts.removeLast();
      } else {
        parts.addLast(part);
      }
    }
    String path = String.join("/", parts);
    return path.length() > MAX_MODULE_PATH_CHARACTERS ? null : path;
  }
}
