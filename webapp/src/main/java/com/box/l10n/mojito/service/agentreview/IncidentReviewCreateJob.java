package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Result;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobService.Command;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IncidentReviewCreateJob extends QuartzPollableJob<Command, Result> {
  @Autowired IncidentReviewJobService service;

  @Override
  public Result call(Command input) {
    return service.create(input, getCurrentPollableTask().getId());
  }
}
