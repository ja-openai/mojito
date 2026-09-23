package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.service.agentreview.IncidentReviewJobService.Command;
import com.box.l10n.mojito.service.agentreview.ManualIncidentReviewService.Preview;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class IncidentReviewPreviewJob extends QuartzPollableJob<Command, Preview> {
  @Autowired IncidentReviewJobService service;

  @Override
  public Preview call(Command input) {
    return service.preview(input, getCurrentPollableTask().getId());
  }
}
