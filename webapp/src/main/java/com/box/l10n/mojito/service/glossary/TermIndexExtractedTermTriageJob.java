package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import org.springframework.beans.factory.annotation.Autowired;

public class TermIndexExtractedTermTriageJob
    extends QuartzPollableJob<
        GlossaryTermIndexCurationService.TriageExtractedTermsCommand,
        GlossaryTermIndexCurationService.TriageExtractedTermsResult> {

  @Autowired GlossaryTermIndexCurationService glossaryTermIndexCurationService;

  @Override
  public GlossaryTermIndexCurationService.TriageExtractedTermsResult call(
      GlossaryTermIndexCurationService.TriageExtractedTermsCommand input) {
    return glossaryTermIndexCurationService.triageExtractedTerms(
        input, getCurrentPollableTask().getId());
  }
}
