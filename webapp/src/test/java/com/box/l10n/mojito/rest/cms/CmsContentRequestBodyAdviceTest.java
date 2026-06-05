package com.box.l10n.mojito.rest.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.cms.CmsContentService;
import java.lang.reflect.Method;
import org.junit.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

public class CmsContentRequestBodyAdviceTest {

  private final CmsContentRequestBodyAdvice advice =
      new CmsContentRequestBodyAdvice(new ObjectMapper());

  @Test
  public void beforeBodyReadRejectsDuplicateCmsRequestObjectKeys() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage(
                        "{\"expectedVersion\":1,\"expectedVersion\":2}".getBytes()),
                    updateEntryRequestParameter(),
                    CmsContentService.EntryUpdateCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("unique object keys");
  }

  @Test
  public void beforeBodyReadRejectsTrailingCmsRequestJsonDocument() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage("{\"expectedVersion\":1} {}".getBytes()),
                    updateEntryRequestParameter(),
                    CmsContentService.EntryUpdateCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("one valid JSON document");
  }

  @Test
  public void beforeBodyReadRejectsOversizedCmsRequestBody() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage(new byte[1024 * 1024 + 1]),
                    updateEntryRequestParameter(),
                    CmsContentService.EntryUpdateCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("at most 1048576 bytes");
  }

  @Test
  public void beforeBodyReadRejectsUnknownCmsRequestFields() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage("{\"statuz\":\"READY\"}".getBytes()),
                    updateEntryRequestParameter(),
                    CmsContentService.EntryUpdateCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("no unknown fields");
  }

  @Test
  public void beforeBodyReadRejectsNullRequiredCmsRequestBody() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage("null".getBytes()),
                    updateEntryRequestParameter(),
                    CmsContentService.EntryUpdateCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("must not be JSON null");
  }

  @Test
  public void beforeBodyReadRejectsNullPublishRequestBody() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage("null".getBytes()),
                    publishProjectRequestParameter(),
                    CmsContentService.PublishCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("must not be JSON null");
  }

  @Test
  public void beforeBodyReadRejectsNullUnmapFieldMappingRequestBody() throws Exception {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage("null".getBytes()),
                    unmapFieldMappingRequestParameter(),
                    CmsContentService.FieldMappingDeleteCommand.class,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("must not be JSON null");
  }

  @Test
  public void springMvcRejectsDuplicateCmsRequestKeysBeforeControllerBinding() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService))
            .setControllerAdvice(advice)
            .build();

    mockMvc
        .perform(
            patch("/api/content-cms/entries/12")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"expectedVersion\":2}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).updateEntry(anyLong(), any());
  }

  @Test
  public void springMvcRejectsUnknownCmsRequestFieldsBeforeControllerBinding() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService))
            .setControllerAdvice(advice)
            .build();

    mockMvc
        .perform(
            patch("/api/content-cms/entries/12")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"statuz\":\"READY\"}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).updateEntry(anyLong(), any());
  }

  @Test
  public void springMvcRejectsNullRequiredCmsRequestBodyBeforeControllerBinding() throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService))
            .setControllerAdvice(advice)
            .build();

    mockMvc
        .perform(
            patch("/api/content-cms/entries/12")
                .contentType(MediaType.APPLICATION_JSON)
                .content("null"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).updateEntry(anyLong(), any());
  }

  @Test
  public void beforeBodyReadPreservesValidCmsRequestBodyForBinding() throws Exception {
    String requestJson = "{\"name\":\"Welcome\",\"expectedVersion\":2}";

    String bufferedRequestJson =
        new String(
            advice
                .beforeBodyRead(
                    new MockHttpInputMessage(requestJson.getBytes()),
                    updateEntryRequestParameter(),
                    CmsContentService.EntryUpdateCommand.class,
                    MappingJackson2HttpMessageConverter.class)
                .getBody()
                .readAllBytes());

    assertThat(bufferedRequestJson).isEqualTo(requestJson);
  }

  private MethodParameter updateEntryRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "updateEntry", Long.class, CmsContentService.EntryUpdateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter publishProjectRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "publishProject", Long.class, String.class, CmsContentService.PublishCommand.class);
    return new MethodParameter(method, 2);
  }

  private MethodParameter unmapFieldMappingRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "unmapFieldMapping", Long.class, CmsContentService.FieldMappingDeleteCommand.class);
    return new MethodParameter(method, 1);
  }
}
