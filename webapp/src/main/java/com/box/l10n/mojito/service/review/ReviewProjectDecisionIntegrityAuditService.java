package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.service.review.ReviewProjectDecisionIntegrityAuditRepository.DecisionRow;
import com.box.l10n.mojito.utils.ServerConfig;
import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.MessagePattern.Part;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds a bounded, read-only integrity report from persisted Review Project decisions. */
@Service
public class ReviewProjectDecisionIntegrityAuditService {

  static final int MAX_DETAIL_LIMIT = 50;
  static final Duration MAX_AUDIT_WINDOW = Duration.ofHours(48);
  private static final Duration MAX_CARRYOVER_GAP = Duration.ofSeconds(30);
  private static final int PREVIEW_CODE_POINT_LIMIT = 160;
  private static final int MAX_DETAILED_DECISIONS_PER_RUN = 20;

  private static final Pattern PRINTF_PLACEHOLDER =
      Pattern.compile(
          "%(?:(?:\\d+\\$)?[-#+0,(<]*\\d*(?:\\.\\d+)?(?:[tT])?(?:hh|h|l|ll|j|z|t|L)?(?:%|d|i|u|o|x|X|f|F|e|E|g|G|a|A|c|s|p|n|@)|\\([A-Za-z0-9_]+\\)[-#+0,(<]*\\d*(?:\\.\\d+)?[A-Za-z])");
  private static final Pattern MUSTACHE_PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
  private static final Pattern DOLLAR_PLACEHOLDER =
      Pattern.compile("\\$\\{?[A-Za-z_][A-Za-z0-9_]*}?");
  private static final Pattern MARKUP_TAG =
      Pattern.compile("<\\s*(/?)\\s*([\\p{L}_][\\p{L}\\p{N}._:-]*|\\d+)(?:\\s+[^<>]*?)?\\s*(/?)>");
  private static final Set<String> VOID_MARKUP_TAGS =
      Set.of(
          "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
          "source", "track", "wbr");

  private static final Pattern BEARER_SECRET =
      Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
  private static final Pattern LABELED_SECRET =
      Pattern.compile(
          "(?i)\\b(password|passwd|secret|token|api[_-]?key|account[_-]?key|client[_-]?secret|access[_-]?key|aws[_-]?secret[_-]?access[_-]?key|private[_-]?key|connection[_-]?string)\\s*[:=]\\s*[^\\s,;]{4,}");
  private static final Pattern AUTHORIZATION_HEADER =
      Pattern.compile("(?i)\\b(?:proxy-)?authorization\\s*:\\s*[^,]{4,}");
  private static final Pattern COOKIE_HEADER =
      Pattern.compile("(?i)\\b(?:cookie|set-cookie)\\s*:\\s*[^,]{4,}");
  private static final Pattern KNOWN_TOKEN =
      Pattern.compile(
          "(?i)\\b(?:sk-[A-Za-z0-9_-]{12,}|gh[pousr]_[A-Za-z0-9]{12,}|AKIA[A-Z0-9]{12,}|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})\\b");
  private static final Pattern CONNECTION_STRING =
      Pattern.compile(
          "(?i)\\b(?:jdbc:[a-z0-9]+|mysql|mariadb|postgres(?:ql)?|mongodb(?:\\+srv)?|redis|rediss|amqp|amqps)://[^\\s]+");
  private static final Pattern PRIVATE_KEY =
      Pattern.compile(
          "(?i)-----BEGIN [^-]{0,40}PRIVATE KEY-----.*?(?:-----END [^-]{0,40}PRIVATE KEY-----|$)");
  private static final Pattern HIGH_ENTROPY_TOKEN =
      Pattern.compile(
          "(?<![A-Za-z0-9_+/=-])(?=[A-Za-z0-9_+/=-]{32,})(?=[A-Za-z0-9_+/=-]*[A-Za-z])(?=[A-Za-z0-9_+/=-]*\\d)[A-Za-z0-9_+/=-]{32,}(?![A-Za-z0-9_+/=-])");
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@");

