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
      - provide a short rationale tied to the provided samples
      - set `confidence` from 0 to 100
      - suggest `termType`
      - suggest `partOfSpeech` only when it is reasonably clear
      - suggest `enforcement`
      - set `doNotTranslate` for brands / product names / tokens that should usually remain as-is

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
    if (openAIClient == null || candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    ResponsesRequest request =
        ResponsesRequest.builder()
            .model(aiReviewConfigurationProperties.getModelName())
            .instructions(EXTRACTION_PROMPT)
            .reasoningEffort(aiReviewConfigurationProperties.getResponses().getReasoningEffort())
            .textVerbosity(aiReviewConfigurationProperties.getResponses().getTextVerbosity())
            .addUserText(
                objectMapper.writeValueAsStringUnchecked(new CandidateSignalInput(candidates)))
            .addJsonSchema(CandidateSignalOutput.class)
            .build();

    int charCount =
        objectMapper.writeValueAsStringUnchecked(new CandidateSignalInput(candidates)).length();
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
      String rationale,
      String termType,
      String partOfSpeech,
      String enforcement,
      Boolean doNotTranslate) {}
}
