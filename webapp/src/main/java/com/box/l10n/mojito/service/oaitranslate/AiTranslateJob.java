package com.box.l10n.mojito.service.oaitranslate;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateService.AiTranslateInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Class to process a batch of strings for machine translation against a set of target languages.
 *
 * @author garion
 */
@Component
public class AiTranslateJob extends QuartzPollableJob<AiTranslateInput, Void> {

  static Logger logger = LoggerFactory.getLogger(AiTranslateJob.class);

  @Autowired AiTranslateService aiTranslateService;
  @Autowired AiTranslateRunService aiTranslateRunService;

  @Override
  public Void call(AiTranslateInput aiTranslateJobInput) throws Exception {
    Long pollableTaskId = getCurrentPollableTask().getId();
    aiTranslateRunService.markRunning(pollableTaskId);
    try {
      AiTranslateService.AiTranslateRunTotals totals =
          aiTranslateService.aiTranslate(aiTranslateJobInput, getCurrentPollableTask());
      aiTranslateRunService.markCompleted(pollableTaskId, totals);
      return null;
    } catch (Exception e) {
      aiTranslateRunService.markFailed(pollableTaskId);
      throw e;
    }
  }
}
