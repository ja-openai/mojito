package com.box.l10n.mojito.service.glossary;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TermIndexJobObservability {

  static final String JOB_REFRESH = "refresh";
  static final String JOB_CANDIDATE_GENERATION = "candidate_generation";
  static final String JOB_EXTRACTED_TERM_TRIAGE = "extracted_term_triage";

  static final String RESULT_STARTED = "started";
  static final String RESULT_SUCCEEDED = "succeeded";
  static final String RESULT_FAILED = "failed";

  static final String PHASE_REFRESH_REPOSITORY = "refresh_repository";
  static final String PHASE_REFRESH_AGGREGATES = "refresh_aggregates";

  static final String TYPE_REFRESH_TEXT_UNIT_BATCH = "refresh_text_unit_batch";
  static final String TYPE_CANDIDATE_GENERATION_BATCH = "candidate_generation_batch";
  static final String TYPE_TRIAGE_BATCH = "triage_batch";
  static final String TYPE_AI_CANDIDATE_ENRICHMENT = "candidate_enrichment";
  static final String TYPE_AI_EXTRACTED_TERM_REVIEW = "extracted_term_review";

  static final String METRIC_JOB_EVENTS = "term_index.job.events";
  static final String METRIC_JOB_DURATION = "term_index.job.duration";
  static final String METRIC_PHASE_EVENTS = "term_index.phase.events";
  static final String METRIC_PHASE_DURATION = "term_index.phase.duration";
  static final String METRIC_BATCH_EVENTS = "term_index.batch.events";
  static final String METRIC_BATCH_DURATION = "term_index.batch.duration";
  static final String METRIC_AI_BATCH_EVENTS = "term_index.ai_batch.events";
  static final String METRIC_AI_BATCH_DURATION = "term_index.ai_batch.duration";

  private final MeterRegistry meterRegistry;

  public TermIndexJobObservability(MeterRegistry meterRegistry) {
    this.meterRegistry = Objects.requireNonNull(meterRegistry);
  }

  Timer.Sample startTimer() {
    return Timer.start(meterRegistry);
  }

  void recordJobStarted(String job) {
    meterRegistry
        .counter(METRIC_JOB_EVENTS, Tags.of("job", job, "result", RESULT_STARTED))
        .increment();
  }

  void recordJobFinished(String job, String result, Timer.Sample sample) {
    Tags tags = Tags.of("job", job, "result", result);
    meterRegistry.counter(METRIC_JOB_EVENTS, tags).increment();
    sample.stop(meterRegistry.timer(METRIC_JOB_DURATION, tags));
  }

  void recordPhase(String job, String phase, String result, Duration duration) {
    Tags tags = Tags.of("job", job, "phase", phase, "result", result);
    meterRegistry.counter(METRIC_PHASE_EVENTS, tags).increment();
    meterRegistry.timer(METRIC_PHASE_DURATION, tags).record(duration);
  }

  void recordBatch(String job, String type, String result, Duration duration) {
    Tags tags = Tags.of("job", job, "type", type, "result", result);
    meterRegistry.counter(METRIC_BATCH_EVENTS, tags).increment();
    meterRegistry.timer(METRIC_BATCH_DURATION, tags).record(duration);
  }

  void recordAiBatch(String job, String type, String result, Duration duration) {
    Tags tags = Tags.of("job", job, "type", type, "result", result);
    meterRegistry.counter(METRIC_AI_BATCH_EVENTS, tags).increment();
    meterRegistry.timer(METRIC_AI_BATCH_DURATION, tags).record(duration);
  }
}
