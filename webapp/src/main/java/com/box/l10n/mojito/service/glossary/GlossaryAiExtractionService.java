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
      - `term`
      - `occurrenceCount`
      - `repositoryCount`
      - `repositories`
      - `sampleSources`
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
      - `term`
      - `occurrenceCount`
      - `repositoryCount`
      - `repositories`
      - `sampleSources`
      - `heuristicTermType`

      Return exactly one candidate object for each input candidate.
      Do not filter, reject, drop, merge, rename, or replace candidates.

      For each candidate:
      - preserve the original `term` exactly
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
      String term,
      int occurrenceCount,
      int repositoryCount,
      List<String> repositories,
      List<String> sampleSources,
      String heuristicTermType) {}

  public record CandidateSignalOutput(List<AiCandidateView> candidates) {}

  public record AiCandidateView(
      String term,
      Integer confidence,
      String definition,
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate) {}
}
