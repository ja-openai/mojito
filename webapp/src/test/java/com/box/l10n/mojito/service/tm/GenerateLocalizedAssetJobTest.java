package com.box.l10n.mojito.service.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.box.l10n.mojito.rest.asset.LocalizedAssetBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GenerateLocalizedAssetJobTest {

  @Mock LocalizedAssetGenerationService localizedAssetGenerationService;

  GenerateLocalizedAssetJob generateLocalizedAssetJob = new GenerateLocalizedAssetJob();

  @Before
  public void setUp() {
    generateLocalizedAssetJob.localizedAssetGenerationService = localizedAssetGenerationService;
  }

  @Test
  public void callDelegatesToSharedGenerationService() throws Exception {
    LocalizedAssetBody input = new LocalizedAssetBody();
    LocalizedAssetBody output = new LocalizedAssetBody();
    when(localizedAssetGenerationService.generate(input)).thenReturn(output);

    assertThat(generateLocalizedAssetJob.call(input)).isSameAs(output);
    verify(localizedAssetGenerationService).generate(input);
  }
}