  private final ReviewProjectDecisionIntegrityAuditRepository repository;
  private final ServerConfig serverConfig;

  public ReviewProjectDecisionIntegrityAuditService(
      ReviewProjectDecisionIntegrityAuditRepository repository, ServerConfig serverConfig) {
    this.repository = Objects.requireNonNull(repository);
    this.serverConfig = Objects.requireNonNull(serverConfig);
  }

  /**
   * Audits persisted decision mutations in {@code [fromInclusive, toExclusive)}. The repository
   * performs one bounded read; all candidate detection, grouping, and structural analysis is local.
   */
  @Transactional(readOnly = true)
  public AuditResult audit(
      Instant fromInclusive,
      Instant toExclusive,
      int carryoverDetailLimit,
      int structuralDetailLimit) {
    validateBounds(fromInclusive, toExclusive);
    validateDetailLimit(carryoverDetailLimit, "carryoverDetailLimit");
    validateDetailLimit(structuralDetailLimit, "structuralDetailLimit");

    List<DecisionRow> rows = repository.findDecisionRows(fromInclusive, toExclusive);
    Coverage coverage = summarizeCoverage(rows);
    CarryoverSummary carryover = analyzeCarryover(rows, carryoverDetailLimit);
    BroaderReviewSummary broaderReview = analyzeStructuralRisks(rows, structuralDetailLimit);
    String status =
        carryover.candidatePairCount() == 0
                && broaderReview.deterministicIntegrityFindingCount() == 0
                && broaderReview.reviewNeededFindingCount() == 0
            ? "PASS"
            : "CANDIDATES_FOUND";

    return new AuditResult(
        status,
        Instant.now().toString(),
        new AuditWindow(
            fromInclusive.toString(),
            toExclusive.toString(),
            durationSeconds(Duration.between(fromInclusive, toExclusive))),
        coverage,
        carryover,
        broaderReview,
        "Deterministic checks identify structural inconsistencies only; carryover and source-equals-target results require human review and are not proof of a bad translation.");
  }

  private static void validateBounds(Instant fromInclusive, Instant toExclusive) {
    if (fromInclusive == null || toExclusive == null) {
      throw new IllegalArgumentException("fromInclusive and toExclusive are required");
    }
    if (!fromInclusive.isBefore(toExclusive)) {
      throw new IllegalArgumentException("fromInclusive must be before toExclusive");
    }
    Duration window = Duration.between(fromInclusive, toExclusive);
    if (window.compareTo(MAX_AUDIT_WINDOW) > 0) {
      throw new IllegalArgumentException("audit window must not exceed 48 hours");
    }
  }

  private static void validateDetailLimit(int detailLimit, String fieldName) {
    if (detailLimit < 0 || detailLimit > MAX_DETAIL_LIMIT) {
      throw new IllegalArgumentException(fieldName + " must be between 0 and " + MAX_DETAIL_LIMIT);
    }
  }

  private Coverage summarizeCoverage(List<DecisionRow> rows) {
    long distinctReviewers =
        rows.stream()
            .map(DecisionRow::effectiveReviewerId)
            .filter(Objects::nonNull)
            .distinct()
            .count();
    long distinctProjects =
        rows.stream().map(DecisionRow::reviewProjectId).filter(Objects::nonNull).distinct().count();
    long distinctTextUnits =
        rows.stream().map(DecisionRow::tmTextUnitId).filter(Objects::nonNull).distinct().count();
    long unattributedDecisions =
        rows.stream().filter(row -> row.effectiveReviewerId() == null).count();
    long expectedTargetlessTerminologyDecisions =
        rows.stream()
            .filter(this::isExpectedTargetlessTerminologyDecision)
            .filter(row -> row.currentTargetText() == null)
            .count();
    return new Coverage(
        rows.size(),
        distinctReviewers,
        distinctProjects,
        distinctTextUnits,
        unattributedDecisions,
        expectedTargetlessTerminologyDecisions,
        "The decision table stores one mutable row per Review Project text unit. This report covers each row's latest persisted DECIDED mutation in the requested window; overwritten intermediate clicks cannot be reconstructed.");
  }

