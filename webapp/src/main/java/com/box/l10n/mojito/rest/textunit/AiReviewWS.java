package com.box.l10n.mojito.rest.textunit;

import com.box.l10n.mojito.entity.AiReviewProto;
import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.rest.textunit.AiReviewType.AiReviewTextUnitVariantOutput;
import com.box.l10n.mojito.service.oaireview.AiReviewService;
import com.box.l10n.mojito.service.oaireview.AiReviewService.AiReviewTextUnitVariantInput;
import com.box.l10n.mojito.service.pollableTask.PollableFuture;
import com.box.l10n.mojito.service.tm.AiReviewProtoRepository;
import com.box.l10n.mojito.service.tm.TMTextUnitVariantRepository;
import com.box.l10n.mojito.service.tm.search.TextUnitDTO;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcher;
import com.box.l10n.mojito.service.tm.search.TextUnitSearcherParameters;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiReviewWS {

  /** logger */
  static Logger logger = LoggerFactory.getLogger(AiReviewWS.class);

  static final String RUN_NAME_FOR_FRONTEND = "for-frontend";
  static final String PRECOMPUTED_REVIEW_LOOKUP_METRIC = "AiReviewWS.precomputedReviewLookup";

  AiReviewProtoRepository aiReviewProtoRepository;

  ObjectMapper objectMapper;

  TextUnitSearcher textUnitSearcher;

  AiReviewService aiReviewService;

  TMTextUnitVariantRepository tmTextUnitVariantRepository;

  MeterRegistry meterRegistry;

  public AiReviewWS(
      TextUnitSearcher textUnitSearcher,
      AiReviewService aiReviewService,
      AiReviewProtoRepository aiReviewProtoRepository,
      TMTextUnitVariantRepository tmTextUnitVariantRepository,
      @Qualifier("objectMapperReview") ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.textUnitSearcher = textUnitSearcher;
    this.aiReviewService = aiReviewService;
    this.aiReviewProtoRepository = aiReviewProtoRepository;
    this.tmTextUnitVariantRepository = tmTextUnitVariantRepository;
    this.objectMapper = objectMapper;
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  @RequestMapping(method = RequestMethod.POST, value = "/api/proto-ai-review")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiReviewResponse aiReview(@RequestBody ProtoAiReviewRequest protoAiReviewRequest) {

    PollableFuture<Void> pollableFuture =
        aiReviewService.aiReviewAsync(
            new AiReviewService.AiReviewInput(
                protoAiReviewRequest.repositoryName(),
                protoAiReviewRequest.targetBcp47tags(),
                protoAiReviewRequest.sourceTextMaxCountPerLocale(),
                protoAiReviewRequest.tmTextUnitIds(),
                protoAiReviewRequest.useBatch(),
                protoAiReviewRequest.useModel(),
                protoAiReviewRequest.runName(),
                protoAiReviewRequest.reviewType()));

    return new ProtoAiReviewResponse(pollableFuture.getPollableTask());
  }

  public record ProtoAiReviewRequest(
      String repositoryName,
      List<String> targetBcp47tags,
      int sourceTextMaxCountPerLocale,
      boolean useBatch,
      String useModel,
      String runName,
      List<Long> tmTextUnitIds,
      boolean allLocales,
      String reviewType) {}

  public record ProtoAiReviewResponse(PollableTask pollableTask) {}

  @RequestMapping(method = RequestMethod.GET, value = "/api/proto-ai-review-single-text-unit")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiReviewSingleTextUnitResponse getAiReviewForSingleTextUnit(
      ProtoAiReviewSingleTextUnitRequest protoAiReviewSingleTextUnitRequest,
      @RequestParam(defaultValue = "false") boolean onlyPrecomputed) {

    AiReviewProto alreadyReviewed =
        aiReviewProtoRepository.findByTmTextUnitVariantIdAndRunName(
            protoAiReviewSingleTextUnitRequest.tmTextUnitVariantId(), RUN_NAME_FOR_FRONTEND);

    AiReviewTextUnitVariantOutput aiReviewTextUnitVariantOutput = null;
    String precomputedLookupResult = alreadyReviewed == null ? "miss" : "hit";

    if (alreadyReviewed != null) {
      try {
        aiReviewTextUnitVariantOutput =
            objectMapper.readValueUnchecked(
                alreadyReviewed.getJsonReview(), AiReviewTextUnitVariantOutput.class);
        if (!hasUsefulReviewOutput(aiReviewTextUnitVariantOutput)) {
          aiReviewTextUnitVariantOutput = null;
          precomputedLookupResult = "empty";
        }
      } catch (RuntimeException e) {
        precomputedLookupResult = "unreadable";
        logger.warn(
            "Can't deserialize the existing AI review{}",
            onlyPrecomputed ? "; returning cache miss for cache-only request" : ", will recompute");
      }
    }

    TextUnitDTO textUnit = null;
    if (!onlyPrecomputed || aiReviewTextUnitVariantOutput != null) {
      textUnit = findTextUnitForVariantId(protoAiReviewSingleTextUnitRequest.tmTextUnitVariantId);
      if (textUnit == null) {
        if (aiReviewTextUnitVariantOutput != null) {
          precomputedLookupResult = "stale";
        }
        recordPrecomputedReviewLookup(onlyPrecomputed, precomputedLookupResult);
        if (onlyPrecomputed) {
          return new ProtoAiReviewSingleTextUnitResponse(null, null);
        }
        throw new RuntimeException("Wrong tmTextUnitVariantId");
      }
    }

    recordPrecomputedReviewLookup(onlyPrecomputed, precomputedLookupResult);

    if (onlyPrecomputed) {
      return new ProtoAiReviewSingleTextUnitResponse(null, aiReviewTextUnitVariantOutput);
    }

    if (aiReviewTextUnitVariantOutput == null) {

      AiReviewTextUnitVariantInput input =
          new AiReviewTextUnitVariantInput(
              textUnit.getTargetLocale(),
              textUnit.getSource(),
              textUnit.getComment(),
              new AiReviewTextUnitVariantInput.ExistingTarget(
                  textUnit.getTarget(), !textUnit.isIncludedInLocalizedFile()));

      aiReviewTextUnitVariantOutput = aiReviewService.getAiReviewSingleTextUnit(input);

      AiReviewProto aiReviewProto =
          alreadyReviewed != null
              ? alreadyReviewed
              : createReviewProto(protoAiReviewSingleTextUnitRequest);
      aiReviewProto.setJsonReview(
          objectMapper.writeValueAsStringUnchecked(aiReviewTextUnitVariantOutput));
      aiReviewProtoRepository.save(aiReviewProto);
    }

    return new ProtoAiReviewSingleTextUnitResponse(textUnit, aiReviewTextUnitVariantOutput);
  }

  private AiReviewProto createReviewProto(
      ProtoAiReviewSingleTextUnitRequest protoAiReviewSingleTextUnitRequest) {
    AiReviewProto aiReviewProto = new AiReviewProto();
    aiReviewProto.setTmTextUnitVariant(
        tmTextUnitVariantRepository.getReferenceById(
            protoAiReviewSingleTextUnitRequest.tmTextUnitVariantId()));
    aiReviewProto.setRunName(RUN_NAME_FOR_FRONTEND);
    return aiReviewProto;
  }

  private TextUnitDTO findTextUnitForVariantId(long tmTextUnitVariantId) {
    TextUnitSearcherParameters textUnitSearcherParameters = new TextUnitSearcherParameters();
    textUnitSearcherParameters.setTmTextUnitVariantId(tmTextUnitVariantId);

    List<TextUnitDTO> search = textUnitSearcher.search(textUnitSearcherParameters);
    return search.isEmpty() ? null : search.getFirst();
  }

  private boolean hasUsefulReviewOutput(AiReviewTextUnitVariantOutput output) {
    if (output == null) {
      return false;
    }
    return hasText(output.target() != null ? output.target().content() : null)
        || hasText(output.altTarget() != null ? output.altTarget().content() : null)
        || hasUsefulExistingTargetRating(output.existingTargetRating())
        || hasText(output.reviewRequired() != null ? output.reviewRequired().reason() : null)
        || (output.reviewRequired() != null && output.reviewRequired().required());
  }

  private boolean hasUsefulExistingTargetRating(
      AiReviewTextUnitVariantOutput.ExistingTargetRating rating) {
    return rating != null && isUsefulRatingScore(rating.score()) && hasText(rating.explanation());
  }

  private boolean isUsefulRatingScore(Integer score) {
    return score != null && score >= 0 && score <= 2;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private void recordPrecomputedReviewLookup(boolean onlyPrecomputed, String result) {
    meterRegistry
        .counter(
            PRECOMPUTED_REVIEW_LOOKUP_METRIC,
            Tags.of(
                "requestMode",
                onlyPrecomputed ? "cache_only" : "live_or_compute",
                "result",
                result))
        .increment();
  }

  public record ProtoAiReviewSingleTextUnitRequest(long tmTextUnitVariantId) {}

  public record ProtoAiReviewSingleTextUnitResponse(
      TextUnitDTO textUnitDTO, AiReviewTextUnitVariantOutput aiReviewOutput) {}

  public record ProtoAiReviewRetryImportRequest(long childPollableTaskId) {}

  public record ProtoAiReviewRetryImportResponse(long pollableTaskId) {}

  @RequestMapping(method = RequestMethod.POST, value = "/api/proto-ai-review/retry-import")
  @ResponseStatus(HttpStatus.OK)
  public ProtoAiReviewRetryImportResponse aiReviewRetryImport(
      @RequestBody ProtoAiReviewRetryImportRequest protoAiReviewRetryImportRequest) {
    PollableFuture<Void> pollableFuture =
        aiReviewService.retryImport(protoAiReviewRetryImportRequest.childPollableTaskId());
    return new ProtoAiReviewRetryImportResponse(pollableFuture.getPollableTask().getId());
  }
}
