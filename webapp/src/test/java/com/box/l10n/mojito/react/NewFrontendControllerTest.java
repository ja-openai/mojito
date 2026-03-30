package com.box.l10n.mojito.react;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NewFrontendControllerTest {

  @Test
  public void forwardNewAppUsesRootIndex() {
    NewFrontendController controller = new NewFrontendController();

    assertEquals("forward:/index.html", controller.forwardNewApp());
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