  private CarryoverSummary analyzeCarryover(List<DecisionRow> rows, int detailLimit) {
    List<DecisionRow> candidateEdges =
        rows.stream()
            .filter(this::isCarryoverCandidate)
            .sorted(
                Comparator.comparing(DecisionRow::decidedAt).thenComparing(DecisionRow::decisionId))
            .toList();
    List<List<DecisionRow>> groupedEdges = groupCarryoverEdges(candidateEdges);
    List<CarryoverRun> reportedRuns =
        groupedEdges.stream().limit(detailLimit).map(this::toCarryoverRun).toList();
    return new CarryoverSummary(
        candidateEdges.size(),
        groupedEdges.size(),
        reportedRuns.size(),
        groupedEdges.size() > reportedRuns.size(),
        reportedRuns);
  }

  private boolean isCarryoverCandidate(DecisionRow row) {
    if (row.previousDecisionId() == null
        || row.effectiveReviewerId() == null
        || row.previousDecidedAt() == null
        || row.sourceText() == null
        || row.previousSourceText() == null
        || row.decisionTargetText() == null) {
      return false;
    }
    Duration gap = Duration.between(row.previousDecidedAt(), row.decidedAt());
    return !gap.isNegative()
        && gap.compareTo(MAX_CARRYOVER_GAP) <= 0
        && !row.sourceText().equals(row.previousSourceText())
        && row.decisionTargetText().equals(row.previousDecisionTargetText())
        && row.decisionTargetText().equals(row.currentTargetText());
  }

  private static List<List<DecisionRow>> groupCarryoverEdges(List<DecisionRow> candidateEdges) {
    Map<Long, DecisionRow> edgeByPreviousDecisionId = new HashMap<>();
    Set<Long> candidateDecisionIds = new HashSet<>();
    for (DecisionRow edge : candidateEdges) {
      edgeByPreviousDecisionId.put(edge.previousDecisionId(), edge);
      candidateDecisionIds.add(edge.decisionId());
    }

    List<List<DecisionRow>> runs = new ArrayList<>();
    Set<Long> visitedDecisionIds = new HashSet<>();
    for (DecisionRow edge : candidateEdges) {
      if (candidateDecisionIds.contains(edge.previousDecisionId())) {
        continue;
      }
      runs.add(walkCarryoverRun(edge, edgeByPreviousDecisionId, visitedDecisionIds));
    }
    for (DecisionRow edge : candidateEdges) {
      if (!visitedDecisionIds.contains(edge.decisionId())) {
        runs.add(walkCarryoverRun(edge, edgeByPreviousDecisionId, visitedDecisionIds));
      }
    }
    runs.sort(
        Comparator.comparing((List<DecisionRow> run) -> run.get(0).decidedAt())
            .thenComparing(run -> run.get(0).decisionId()));
    return runs;
  }

  private static List<DecisionRow> walkCarryoverRun(
      DecisionRow first,
      Map<Long, DecisionRow> edgeByPreviousDecisionId,
      Set<Long> visitedDecisionIds) {
    List<DecisionRow> run = new ArrayList<>();
    DecisionRow current = first;
    while (current != null && visitedDecisionIds.add(current.decisionId())) {
      run.add(current);
      DecisionRow next = edgeByPreviousDecisionId.get(current.decisionId());
      if (next == null
          || !Objects.equals(next.reviewProjectId(), current.reviewProjectId())
          || !Objects.equals(next.effectiveReviewerId(), current.effectiveReviewerId())
          || !Objects.equals(next.decisionTargetText(), current.decisionTargetText())) {
        break;
      }
      current = next;
    }
    return run;
  }

