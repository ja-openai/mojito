package com.box.l10n.mojito.service.glossary;

import static com.box.l10n.mojito.entity.TMTextUnitVariant.Status.APPROVED;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.entity.glossary.Glossary;
import com.box.l10n.mojito.entity.glossary.GlossaryTermMetadata;
import com.box.l10n.mojito.service.asset.VirtualAssetRequiredException;
import com.box.l10n.mojito.service.asset.VirtualAssetTextUnit;
import com.box.l10n.mojito.service.asset.VirtualTextUnitBatchUpdaterService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import com.box.l10n.mojito.service.tm.importer.TextUnitBatchImporterService;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import com.box.l10n.mojito.service.tm.search.UsedFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlossaryImportExportService {

  private static final int EXPORT_LIMIT = 10_000;
  private static final String FORMAT_JSON = "json";
  private static final String FORMAT_CSV = "csv";

  private final GlossaryRepository glossaryRepository;
  private final GlossaryStorageService glossaryStorageService;
  private final TextUnitSearcher textUnitSearcher;
  private final GlossaryTermMetadataRepository glossaryTermMetadataRepository;
  private final VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService;
  private final TextUnitBatchImporterService textUnitBatchImporterService;
  private final TMTextUnitRepository tmTextUnitRepository;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public GlossaryImportExportService(
      GlossaryRepository glossaryRepository,
      GlossaryStorageService glossaryStorageService,
      TextUnitSearcher textUnitSearcher,
      GlossaryTermMetadataRepository glossaryTermMetadataRepository,
      VirtualTextUnitBatchUpdaterService virtualTextUnitBatchUpdaterService,
      TextUnitBatchImporterService textUnitBatchImporterService,
      TMTextUnitRepository tmTextUnitRepository) {
    this.glossaryRepository = glossaryRepository;
    this.glossaryStorageService = glossaryStorageService;
    this.textUnitSearcher = textUnitSearcher;
    this.glossaryTermMetadataRepository = glossaryTermMetadataRepository;
    this.virtualTextUnitBatchUpdaterService = virtualTextUnitBatchUpdaterService;
    this.textUnitBatchImporterService = textUnitBatchImporterService;
    this.tmTextUnitRepository = tmTextUnitRepository;
  }

  @Transactional
  public ExportPayload exportGlossary(Long glossaryId, String format) {
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    List<GlossaryExportRow> rows = buildExportRows(glossary, asset);
    String normalizedFormat = normalizeFormat(format);
    String filename = slugify(glossary.getName()) + "." + normalizedFormat;
    if (FORMAT_CSV.equals(normalizedFormat)) {
      return new ExportPayload(normalizedFormat, filename, writeCsv(rows));
    }
    return new ExportPayload(
        normalizedFormat,
        filename,
        writeJson(
            new GlossaryExportDocument(
                new GlossaryExportInfo(
                    glossary.getId(),
                    glossary.getName(),
                    glossary.getDescription(),
                    Boolean.TRUE.equals(glossary.getEnabled()),
                    glossary.getPriority(),
                    glossary.getScopeMode()),
                rows)));
  }

  @Transactional
  public ImportResult importGlossary(Long glossaryId, String format, String content) {
    Glossary glossary = getGlossary(glossaryId);
    Asset asset = glossaryStorageService.ensureCanonicalAsset(glossary);
    List<GlossaryExportRow> rows = parseRows(format, content);
    if (rows.isEmpty()) {
      return new ImportResult(0, 0, 0, 0);
    }

    Map<String, ExistingTerm> existingTermsByKey = loadExistingTermsByKey(asset);
    Map<String, ExistingTerm> existingTermsBySource =
        loadExistingTermsBySource(existingTermsByKey.values());
    Map<String, ImportedTerm> importedTerms =
        groupImportedTerms(rows, existingTermsByKey, existingTermsBySource);

    List<VirtualAssetTextUnit> sourceTextUnits = new ArrayList<>(importedTerms.size());
    int createdTermCount = 0;
    int updatedTermCount = 0;
    for (ImportedTerm importedTerm : importedTerms.values()) {
      sourceTextUnits.add(importedTerm.toVirtualAssetTextUnit());
      if (importedTerm.existingTerm() == null) {
        createdTermCount++;
      } else {
        updatedTermCount++;
      }
    }
    try {
      virtualTextUnitBatchUpdaterService.updateTextUnits(asset, sourceTextUnits, false);
    } catch (VirtualAssetRequiredException ex) {
      throw new IllegalStateException(
          "Canonical glossary asset is not a virtual asset for glossary " + glossary.getId(), ex);
    }

    Map<String, ExistingTerm> refreshedTermsByKey = loadExistingTermsByKey(asset);
    upsertMetadata(glossary, importedTerms, refreshedTermsByKey);

    TranslationDelta translationDelta =
        importTranslations(glossary, importedTerms, refreshedTermsByKey);
    return new ImportResult(
        createdTermCount,
        updatedTermCount,
        translationDelta.createdTranslationCount(),
        translationDelta.updatedTranslationCount());
  }

  private Glossary getGlossary(Long glossaryId) {
    return glossaryRepository
        .findByIdWithBindings(glossaryId)
        .orElseThrow(() -> new IllegalArgumentException("Glossary not found: " + glossaryId));
  }

  private List<GlossaryExportRow> buildExportRows(Glossary glossary, Asset asset) {
    Map<String, ExistingTerm> sourceTermsByKey = loadExistingTermsByKey(asset);
    if (sourceTermsByKey.isEmpty()) {
      return List.of();
    }

    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId =
        glossaryTermMetadataRepository
            .findByGlossaryIdAndTmTextUnitIdIn(
                glossary.getId(),
                sourceTermsByKey.values().stream()
                    .map(term -> term.textUnit().getTmTextUnitId())
                    .toList())
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    metadata -> metadata.getTmTextUnit().getId(), metadata -> metadata));

    Map<String, List<TextUnitDTO>> localizedByKey =
        loadLocalizedTextUnits(asset, glossary.getBackingRepository());
    List<GlossaryExportRow> rows = new ArrayList<>();

    for (ExistingTerm existingTerm :
        sourceTermsByKey.values().stream()
            .sorted(
                Comparator.comparing(
                    term -> term.textUnit().getName(), String.CASE_INSENSITIVE_ORDER))
            .toList()) {
      GlossaryTermMetadata metadata =
          metadataByTmTextUnitId.get(existingTerm.textUnit().getTmTextUnitId());
      List<TextUnitDTO> localizedTextUnits =
          localizedByKey.getOrDefault(existingTerm.textUnit().getName(), List.of());

      if (localizedTextUnits.isEmpty()) {
        rows.add(toExportRow(existingTerm.textUnit(), metadata, null));
        continue;
      }

      for (TextUnitDTO localizedTextUnit : localizedTextUnits) {
        rows.add(toExportRow(existingTerm.textUnit(), metadata, localizedTextUnit));
      }
    }

    return rows;
  }

  private GlossaryExportRow toExportRow(
      TextUnitDTO sourceTextUnit, GlossaryTermMetadata metadata, TextUnitDTO localizedTextUnit) {
    return new GlossaryExportRow(
        sourceTextUnit.getName(),
        sourceTextUnit.getSource(),
        metadata != null ? metadata.getDefinition() : sourceTextUnit.getComment(),
        metadata != null ? metadata.getPartOfSpeech() : null,
        metadata != null ? metadata.getTermType() : null,
        metadata != null ? metadata.getEnforcement() : null,
        metadata != null ? metadata.getStatus() : null,
        metadata != null ? metadata.getProvenance() : null,
        metadata != null ? Boolean.TRUE.equals(metadata.getCaseSensitive()) : false,
        metadata != null
            ? Boolean.TRUE.equals(metadata.getDoNotTranslate())
            : sourceTextUnit.isDoNotTranslate(),
        localizedTextUnit != null ? localizedTextUnit.getTargetLocale() : null,
        localizedTextUnit != null ? localizedTextUnit.getTarget() : null,
        localizedTextUnit != null ? localizedTextUnit.getTargetComment() : null);
  }

  private Map<String, ExistingTerm> loadExistingTermsByKey(Asset asset) {
    Map<String, ExistingTerm> termsByKey = new LinkedHashMap<>();
    for (TextUnitDTO textUnit : searchAssetTextUnits(asset, null, true)) {
      termsByKey.put(textUnit.getName(), new ExistingTerm(textUnit));
    }
    return termsByKey;
  }

  private Map<String, ExistingTerm> loadExistingTermsBySource(
      Collection<ExistingTerm> existingTerms) {
    Map<String, ExistingTerm> existingTermsBySource = new LinkedHashMap<>();
    for (ExistingTerm existingTerm : existingTerms) {
      existingTermsBySource.put(
          normalizeSourceKey(existingTerm.textUnit().getSource()), existingTerm);
    }
    return existingTermsBySource;
  }

  private Map<String, List<TextUnitDTO>> loadLocalizedTextUnits(
      Asset asset, Repository backingRepository) {
    Map<String, List<TextUnitDTO>> localizedByKey = new LinkedHashMap<>();
    backingRepository.getRepositoryLocales().stream()
        .filter(repositoryLocale -> repositoryLocale.getParentLocale() != null)
        .forEach(
            repositoryLocale -> {
              List<TextUnitDTO> localizedTextUnits =
                  searchAssetTextUnits(asset, repositoryLocale.getLocale().getBcp47Tag(), false);
              for (TextUnitDTO localizedTextUnit : localizedTextUnits) {
                localizedByKey
                    .computeIfAbsent(localizedTextUnit.getName(), ignored -> new ArrayList<>())
                    .add(localizedTextUnit);
              }
            });
    return localizedByKey;
  }

  private List<TextUnitDTO> searchAssetTextUnits(
      Asset asset, String localeTag, boolean rootLocale) {
    TextUnitSearcherParameters parameters = new TextUnitSearcherParameters();
    parameters.setAssetId(asset.getId());
    parameters.setUsedFilter(UsedFilter.USED);
    parameters.setLimit(EXPORT_LIMIT);
    if (rootLocale) {
      parameters.setForRootLocale(true);
      parameters.setRootLocaleExcluded(false);
    } else {
      parameters.setLocaleTags(List.of(localeTag));
      parameters.setRootLocaleExcluded(false);
    }
    return textUnitSearcher.search(parameters);
  }

  private Map<String, ImportedTerm> groupImportedTerms(
      List<GlossaryExportRow> rows,
      Map<String, ExistingTerm> existingTermsByKey,
      Map<String, ExistingTerm> existingTermsBySource) {
    Map<String, ImportedTerm> importedTerms = new LinkedHashMap<>();
    for (GlossaryExportRow row : rows) {
      String source = normalizeRequired(row.source(), "source");
      String providedKey = normalizeOptional(row.termKey());
      ExistingTerm existingTerm =
          providedKey != null
              ? existingTermsByKey.get(providedKey)
              : existingTermsBySource.get(normalizeSourceKey(source));
      String termKey =
          providedKey != null
              ? providedKey
              : existingTerm != null ? existingTerm.textUnit().getName() : generateTermKey(source);
      ImportedTerm importedTerm =
          importedTerms.computeIfAbsent(
              termKey, ignored -> ImportedTerm.fromRow(termKey, row, source, existingTerm));
      importedTerm.mergeRow(row);
    }
    return importedTerms;
  }

  private void upsertMetadata(
      Glossary glossary,
      Map<String, ImportedTerm> importedTerms,
      Map<String, ExistingTerm> refreshedTermsByKey) {
    List<GlossaryTermMetadata> existingMetadata =
        glossaryTermMetadataRepository.findByGlossaryIdAndTmTextUnitIdIn(
            glossary.getId(),
            refreshedTermsByKey.values().stream()
                .map(term -> term.textUnit().getTmTextUnitId())
                .toList());
    Map<Long, GlossaryTermMetadata> metadataByTmTextUnitId = new LinkedHashMap<>();
    for (GlossaryTermMetadata metadata : existingMetadata) {
      metadataByTmTextUnitId.put(metadata.getTmTextUnit().getId(), metadata);
    }

    for (ImportedTerm importedTerm : importedTerms.values()) {
      ExistingTerm refreshedTerm = refreshedTermsByKey.get(importedTerm.termKey());
      if (refreshedTerm == null) {
        throw new IllegalStateException(
            "Glossary term was not persisted: " + importedTerm.termKey());
      }

      GlossaryTermMetadata metadata =
          metadataByTmTextUnitId.get(refreshedTerm.textUnit().getTmTextUnitId());
      if (metadata == null) {
        metadata = new GlossaryTermMetadata();
        metadata.setGlossary(glossary);
        metadata.setTmTextUnit(
            tmTextUnitRepository
                .findById(refreshedTerm.textUnit().getTmTextUnitId())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "Glossary term TM text unit not found: "
                                + refreshedTerm.textUnit().getTmTextUnitId())));
      }

      metadata.setDefinition(importedTerm.definition());
      metadata.setPartOfSpeech(importedTerm.partOfSpeech());
      metadata.setTermType(importedTerm.termType());
      metadata.setEnforcement(importedTerm.enforcement());
      metadata.setStatus(importedTerm.status());
      metadata.setProvenance(importedTerm.provenance());
      metadata.setCaseSensitive(importedTerm.caseSensitive());
      metadata.setDoNotTranslate(importedTerm.doNotTranslate());
      glossaryTermMetadataRepository.save(metadata);
    }
  }

  private TranslationDelta importTranslations(
      Glossary glossary,
      Map<String, ImportedTerm> importedTerms,
      Map<String, ExistingTerm> refreshedTermsByKey) {
    List<TextUnitBatchImporterService.TextUnitDTOWithVariantComment> imports = new ArrayList<>();
    int createdTranslationCount = 0;
    int updatedTranslationCount = 0;
    Map<String, List<TextUnitDTO>> currentLocalizedByKey =
        loadLocalizedTextUnits(
            glossaryStorageService.ensureCanonicalAsset(glossary), glossary.getBackingRepository());

    for (ImportedTerm importedTerm : importedTerms.values()) {
      ExistingTerm refreshedTerm = refreshedTermsByKey.get(importedTerm.termKey());
      if (refreshedTerm == null) {
        continue;
      }
      Map<String, TextUnitDTO> currentTranslationsByLocale =
          currentLocalizedByKey.getOrDefault(importedTerm.termKey(), List.of()).stream()
              .collect(
                  java.util.stream.Collectors.toMap(TextUnitDTO::getTargetLocale, value -> value));

      for (ImportedTranslation translation : importedTerm.translations()) {
        if (translation.localeTag() == null || translation.localeTag().isBlank()) {
          continue;
        }
        glossaryStorageService.ensureLocale(glossary, translation.localeTag());
        TextUnitDTO currentTranslation = currentTranslationsByLocale.get(translation.localeTag());
        if (currentTranslation == null || currentTranslation.getTarget() == null) {
          createdTranslationCount++;
        } else {
          updatedTranslationCount++;
        }

        TextUnitDTO textUnitDTO = new TextUnitDTO();
        textUnitDTO.setRepositoryName(glossary.getBackingRepository().getName());
        textUnitDTO.setAssetPath(glossary.getAssetPath());
        textUnitDTO.setTargetLocale(translation.localeTag());
        textUnitDTO.setName(importedTerm.termKey());
        textUnitDTO.setTarget(translation.target());
        textUnitDTO.setTargetComment(translation.targetComment());
        textUnitDTO.setStatus(APPROVED);
        textUnitDTO.setIncludedInLocalizedFile(true);
        imports.add(
            new TextUnitBatchImporterService.TextUnitDTOWithVariantComment(textUnitDTO, null));
      }
    }

    if (!imports.isEmpty()) {
      textUnitBatchImporterService.importTextUnitsWithVariantComment(
          imports,
          TextUnitBatchImporterService.IntegrityChecksType.SKIP,
          TextUnitBatchImporterService.ImportMode.ALWAYS_IMPORT);
    }

    return new TranslationDelta(createdTranslationCount, updatedTranslationCount);
  }

  private List<GlossaryExportRow> parseRows(String format, String content) {
    String normalizedFormat = normalizeFormat(format);
    String normalizedContent = content == null ? "" : content.trim();
    if (normalizedContent.isEmpty()) {
      throw new IllegalArgumentException("Glossary import content is required");
    }
    return FORMAT_CSV.equals(normalizedFormat)
        ? parseCsv(normalizedContent)
        : parseJson(normalizedContent);
  }

  private List<GlossaryExportRow> parseJson(String content) {
    try {
      JsonNode root = objectMapper.readTree(content);
      JsonNode entriesNode = root.isArray() ? root : root.get("entries");
      if (entriesNode == null || !entriesNode.isArray()) {
        throw new IllegalArgumentException("Glossary JSON import must contain an entries array");
      }
      List<GlossaryExportRow> rows = new ArrayList<>();
      for (JsonNode entryNode : entriesNode) {
        rows.add(objectMapper.treeToValue(entryNode, GlossaryExportRow.class));
      }
      return rows;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Failed to parse glossary JSON import", ex);
    }
  }

  private List<GlossaryExportRow> parseCsv(String content) {
    try (CSVParser parser =
        CSVParser.parse(content, CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {
      List<GlossaryExportRow> rows = new ArrayList<>();
      for (CSVRecord record : parser) {
        rows.add(
            new GlossaryExportRow(
                record.get("termKey"),
                record.get("source"),
                nullable(record, "definition"),
                nullable(record, "partOfSpeech"),
                nullable(record, "termType"),
                nullable(record, "enforcement"),
                nullable(record, "status"),
                nullable(record, "provenance"),
                parseBoolean(nullable(record, "caseSensitive")),
                parseBoolean(nullable(record, "doNotTranslate")),
                nullable(record, "locale"),
                nullable(record, "target"),
                nullable(record, "targetComment")));
      }
      return rows;
    } catch (IOException ex) {
      throw new IllegalArgumentException("Failed to parse glossary CSV import", ex);
    }
  }

  private String writeJson(GlossaryExportDocument document) {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(document);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Failed to serialize glossary export", ex);
    }
  }

  private String writeCsv(List<GlossaryExportRow> rows) {
    try {
      StringWriter writer = new StringWriter();
      CSVPrinter printer =
          new CSVPrinter(
              writer,
              CSVFormat.DEFAULT.withHeader(
                  "termKey",
                  "source",
                  "definition",
                  "partOfSpeech",
                  "termType",
                  "enforcement",
                  "status",
                  "provenance",
                  "caseSensitive",
                  "doNotTranslate",
                  "locale",
                  "target",
                  "targetComment"));
      for (GlossaryExportRow row : rows) {
        printer.printRecord(
            row.termKey(),
            row.source(),
            row.definition(),
            row.partOfSpeech(),
            row.termType(),
            row.enforcement(),
            row.status(),
            row.provenance(),
            row.caseSensitive(),
            row.doNotTranslate(),
            row.locale(),
            row.target(),
            row.targetComment());
      }
      printer.flush();
      return writer.toString();
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to serialize glossary CSV export", ex);
    }
  }

  private String normalizeFormat(String format) {
    String normalized = format == null ? FORMAT_JSON : format.trim().toLowerCase(Locale.ROOT);
    if (!FORMAT_JSON.equals(normalized) && !FORMAT_CSV.equals(normalized)) {
      throw new IllegalArgumentException("Unsupported glossary format: " + format);
    }
    return normalized;
  }

  private String normalizeRequired(String value, String fieldName) {
    String normalized = normalizeOptional(value);
    if (normalized == null) {
      throw new IllegalArgumentException("Glossary import field is required: " + fieldName);
    }
    return normalized;
  }

  private String normalizeOptional(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
  }

  private String normalizeSourceKey(String source) {
    return source.trim().toLowerCase(Locale.ROOT);
  }

  private String generateTermKey(String source) {
    String slug =
        source
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("(^_+|_+$)", "");
    if (slug.isBlank()) {
      slug = "term";
    }
    return slug + "_" + DigestUtils.md5Hex(source).substring(0, 8);
  }

  private String slugify(String name) {
    return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  private String nullable(CSVRecord record, String key) {
    return record.isMapped(key) ? normalizeOptional(record.get(key)) : null;
  }

  private boolean parseBoolean(String value) {
    if (value == null) {
      return false;
    }
    return Boolean.parseBoolean(value);
  }

  public record ExportPayload(String format, String filename, String content) {}

  public record ImportResult(
      int createdTermCount,
      int updatedTermCount,
      int createdTranslationCount,
      int updatedTranslationCount) {}

  record GlossaryExportDocument(GlossaryExportInfo glossary, List<GlossaryExportRow> entries) {}

  record GlossaryExportInfo(
      Long id, String name, String description, boolean enabled, int priority, String scopeMode) {}

  public record GlossaryExportRow(
      String termKey,
      String source,
      String definition,
      String partOfSpeech,
      String termType,
      String enforcement,
      String status,
      String provenance,
      boolean caseSensitive,
      boolean doNotTranslate,
      String locale,
      String target,
      String targetComment) {}

  record ExistingTerm(TextUnitDTO textUnit) {
    ExistingTerm {
      Objects.requireNonNull(textUnit);
    }
  }

  static final class ImportedTerm {
    private final String termKey;
    private final ExistingTerm existingTerm;
    private final String source;
    private String definition;
    private String partOfSpeech;
    private String termType;
    private String enforcement;
    private String status;
    private String provenance;
    private boolean caseSensitive;
    private boolean doNotTranslate;
    private final Map<String, ImportedTranslation> translations = new LinkedHashMap<>();

    private ImportedTerm(String termKey, ExistingTerm existingTerm, String source) {
      this.termKey = termKey;
      this.existingTerm = existingTerm;
      this.source = source;
    }

    static ImportedTerm fromRow(
        String termKey, GlossaryExportRow row, String source, ExistingTerm existingTerm) {
      ImportedTerm importedTerm = new ImportedTerm(termKey, existingTerm, source);
      importedTerm.mergeRow(row);
      return importedTerm;
    }

    void mergeRow(GlossaryExportRow row) {
      String rowSource = row.source() == null ? null : row.source().trim();
      if (rowSource != null && !rowSource.equals(source)) {
        throw new IllegalArgumentException(
            "Conflicting source values for glossary term key: " + termKey);
      }
      definition = normalizeLastNonBlank(definition, row.definition());
      partOfSpeech = normalizeLastNonBlank(partOfSpeech, row.partOfSpeech());
      termType =
          normalizeOptionalValue(
              row.termType(), GlossaryTermMetadata.TERM_TYPES, "term type", termType);
      enforcement =
          normalizeOptionalValue(
              row.enforcement(),
              GlossaryTermMetadata.ENFORCEMENTS,
              "enforcement",
              enforcement == null ? GlossaryTermMetadata.ENFORCEMENT_SOFT : enforcement);
      status =
          normalizeOptionalValue(
              row.status(),
              GlossaryTermMetadata.STATUSES,
              "status",
              status == null ? GlossaryTermMetadata.STATUS_CANDIDATE : status);
      provenance =
          normalizeOptionalValue(
              row.provenance(),
              GlossaryTermMetadata.PROVENANCES,
              "provenance",
              provenance == null ? GlossaryTermMetadata.PROVENANCE_IMPORTED : provenance);
      caseSensitive = row.caseSensitive() || caseSensitive;
      doNotTranslate = row.doNotTranslate() || doNotTranslate;

      String localeTag = row.locale() == null ? null : row.locale().trim();
      String target = row.target() == null ? null : row.target().trim();
      if (localeTag != null && target != null && !target.isEmpty()) {
        translations.put(
            localeTag,
            new ImportedTranslation(
                localeTag,
                target,
                row.targetComment() == null ? null : row.targetComment().trim()));
      }
    }

    VirtualAssetTextUnit toVirtualAssetTextUnit() {
      VirtualAssetTextUnit textUnit = new VirtualAssetTextUnit();
      textUnit.setName(termKey);
      textUnit.setContent(source);
      textUnit.setComment(definition);
      textUnit.setDoNotTranslate(doNotTranslate);
      return textUnit;
    }

    String termKey() {
      return termKey;
    }

    ExistingTerm existingTerm() {
      return existingTerm;
    }

    String definition() {
      return definition;
    }

    String partOfSpeech() {
      return partOfSpeech;
    }

    String termType() {
      return termType;
    }

    String enforcement() {
      return enforcement == null ? GlossaryTermMetadata.ENFORCEMENT_SOFT : enforcement;
    }

    String status() {
      return status == null ? GlossaryTermMetadata.STATUS_CANDIDATE : status;
    }

    String provenance() {
      return provenance == null ? GlossaryTermMetadata.PROVENANCE_IMPORTED : provenance;
    }

    boolean caseSensitive() {
      return caseSensitive;
    }

    boolean doNotTranslate() {
      return doNotTranslate;
    }

    Collection<ImportedTranslation> translations() {
      return translations.values();
    }

    private static String normalizeLastNonBlank(String currentValue, String nextValue) {
      if (nextValue == null || nextValue.trim().isEmpty()) {
        return currentValue;
      }
      return nextValue.trim();
    }

    private static String normalizeOptionalValue(
        String value, Set<String> allowed, String fieldName, String defaultValue) {
      if (value == null || value.trim().isEmpty()) {
        return defaultValue;
      }
      String upper = value.trim().toUpperCase(Locale.ROOT);
      if (!allowed.contains(upper)) {
        throw new IllegalArgumentException("Unknown glossary " + fieldName + ": " + value);
      }
      return upper;
    }
  }

  record ImportedTranslation(String localeTag, String target, String targetComment) {}

  record TranslationDelta(int createdTranslationCount, int updatedTranslationCount) {}
}
