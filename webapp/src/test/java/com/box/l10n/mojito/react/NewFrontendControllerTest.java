package com.box.l10n.mojito.react;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.web.bind.annotation.RequestMapping;

public class NewFrontendControllerTest {

  @Test
  public void forwardNewAppUsesRootIndex() {
    NewFrontendController controller = new NewFrontendController();

    assertEquals("forward:/index.html", controller.forwardNewApp());
  }

  @Test
  public void forwardNewAppIncludesTranslationIncidentsRoute() throws Exception {
    RequestMapping requestMapping =
        NewFrontendController.class.getMethod("forwardNewApp").getAnnotation(RequestMapping.class);

    Assert.assertNotNull(requestMapping);
    assertTrue(Arrays.asList(requestMapping.value()).contains("/translation-incidents"));
  }

  @Test
  public void legacyPrefixRedirectStripsNPrefix() {
    NewFrontendController controller = new NewFrontendController();

    assertEquals(
        "/review-projects/12?requestId=7",
        controller.getLegacyRedirectTarget("/n/review-projects/12", "", "requestId=7"));
  }

  @Test
  public void legacyPrefixRedirectFallsBackToRoot() {
    NewFrontendController controller = new NewFrontendController();

    assertEquals("/", controller.getLegacyRedirectTarget("/n", "", null));
  }

  @Test
  public void legacyPrefixRedirectRemovesContextPath() {
    NewFrontendController controller = new NewFrontendController();

    assertEquals(
        "/settings/system",
        controller.getLegacyRedirectTarget("/mojito/n/settings/system", "/mojito", null));
  }
}