  private CarryoverRun toCarryoverRun(List<DecisionRow> edges) {
    DecisionRow first = edges.get(0);
    List<CarryoverDecision> decisions = new ArrayList<>();
    decisions.add(
        new CarryoverDecision(
            false,
            first.previousDecisionId(),
            first.previousReviewProjectTextUnitId(),
            first.previousTmTextUnitId(),
            first.previousDecidedAt().toString(),
            null,
            preview(first.previousSourceText()),
            preview(first.previousDecisionTargetText()),
            buildReviewProjectLink(first.reviewProjectId(), first.previousTmTextUnitId())));
    int reportedEdgeCount = Math.min(edges.size(), MAX_DETAILED_DECISIONS_PER_RUN - 1);
    for (DecisionRow edge : edges.subList(0, reportedEdgeCount)) {
      decisions.add(
          new CarryoverDecision(
              true,
              edge.decisionId(),
              edge.reviewProjectTextUnitId(),
              edge.tmTextUnitId(),
              edge.decidedAt().toString(),
              durationSeconds(Duration.between(edge.previousDecidedAt(), edge.decidedAt())),
              preview(edge.sourceText()),
              preview(edge.decisionTargetText()),
              buildReviewProjectLink(edge.reviewProjectId(), edge.tmTextUnitId())));
    }
    return new CarryoverRun(
        edges.size(),
        edges.size() + 1,
        decisions.size(),
        decisions.size() < edges.size() + 1,
        projectRef(first),
        reviewerRef(first),
        first.projectLocale(),
        preview(first.decisionTargetText()),
        legitimateLookingReason(edges),
        "Candidate only: different source strings can legitimately share a target, especially product names, short actions, and grammatical variants.",
        decisions);
  }

  private BroaderReviewSummary analyzeStructuralRisks(List<DecisionRow> rows, int detailLimit) {
    Map<String, Long> countsByKind = new TreeMap<>();
    Set<Long> deterministicDecisionIds = new HashSet<>();
    Set<Long> reviewNeededDecisionIds = new HashSet<>();
    List<StructuralFinding> details = new ArrayList<>();
    long[] categoryCounts = new long[2];

    for (DecisionRow row : rows) {
      List<FindingSpec> rowFindings = structuralFindings(row);
      for (FindingSpec finding : rowFindings) {
        countsByKind.merge(finding.kind(), 1L, Long::sum);
        if (finding.category() == FindingCategory.DETERMINISTIC_INTEGRITY) {
          categoryCounts[0]++;
          deterministicDecisionIds.add(row.decisionId());
        } else {
          categoryCounts[1]++;
          reviewNeededDecisionIds.add(row.decisionId());
        }
        if (details.size() < detailLimit) {
          details.add(toStructuralFinding(row, finding));
        }
      }
    }

    long totalFindings = categoryCounts[0] + categoryCounts[1];
    return new BroaderReviewSummary(
        categoryCounts[0],
        deterministicDecisionIds.size(),
        categoryCounts[1],
        reviewNeededDecisionIds.size(),
        new LinkedHashMap<>(countsByKind),
        details.size(),
        totalFindings > details.size(),
        details);
  }

