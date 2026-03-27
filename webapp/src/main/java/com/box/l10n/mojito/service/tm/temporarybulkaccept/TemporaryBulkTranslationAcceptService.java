package com.box.l10n.mojito.service.tm.temporarybulkaccept;

import com.box.l10n.mojito.entity.TMTextUnitCurrentVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.box.l10n.mojito.entity.TMTextUnitVariantComment;
import com.box.l10n.mojito.security.AuditorAwareImpl;
import com.box.l10n.mojito.service.pollableTask.Pollable;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.pollableTask.PollableFutureTaskResult;
import com.box.l10n.mojito.service.security.user.UserService;
import com.box.l10n.mojito.service.tm.AddTMTextUnitCurrentVariantResult;
import com.box.l10n.mojito.service.tm.TMService;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantCommentService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Temporary admin-only cleanup service to bulk accept selected REVIEW_NEEDED translations.
 *
 * <p>Delete this file and its paired controller/frontend page when the one-off cleanup is no longer
 * needed.
 */
@Service
public class TemporaryBulkTranslationAcceptService {

  static final int FETCH_PAGE_SIZE = 500;
  static final int WRITE_CHUNK_SIZE = 100;

  public enum Selector {
    PHRASE_IMPORTED_NEEDS_REVIEW,
    NEEDS_REVIEW_OLDER_THAN
  }

  public record Request(Selector selector, List<Long> repositoryIds, LocalDate createdBeforeDate) {}

  public record RepositoryCount(Long repositoryId, String repositoryName, long matchedCount) {}

  public record DryRunResult(long totalMatchedCount, List<RepositoryCount> repositoryCounts) {}

  public record ExecuteResult(long processedCount, List<RepositoryCount> repositoryCounts) {}

  public record TaskResponse(long totalCount, List<RepositoryCount> repositoryCounts) {}

  private final TemporaryBulkTranslationAcceptRepository repository;
  private final TMService tmService;
  private final TMTextUnitVariantCommentService tmTextUnitVariantCommentService;
  private final TransactionTemplate transactionTemplate;
  private final UserService userService;
  private final AuditorAwareImpl auditorAwareImpl;

  public TemporaryBulkTranslationAcceptService(
      TemporaryBulkTranslationAcceptRepository repository,
      TMService tmService,
      TMTextUnitVariantCommentService tmTextUnitVariantCommentService,
      TransactionTemplate transactionTemplate,
      UserService userService,
      AuditorAwareImpl auditorAwareImpl) {
    this.repository = Objects.requireNonNull(repository);
    this.tmService = Objects.requireNonNull(tmService);
    this.tmTextUnitVariantCommentService = Objects.requireNonNull(tmTextUnitVariantCommentService);
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
    this.userService = Objects.requireNonNull(userService);
    this.auditorAwareImpl = Objects.requireNonNull(auditorAwareImpl);
  }

  public DryRunResult dryRun(Request request) {
    ValidatedRequest validatedRequest = validate(request);
    List<RepositoryCount> counts = getRepositoryCounts(validatedRequest);
    long totalMatchedCount = counts.stream().mapToLong(RepositoryCount::matchedCount).sum();
    return new DryRunResult(totalMatchedCount, counts);
  }

  @Pollable(async = true, message = "Run temporary bulk translation accept dry run")
  public PollableFuture<TaskResponse> dryRunAsync(Request request) {
    return new PollableFutureTaskResult<>(toTaskResponse(dryRun(request)));
  }

  public ExecuteResult execute(Request request) {
    ValidatedRequest validatedRequest = validate(request);

    Map<Long, MutableRepositoryCount> processedByRepositoryId = new LinkedHashMap<>();
    while (true) {
      List<TemporaryBulkTranslationAcceptRepository.CandidateRow> candidates =
          fetchCandidateBatch(validatedRequest);
      if (candidates.isEmpty()) {
        break;
      }

      for (int start = 0; start < candidates.size(); start += WRITE_CHUNK_SIZE) {
        int end = Math.min(start + WRITE_CHUNK_SIZE, candidates.size());
        List<TemporaryBulkTranslationAcceptRepository.CandidateRow> chunk =
            candidates.subList(start, end);
        processChunk(chunk, processedByRepositoryId);
      }
    }

    List<RepositoryCount> repositoryCounts =
        processedByRepositoryId.values().stream()
            .map(
                count ->
                    new RepositoryCount(
                        count.repositoryId, count.repositoryName, count.matchedCount))
            .toList();
    long processedCount = repositoryCounts.stream().mapToLong(RepositoryCount::matchedCount).sum();
    return new ExecuteResult(processedCount, repositoryCounts);
  }

  @Pollable(async = true, message = "Execute temporary bulk translation accept")
  public PollableFuture<TaskResponse> executeAsync(Request request) {
    return new PollableFutureTaskResult<>(toTaskResponse(execute(request)));
  }

