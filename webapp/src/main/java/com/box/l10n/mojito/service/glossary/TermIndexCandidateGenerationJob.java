package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import org.springframework.beans.factory.annotation.Autowired;

public class TermIndexCandidateGenerationJob
    extends QuartzPollableJob<
        GlossaryTermIndexCurationService.GenerateCandidatesJobCommand,
        GlossaryTermIndexCurationService.GenerateCandidatesResult> {

  @Autowired GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  @Override
  public GlossaryTermIndexCurationService.GenerateCandidatesResult call(
      GlossaryTermIndexCurationService.GenerateCandidatesJobCommand input) {
    Long pollableTaskId = getCurrentPollableTask().getId();
    try {
      return glossaryTermIndexCurationService.generateCandidates(input, pollableTaskId);
    } catch (RuntimeException e) {
      glossaryTermIndexCurationService.markAutomationRunFailed(pollableTaskId, e);
      throw e;
    }
  }
}