  private List<FindingSpec> structuralFindings(DecisionRow row) {
    List<FindingSpec> findings = new ArrayList<>();
    String source = row.sourceText();
    String target = row.currentTargetText();

    if (target == null) {
      if (!isExpectedTargetlessTerminologyDecision(row)) {
        findings.add(
            deterministic(
                "MISSING_CURRENT_TARGET",
                "The text unit has no current target for the Review Project locale."));
      }
      return findings;
    }
    if (isWhitespaceOnly(target)) {
      findings.add(
          deterministic(
              "WHITESPACE_ONLY_CURRENT_TARGET",
              "The current target is empty or contains only whitespace."));
      return findings;
    }

    if (row.currentVariantLocaleId() != null
        && row.projectLocaleId() != null
        && !row.currentVariantLocaleId().equals(row.projectLocaleId())) {
      findings.add(
          deterministic(
              "CURRENT_VARIANT_LOCALE_MISMATCH",
              "The current target variant locale does not match the Review Project locale."));
    }
    if (row.decisionVariantLocaleId() != null
        && row.projectLocaleId() != null
        && !row.decisionVariantLocaleId().equals(row.projectLocaleId())) {
      findings.add(
          deterministic(
              "DECISION_VARIANT_LOCALE_MISMATCH",
              "The persisted decision variant locale does not match the Review Project locale."));
    }

    if (source == null) {
      findings.add(
          deterministic("MISSING_SOURCE_TEXT", "The decision's text unit has no source text."));
      return findings;
    }

    if (!tokenMultiset(source, PRINTF_PLACEHOLDER)
        .equals(tokenMultiset(target, PRINTF_PLACEHOLDER))) {
      findings.add(
          deterministic(
              "PRINTF_PLACEHOLDER_MISMATCH",
              "Printf-style placeholders differ between source and current target."));
    }
    if (!tokenMultiset(source, MUSTACHE_PLACEHOLDER)
        .equals(tokenMultiset(target, MUSTACHE_PLACEHOLDER))) {
      findings.add(
          deterministic(
              "MUSTACHE_PLACEHOLDER_MISMATCH",
              "Double-brace placeholders differ between source and current target."));
    }
    if (!tokenMultiset(source, DOLLAR_PLACEHOLDER)
        .equals(tokenMultiset(target, DOLLAR_PLACEHOLDER))) {
      findings.add(
          deterministic(
              "DOLLAR_PLACEHOLDER_MISMATCH",
              "Dollar-style placeholders differ between source and current target."));
    }

    FindingSpec icuFinding = findIcuMismatch(source, target);
    if (icuFinding != null) {
      findings.add(icuFinding);
    }
    FindingSpec markupFinding = findMarkupMismatch(source, target);
    if (markupFinding != null) {
      findings.add(markupFinding);
    }

    if (row.projectLocaleId() != null
        && row.sourceLocaleId() != null
        && !row.projectLocaleId().equals(row.sourceLocaleId())
        && source.equals(target)) {
      findings.add(
          reviewNeeded(
              "SOURCE_EQUALS_TARGET_NON_SOURCE_LOCALE",
              "Source and current target are exactly equal in a non-source locale; names, colors, model names, and short actions may legitimately remain unchanged."));
    }
    return findings;
  }

  private static FindingSpec findIcuMismatch(String source, String target) {
    if (source.contains("{{") || source.contains("${") || !source.contains("{")) {
      return null;
    }
    IcuSignature sourceSignature = parseIcuSignature(source);
    if (!sourceSignature.valid() || sourceSignature.arguments().isEmpty()) {
      return null;
    }
    IcuSignature targetSignature = parseIcuSignature(target);
    if (!targetSignature.valid()) {
      return deterministic(
          "ICU_MESSAGE_MISMATCH",
          "The source is a valid ICU message with arguments, but the current target is not parseable as ICU MessageFormat.");
    }
    if (!sourceSignature.arguments().equals(targetSignature.arguments())) {
      return deterministic(
          "ICU_MESSAGE_MISMATCH",
          "ICU argument names or argument types differ between source and current target.");
    }
    return null;
  }