  private void processChunk(
      List<TemporaryBulkTranslationAcceptRepository.CandidateRow> chunk,
      Map<Long, MutableRepositoryCount> processedByRepositoryId) {
    transactionTemplate.executeWithoutResult(
        transactionStatus -> {
          for (TemporaryBulkTranslationAcceptRepository.CandidateRow candidate : chunk) {
            TMTextUnitCurrentVariant currentVariant =
                repository.findById(candidate.getTmTextUnitCurrentVariantId()).orElse(null);
            if (currentVariant == null || currentVariant.getTmTextUnitVariant() == null) {
              continue;
            }

            TMTextUnitVariant currentTextUnitVariant = currentVariant.getTmTextUnitVariant();
            if (!TMTextUnitVariant.Status.REVIEW_NEEDED.equals(
                currentTextUnitVariant.getStatus())) {
              continue;
            }

            Long sourceVariantId = currentTextUnitVariant.getId();
            AddTMTextUnitCurrentVariantResult result =
                tmService.addTMTextUnitCurrentVariantWithResult(
                    currentVariant,
                    currentVariant.getTm().getId(),
                    currentVariant.getAsset().getId(),
                    currentVariant.getTmTextUnit().getId(),
                    currentVariant.getLocale().getId(),
                    currentTextUnitVariant.getContent(),
                    currentTextUnitVariant.getComment(),
                    TMTextUnitVariant.Status.APPROVED,
                    currentTextUnitVariant.isIncludedInLocalizedFile(),
                    null,
                    auditorAwareImpl.getCurrentAuditor().orElse(null));

            if (!result.isTmTextUnitCurrentVariantUpdated()
                || result.getTmTextUnitCurrentVariant() == null
                || result.getTmTextUnitCurrentVariant().getTmTextUnitVariant() == null) {
              continue;
            }

            Long targetVariantId =
                result.getTmTextUnitCurrentVariant().getTmTextUnitVariant().getId();
            if (!Objects.equals(sourceVariantId, targetVariantId)) {
              tmTextUnitVariantCommentService.copyComments(sourceVariantId, targetVariantId);
            }

            processedByRepositoryId.computeIfAbsent(
                        candidate.getRepositoryId(),
                        ignored ->
                            new MutableRepositoryCount(
                                candidate.getRepositoryId(), candidate.getRepositoryName()))
                    .matchedCount +=
                1;
          }
        });
  }

  private List<TemporaryBulkTranslationAcceptRepository.CandidateRow> fetchCandidateBatch(
      ValidatedRequest request) {
    PageRequest pageRequest = PageRequest.of(0, FETCH_PAGE_SIZE);

    return switch (request.selector) {
      case PHRASE_IMPORTED_NEEDS_REVIEW ->
          repository.findPhraseImportedNeedsReviewCandidates(
              request.repositoryIds,
              TMTextUnitVariant.Status.REVIEW_NEEDED,
              TMTextUnitVariantComment.Type.THIRD_PARTY_TMS_PULL,
              pageRequest);
      case NEEDS_REVIEW_OLDER_THAN ->
          repository.findNeedsReviewOlderThanCandidates(
              request.repositoryIds,
              TMTextUnitVariant.Status.REVIEW_NEEDED,
              request.createdBefore,
              pageRequest);
    };
  }

  private List<RepositoryCount> getRepositoryCounts(ValidatedRequest request) {
    List<TemporaryBulkTranslationAcceptRepository.RepositoryCountRow> rows =
        switch (request.selector) {
          case PHRASE_IMPORTED_NEEDS_REVIEW ->
              repository.countPhraseImportedNeedsReviewByRepository(
                  request.repositoryIds,
                  TMTextUnitVariant.Status.REVIEW_NEEDED,
                  TMTextUnitVariantComment.Type.THIRD_PARTY_TMS_PULL);
          case NEEDS_REVIEW_OLDER_THAN ->
              repository.countNeedsReviewOlderThanByRepository(
                  request.repositoryIds,
                  TMTextUnitVariant.Status.REVIEW_NEEDED,
                  request.createdBefore);
        };

    return rows.stream()
        .map(
            row ->
                new RepositoryCount(
                    row.getRepositoryId(), row.getRepositoryName(), row.getMatchedCount()))
        .toList();
  }

  private TaskResponse toTaskResponse(DryRunResult result) {
    return new TaskResponse(result.totalMatchedCount(), result.repositoryCounts());
  }

  private TaskResponse toTaskResponse(ExecuteResult result) {
    return new TaskResponse(result.processedCount(), result.repositoryCounts());
  }

  private ValidatedRequest validate(Request request) {
    if (!userService.isCurrentUserAdmin()) {
      throw new AccessDeniedException("Admin role is required");
    }
    if (request == null || request.selector() == null) {
      throw new IllegalArgumentException("selector is required");
    }
    if (request.repositoryIds() == null || request.repositoryIds().isEmpty()) {
      throw new IllegalArgumentException("repositoryIds is required");
    }

    List<Long> repositoryIds =
        request.repositoryIds().stream()
            .filter(Objects::nonNull)
            .collect(
                java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
    if (repositoryIds.isEmpty()) {
      throw new IllegalArgumentException("repositoryIds is required");
    }

    ZonedDateTime createdBefore = null;
    if (Selector.NEEDS_REVIEW_OLDER_THAN.equals(request.selector())) {
      if (request.createdBeforeDate() == null) {
        throw new IllegalArgumentException("createdBeforeDate is required");
      }
      createdBefore = request.createdBeforeDate().atStartOfDay(ZoneId.systemDefault());
    }

    return new ValidatedRequest(request.selector(), repositoryIds, createdBefore);
  }

  private record ValidatedRequest(
      Selector selector, List<Long> repositoryIds, ZonedDateTime createdBefore) {}

  private static class MutableRepositoryCount {
    final Long repositoryId;
    final String repositoryName;
    long matchedCount;

    MutableRepositoryCount(Long repositoryId, String repositoryName) {
      this.repositoryId = repositoryId;
      this.repositoryName = repositoryName;
    }
  }
}
