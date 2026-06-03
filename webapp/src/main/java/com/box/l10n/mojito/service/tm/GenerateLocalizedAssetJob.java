package com.box.l10n.mojito.service.tm;

import com.box.l10n.mojito.quartz.QuartzPollableJob;
import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import org.springframework.beans.factory.annotation.Autowired;

public class GenerateLocalizedAssetJob
    extends QuartzPollableJob<LocalizedAssetBody, LocalizedAssetBody> {

  @Autowired LocalizedAssetGenerationService localizedAssetGenerationService;

  @Override
  public LocalizedAssetBody call(LocalizedAssetBody localizedAssetBody) throws Exception {
    return localizedAssetGenerationService.generate(localizedAssetBody);
  }
}