  private static IcuSignature parseIcuSignature(String text) {
    try {
      MessagePattern pattern = new MessagePattern(text);
      Map<String, Long> arguments = new TreeMap<>();
      for (int index = 0; index < pattern.countParts(); index++) {
        Part part = pattern.getPart(index);
        if (part.getType() != Part.Type.ARG_START || index + 1 >= pattern.countParts()) {
          continue;
        }
        Part namePart = pattern.getPart(index + 1);
        if (namePart.getType() != Part.Type.ARG_NAME
            && namePart.getType() != Part.Type.ARG_NUMBER) {
          continue;
        }
        String argument = pattern.getSubstring(namePart) + ":" + part.getArgType().name();
        if (part.getArgType() == MessagePattern.ArgType.SIMPLE) {
          argument += ":" + simpleIcuArgumentType(pattern, index + 2);
        }
        arguments.merge(argument, 1L, Long::sum);
      }
      return new IcuSignature(true, arguments);
    } catch (IllegalArgumentException exception) {
      return new IcuSignature(false, Map.of());
    }
  }

  private static String simpleIcuArgumentType(MessagePattern pattern, int startIndex) {
    for (int index = startIndex; index < pattern.countParts(); index++) {
      Part part = pattern.getPart(index);
      if (part.getType() == Part.Type.ARG_TYPE) {
        return pattern.getSubstring(part).toLowerCase(Locale.ROOT);
      }
      if (part.getType() == Part.Type.ARG_LIMIT || part.getType() == Part.Type.ARG_START) {
        break;
      }
    }
    return "unknown";
  }

  private static FindingSpec findMarkupMismatch(String source, String target) {
    MarkupSignature sourceSignature = parseMarkup(source);
    MarkupSignature targetSignature = parseMarkup(target);
    if (sourceSignature.tokens().isEmpty() && targetSignature.tokens().isEmpty()) {
      return null;
    }
    if ((sourceSignature.balanced() && !targetSignature.balanced())
        || !sourceSignature.tokens().equals(targetSignature.tokens())) {
      return deterministic(
          "MARKUP_TAG_MISMATCH",
          "Markup tag names/counts differ or the source/current-target tag nesting is unbalanced; attributes and tag order are intentionally ignored.");
    }
    return null;
  }

  private static MarkupSignature parseMarkup(String text) {
    Map<String, Long> tokens = new TreeMap<>();
    ArrayDeque<String> stack = new ArrayDeque<>();
    boolean balanced = true;
    Matcher matcher = MARKUP_TAG.matcher(text);
    while (matcher.find()) {
      boolean closing = !matcher.group(1).isEmpty();
      String name = matcher.group(2).toLowerCase(Locale.ROOT);
      boolean selfClosing = !matcher.group(3).isEmpty() || VOID_MARKUP_TAGS.contains(name);
      String token = closing ? "/" + name : selfClosing ? name + "/" : name;
      tokens.merge(token, 1L, Long::sum);
      if (selfClosing) {
        continue;
      }
      if (closing) {
        if (stack.isEmpty() || !stack.pop().equals(name)) {
          balanced = false;
        }
      } else {
        stack.push(name);
      }
    }
    return new MarkupSignature(balanced && stack.isEmpty(), tokens);
  }

