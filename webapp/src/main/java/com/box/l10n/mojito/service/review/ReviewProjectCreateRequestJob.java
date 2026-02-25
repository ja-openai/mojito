package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewProjectCreateRequestJob
    extends QuartzPollableJob<CreateReviewProjectRequestCommand, CreateReviewProjectRequestResult> {

  @Autowired ReviewProjectService reviewProjectService;

  @Override
  public CreateReviewProjectRequestResult call(CreateReviewProjectRequestCommand input) {
    return reviewProjectService.createReviewProjectRequest(input);
  }
}
