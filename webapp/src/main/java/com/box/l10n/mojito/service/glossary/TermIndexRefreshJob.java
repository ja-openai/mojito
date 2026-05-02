package com.box.l10n.mojito.service.glossary;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import org.springframework.beans.factory.annotation.Autowired;

public class TermIndexRefreshJob
    extends QuartzPollableJob<
        TermIndexRefreshService.RefreshCommand, TermIndexRefreshService.RefreshResult> {

  @Autowired TermIndexRefreshService termIndexRefreshService;

  @Override
  public TermIndexRefreshService.RefreshResult call(TermIndexRefreshService.RefreshCommand input) {
    return termIndexRefreshService.refresh(input);
  }
}
