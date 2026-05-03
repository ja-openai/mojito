package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.openai.OpenAIClient.ResponsesRequest;
import com.box.l10n.mojito.service.oaireview.AiReviewConfigurationProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class GlossaryAiExtractionService {

  private static final String EXTRACTION_PROMPT =
      """
      You are designing a localization glossary for a software product.

      You will receive JSON with candidate terms gathered from repository strings. Each candidate has:
      - `inputId`
      - `term`
      - `occurrenceCount`
      - `repositoryCount`
      - `repositories`
      - `sampleSources`
      - `sampleContexts` when available, with repository/path/key/source context
      - `heuristicTermType`

      Return only the candidates that should remain glossary candidates.

      Keep terms when they are:
      - product or feature names
      - branded or technical concepts that should stay consistent
      - UI labels that behave like canonical product vocabulary
      - legal / compliance terminology
      - recurring consistency terms that are likely to matter in translation review

      Reject generic UI verbs, vague common nouns, and navigation filler unless the samples clearly
      show that the string is a canonical label.

      For each retained candidate:
      - preserve the original `inputId` exactly when present
      - preserve the original `term` exactly
      - provide a concise `definition` that explains what the term means in product context
      - provide a short `rationale` tied to the provided samples explaining why it belongs in the glossary
      - set `confidence` from 0 to 100
      - suggest `termType`
      - suggest `partOfSpeech` only when it is reasonably clear
      - suggest `enforcement`
      - set `doNotTranslate` for brands / product names / tokens that should usually remain as-is

      Output valid JSON only.
      """;

  private static final String ENRICHMENT_PROMPT =
      """
      You are enriching selected localization glossary candidate terms for a software product.

      You will receive JSON with candidate terms gathered from repository strings. Each candidate has:
      - `inputId`
      - `term`
      - `occurrenceCount`
      - `repositoryCount`
      - `repositories`
      - `sampleSources`
      - `sampleContexts` when available, with repository/path/key/source context
      - `heuristicTermType`

      Return one or more candidate objects for each input candidate.
      Do not filter, reject, drop, merge, rename, or replace input terms.
      If the samples show distinct meanings, product concepts, parts of speech, UI roles, or
      translation treatments for the same term, split the input into separate candidate objects.
      Split by localization meaning, not just dictionary meaning. For example, a term like
      `Thinking` should split if some samples use it as a model capability/style/mode and other
      samples use it as an in-progress action or status label, because those commonly need
      different translations. When the samples provide clearly different UI contexts, prefer
      separate lower-confidence candidates over collapsing them into one generic candidate.

      For each candidate:
      - preserve the original `inputId` exactly
      - preserve the original `term` exactly
      - provide a concise `label` when the term is split, such as `search action`,
        `search feature`, or `browser search`
      - provide a stable `sourceExternalId` when the term is split; make it lowercase,
        short, and tied to the sense, not to wording that may change
      - provide a concise `definition` that explains what the term means in product context
      - provide a short `rationale` tied to the provided samples explaining why it was generated
      - set `confidence` from 0 to 100
      - suggest `termType`
      - suggest `partOfSpeech` only when it is reasonably clear
      - suggest `enforcement`
      - set `doNotTranslate` for brands / product names / tokens that should usually remain as-is
      - if the term appears generic or weak, still return it with lower confidence and explain why

      Output valid JSON only.
      """;

  private static final String EXTRACTED_TERM_REVIEW_PROMPT =
      """
      You are reviewing raw term extractions before they become glossary candidates.

      You will receive JSON with raw terms gathered from repository strings. Each candidate has:
      - `inputId`
      - `term`
      - `occurrenceCount`
      - `repositoryCount`
      - `repositories`
      - `sampleSources`
      - `sampleContexts` when available, with repository/path/key/source context
      - `heuristicTermType`

      Classify every input term with exactly one `reviewStatus`:
      - `ACCEPTED`: clearly useful source terminology for glossary candidate generation.
      - `TO_REVIEW`: plausible but ambiguous; a human should decide.
      - `REJECTED`: should not become a glossary candidate.

      Use `REJECTED` for stop words, generic UI filler, extraction artifacts, false positives,
      or out-of-scope fragments. Reject standalone pronouns, determiners, auxiliaries,
      sentence scaffolding, and common function words such as `You`, `Your`, `We`, or
      `This` unless the samples clearly show a branded or canonical product term.
      Prefer `TO_REVIEW` when the samples are ambiguous.

      For each review:
      - preserve the original `inputId` exactly
      - preserve the original `term` exactly
      - set `reviewReason` to one of `STOP_WORD`, `TOO_GENERIC`, `FALSE_POSITIVE`,
        `OUT_OF_SCOPE`, or `OTHER`
      - provide a concise `reviewRationale` tied to the samples
      - set `reviewConfidence` from 0 to 100

      Output valid JSON only.
      """;

  private final OpenAIClient openAIClient;
  private final AiReviewConfigurationProperties aiReviewConfigurationProperties;
  private final ObjectMapper objectMapper;

  public GlossaryAiExtractionService(
      @Autowired(required = false) @Qualifier("openAIClientReview") OpenAIClient openAIClient,
      AiReviewConfigurationProperties aiReviewConfigurationProperties,
      @Qualifier("objectMapperReview") ObjectMapper objectMapper) {
    this.openAIClient = openAIClient;
    this.aiReviewConfigurationProperties = aiReviewConfigurationProperties;
    this.objectMapper = objectMapper;
  }

  public List<AiCandidateView> refineCandidates(List<CandidateSignal> candidates) {
    return requestCandidates(EXTRACTION_PROMPT, candidates);
  }

  public List<AiCandidateView> enrichCandidates(List<CandidateSignal> candidates) {
    return requestCandidates(ENRICHMENT_PROMPT, candidates);
  }

  public List<AiExtractedTermReviewView> reviewExtractedTerms(List<CandidateSignal> candidates) {
    if (openAIClient == null || candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    String inputJson =
        objectMapper.writeValueAsStringUnchecked(new CandidateSignalInput(candidates));
    ResponsesRequest request =
        ResponsesRequest.builder()
            .model(aiReviewConfigurationProperties.getModelName())
            .instructions(EXTRACTED_TERM_REVIEW_PROMPT)
            .reasoningEffort(aiReviewConfigurationProperties.getResponses().getReasoningEffort())
            .textVerbosity(aiReviewConfigurationProperties.getResponses().getTextVerbosity())
            .addUserText(inputJson)
            .addJsonSchema(ExtractedTermReviewOutput.class)
            .build();

    int charCount = inputJson.length();
    Duration timeout =
        aiReviewConfigurationProperties
            .getTimeout()
            .resolveRequestTimeout(
                1, charCount, aiReviewConfigurationProperties.getResponses().getReasoningEffort());

    String jsonResponse = openAIClient.getResponses(request, timeout).join().outputText();
    ExtractedTermReviewOutput output =
        objectMapper.readValueUnchecked(jsonResponse, ExtractedTermReviewOutput.class);
    return output == null || output.reviews() == null ? List.of() : output.reviews();
  }

  private List<AiCandidateView> requestCandidates(
      String instructions, List<CandidateSignal> candidates) {
    if (openAIClient == null || candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    String inputJson =
        objectMapper.writeValueAsStringUnchecked(new CandidateSignalInput(candidates));
    ResponsesRequest request =
        ResponsesRequest.builder()
            .model(aiReviewConfigurationProperties.getModelName())
            .instructions(instructions)
            .reasoningEffort(aiReviewConfigurationProperties.getResponses().getReasoningEffort())
            .textVerbosity(aiReviewConfigurationProperties.getResponses().getTextVerbosity())
            .addUserText(inputJson)
            .addJsonSchema(CandidateSignalOutput.class)
            .build();

    int charCount = inputJson.length();
    Duration timeout =
        aiReviewConfigurationProperties
            .getTimeout()
            .resolveRequestTimeout(
                1, charCount, aiReviewConfigurationProperties.getResponses().getReasoningEffort());

    String jsonResponse = openAIClient.getResponses(request, timeout).join().outputText();
    CandidateSignalOutput output =
        objectMapper.readValueUnchecked(jsonResponse, CandidateSignalOutput.class);
    return output == null || output.candidates() == null ? List.of() : output.candidates();
  }

  public record CandidateSignalInput(List<CandidateSignal> candidates) {}

  public record CandidateSignal(
      String inputId,
      String term,
      int occurrenceCount,
      int repositoryCount,
      List<String> repositories,
      List<String> sampleSources,
      List<SampleContext> sampleContexts,
      String heuristicTermType) {}

  public record SampleContext(
      String repositoryName,
      String assetPath,
      String textUnitName,
      String matchedText,
      String sourceText) {}

  public record CandidateSignalOutput(List<AiCandidateView> candidates) {}

  public record AiCandidateView(
      String inputId,
      String term,
      String label,
      String sourceExternalId,
      Integer confidence,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate) {}

  public record ExtractedTermReviewOutput(List<AiExtractedTermReviewView> reviews) {}

  public record AiExtractedTermReviewView(
      String inputId,
      String term,
      String reviewStatus,
      String reviewReason,
      String reviewRationale,
      Integer reviewConfidence) {}
}
