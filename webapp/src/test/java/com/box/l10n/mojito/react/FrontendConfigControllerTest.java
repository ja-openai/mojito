package com.box.l10n.mojito.react;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * @author jaurambault
 */
public class FrontendConfigControllerTest {

  @Test
  public void testGetValidLocalFromCookie() {
    String localeCookieValue = "fr-FR";
    FrontendConfigController instance = new FrontendConfigController();

    String expResult = "fr-FR";
    String result = instance.getValidLocaleFromCookie(localeCookieValue);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetValidLocalFromCookieInvalidValue() {
    String localeCookieValue = "dsfsazfsdf dsfdsfsfdsf";
    FrontendConfigController instance = new FrontendConfigController();

    String expResult = "en";
    String result = instance.getValidLocaleFromCookie(localeCookieValue);
    assertEquals(expResult, result);
  }

  @Test
  public void testGetValidLocalFromCookieNullValue() {
    String localeCookieValue = null;
    FrontendConfigController instance = new FrontendConfigController();

    String expResult = "en";
    String result = instance.getValidLocaleFromCookie(localeCookieValue);
    assertEquals(expResult, result);
  }
}