  private static Map<String, Long> tokenMultiset(String text, Pattern pattern) {
    Map<String, Long> tokens = new TreeMap<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      String token = matcher.group();
      if (pattern == MUSTACHE_PLACEHOLDER) {
        token = "{{" + matcher.group(1).trim().replaceAll("\\s+", " ") + "}}";
      }
      tokens.merge(token, 1L, Long::sum);
    }
    return tokens;
  }

  private static FindingSpec deterministic(String kind, String explanation) {
    return new FindingSpec(FindingCategory.DETERMINISTIC_INTEGRITY, kind, explanation);
  }

  private static FindingSpec reviewNeeded(String kind, String explanation) {
    return new FindingSpec(FindingCategory.REVIEW_NEEDED, kind, explanation);
  }

  private StructuralFinding toStructuralFinding(DecisionRow row, FindingSpec finding) {
    return new StructuralFinding(
        finding.category().name(),
        finding.kind(),
        finding.explanation(),
        projectRef(row),
        reviewerRef(row),
        row.decisionId(),
        row.reviewProjectTextUnitId(),
        row.tmTextUnitId(),
        row.decisionVariantId(),
        row.currentVariantId(),
        row.decidedAt().toString(),
        row.projectLocale(),
        row.sourceLocale(),
        preview(row.sourceText()),
        preview(row.currentTargetText()),
        buildReviewProjectLink(row.reviewProjectId(), row.tmTextUnitId()));
  }

  private ProjectRef projectRef(DecisionRow row) {
    return new ProjectRef(
        row.reviewProjectId(),
        row.reviewProjectRequestId(),
        row.reviewProjectName(),
        row.reviewProjectType(),
        row.terminologyPhase());
  }

  private static ReviewerRef reviewerRef(DecisionRow row) {
    String username = row.effectiveReviewerUsername();
    String email = username != null && username.contains("@") ? username : null;
    return new ReviewerRef(
        row.effectiveReviewerId(), username, email, row.effectiveReviewerCommonName());
  }

  private String buildReviewProjectLink(Long reviewProjectId, Long tmTextUnitId) {
    if (reviewProjectId == null || tmTextUnitId == null) {
      return null;
    }
    String configuredUrl = serverConfig.getUrl();
    if (configuredUrl == null || configuredUrl.isBlank()) {
      return null;
    }
    String baseUrl = configuredUrl.trim();
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    return baseUrl + "/review-projects/" + reviewProjectId + "?tu=" + tmTextUnitId;
  }

  private boolean isExpectedTargetlessTerminologyDecision(DecisionRow row) {
    return row.decisionVariantId() == null
        && "PM_RESOLUTION".equals(row.terminologyPhase())
        && ("TERMINOLOGY".equals(row.reviewProjectType())
            || "TERM_CANDIDATE".equals(row.reviewProjectType()));
  }

  private static Double durationSeconds(Duration duration) {
    return duration.toNanos() / 1_000_000_000.0;
  }

  private static String legitimateLookingReason(List<DecisionRow> edges) {
    for (DecisionRow edge : edges) {
      String previous = edge.previousSourceText();
      String current = edge.sourceText();
      if (previous == null || current == null) {
        continue;
      }
      String previousNormalized = normalizeComparableSource(previous);
      String currentNormalized = normalizeComparableSource(current);
      if (previousNormalized.equals(currentNormalized)) {
        return "The source texts differ only by case, spacing, or punctuation, so an identical translation may be legitimate.";
      }
      if (simpleSingular(previousNormalized).equals(simpleSingular(currentNormalized))) {
        return "The source texts look like singular/plural variants, which may legitimately share a translation in this locale.";
      }
      if (Math.min(previousNormalized.length(), currentNormalized.length()) > 0
          && (previousNormalized.contains(currentNormalized)
              || currentNormalized.contains(previousNormalized))) {
        return "One source is a close expansion of the other, so an identical translation may be legitimate.";
      }
    }
    return null;
  }

  private static boolean isWhitespaceOnly(String value) {
    return value.isEmpty()
        || value
            .codePoints()
            .allMatch(
                codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint));
  }

  private static String normalizeComparableSource(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", "")
        .trim();
  }

  private static String simpleSingular(String value) {
    if (value.endsWith("ies") && value.length() > 3) {
      return value.substring(0, value.length() - 3) + "y";
    }
    if (value.endsWith("es") && value.length() > 2) {
      return value.substring(0, value.length() - 2);
    }
    if (value.endsWith("s") && value.length() > 1) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static Preview preview(String value) {
    if (value == null) {
      return new Preview(null, false, false);
    }
    String singleLine = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    if (containsSensitivePreviewContent(singleLine)) {
      return new Preview("[REDACTED_SENSITIVE_PREVIEW]", false, true);
    }
    String scrubbed = singleLine;
    int codePointCount = scrubbed.codePointCount(0, scrubbed.length());
    if (codePointCount <= PREVIEW_CODE_POINT_LIMIT) {
      return new Preview(scrubbed, false, false);
    }
    int end = scrubbed.offsetByCodePoints(0, PREVIEW_CODE_POINT_LIMIT);
    return new Preview(scrubbed.substring(0, end) + "…", true, false);
  }

  private static boolean containsSensitivePreviewContent(String value) {
    return PRIVATE_KEY.matcher(value).find()
        || CONNECTION_STRING.matcher(value).find()
        || AUTHORIZATION_HEADER.matcher(value).find()
        || COOKIE_HEADER.matcher(value).find()
        || URL_CREDENTIALS.matcher(value).find()
        || BEARER_SECRET.matcher(value).find()
        || LABELED_SECRET.matcher(value).find()
        || KNOWN_TOKEN.matcher(value).find()
        || HIGH_ENTROPY_TOKEN.matcher(value).find();
  }

  private record FindingSpec(FindingCategory category, String kind, String explanation) {}

  private enum FindingCategory {
    DETERMINISTIC_INTEGRITY,
    REVIEW_NEEDED
  }

  private record IcuSignature(boolean valid, Map<String, Long> arguments) {}

  private record MarkupSignature(boolean balanced, Map<String, Long> tokens) {}

  public record AuditResult(
      String status,
      String generatedAtUtc,
      AuditWindow window,
      Coverage coverage,
      CarryoverSummary carryover,
      BroaderReviewSummary broaderReview,
      String scopeNote) {}

  public record AuditWindow(
      String fromInclusiveUtc, String toExclusiveUtc, double durationSeconds) {}

  public record Coverage(
      long totalDecisions,
      long distinctReviewers,
      long distinctReviewProjects,
      long distinctTextUnits,
      long unattributedDecisions,
      long expectedTargetlessTerminologyDecisions,
      String persistenceCaveat) {}

  public record CarryoverSummary(
      long candidatePairCount,
      long runCount,
      int detailedRunCount,
      boolean detailsTruncated,
      List<CarryoverRun> runs) {}

  public record CarryoverRun(
      int candidatePairCount,
      int decisionCount,
      int detailedDecisionCount,
      boolean decisionsTruncated,
      ProjectRef project,
      ReviewerRef reviewer,
      String locale,
      Preview repeatedTargetPreview,
      String legitimateLookingReason,
      String humanReviewNote,
      List<CarryoverDecision> decisions) {}

  public record CarryoverDecision(
      boolean suspectLaterDecision,
      Long decisionId,
      Long reviewProjectTextUnitId,
      Long tmTextUnitId,
      String decidedAtUtc,
      Double deltaSeconds,
      Preview sourcePreview,
      Preview decisionTargetPreview,
      String mojitoLink) {}

  public record BroaderReviewSummary(
      long deterministicIntegrityFindingCount,
      long deterministicAffectedDecisionCount,
      long reviewNeededFindingCount,
      long reviewNeededAffectedDecisionCount,
      Map<String, Long> countsByKind,
      int detailedFindingCount,
      boolean detailsTruncated,
      List<StructuralFinding> findings) {}

  public record StructuralFinding(
      String category,
      String kind,
      String explanation,
      ProjectRef project,
      ReviewerRef reviewer,
      Long decisionId,
      Long reviewProjectTextUnitId,
      Long tmTextUnitId,
      Long decisionVariantId,
      Long currentVariantId,
      String decidedAtUtc,
      String projectLocale,
      String sourceLocale,
      Preview sourcePreview,
      Preview currentTargetPreview,
      String mojitoLink) {}

  public record ProjectRef(
      Long id, Long requestId, String name, String type, String terminologyPhase) {}

  public record ReviewerRef(Long id, String username, String email, String commonName) {}

  public record Preview(String text, boolean truncated, boolean redacted) {}
}
