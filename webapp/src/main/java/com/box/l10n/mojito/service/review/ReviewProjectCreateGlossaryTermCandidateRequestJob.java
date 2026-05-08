package com.box.l10n.mojito.service.review;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewProjectCreateGlossaryTermCandidateRequestJob
    extends QuartzPollableJob<
        CreateGlossaryTermCandidateReviewProjectJobInput, CreateReviewProjectRequestResult> {

  @Autowired ReviewProjectService reviewProjectService;

  @Override
  public CreateReviewProjectRequestResult call(
      CreateGlossaryTermCandidateReviewProjectJobInput input) {
    return reviewProjectService.createGlossaryTermCandidateReviewProject(input);
  }
}
