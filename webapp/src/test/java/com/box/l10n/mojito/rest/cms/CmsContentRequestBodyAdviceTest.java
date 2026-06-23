package com.box.l10n.mojito.rest.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.cms.CmsContentService;
import java.lang.reflect.Method;
import java.util.List;
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
  public void beforeBodyReadRejectsNullNonNullableCmsCommandFields() throws Exception {
    assertRejectsNullCommandField(
        updateProjectRequestParameter(),
        CmsContentService.ProjectUpdateCommand.class,
        "{\"enabled\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateProjectRequestParameter(),
        CmsContentService.ProjectUpdateCommand.class,
        "{\"deliveryHint\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateContentTypeRequestParameter(),
        CmsContentService.ContentTypeUpdateCommand.class,
        "{\"schemaVersion\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldUpdateCommand.class,
        "{\"fieldType\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldUpdateCommand.class,
        "{\"localizable\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldUpdateCommand.class,
        "{\"required\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldUpdateCommand.class,
        "{\"sortOrder\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateEntryRequestParameter(),
        CmsContentService.EntryUpdateCommand.class,
        "{\"status\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateVariantRequestParameter(),
        CmsContentService.VariantUpdateCommand.class,
        "{\"status\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateVariantRequestParameter(),
        CmsContentService.VariantUpdateCommand.class,
        "{\"sortOrder\":null,\"expectedVersion\":1}");
    assertRejectsNullCommandField(
        updateEntryRequestParameter(),
        CmsContentService.EntryUpdateCommand.class,
        "{\"expectedVersion\":null}");
    assertRejectsNullCommandField(
        makeEntryCopyPiecesPrivateRequestParameter(),
        CmsContentService.EntryCopyPiecesPrivateCommand.class,
        "{\"expectedVersion\":null}");
    assertRejectsNullCommandField(
        addTargetLocalesRequestParameter(),
        CmsContentService.TargetLocalesCommand.class,
        "{\"localeTags\":null}");
    assertRejectsNullCommandField(
        createEntryRequestParameter(),
        CmsContentService.EntryCommand.class,
        "{\"contentTypeId\":null}");
    assertRejectsNullCommandField(
        createEntryRequestParameter(),
        CmsContentService.EntryCommand.class,
        "{\"initialFieldMappings\":[{\"fieldId\":null}]}");
    assertRejectsNullCommandField(
        createContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldCommand.class,
        "{\"initialFieldSource\":{\"variantId\":null}}");
    assertRejectsNullCommandField(
        upsertFieldMappingRequestParameter(),
        CmsContentService.FieldMappingCommand.class,
        "{\"fieldId\":null}");
    assertRejectsNullCommandField(
        unmapFieldMappingRequestParameter(),
        CmsContentService.FieldMappingDeleteCommand.class,
        "{\"expectedVersion\":null}");
  }

  @Test
  public void beforeBodyReadRejectsCoercedCmsCommandScalars() throws Exception {
    assertRejectsCoercedCommandScalar(
        updateEntryRequestParameter(),
        CmsContentService.EntryUpdateCommand.class,
        "{\"expectedVersion\":\"1\"}");
    assertRejectsCoercedCommandScalar(
        updateContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldUpdateCommand.class,
        "{\"sortOrder\":1.5,\"expectedVersion\":1}");
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
  public void springMvcRejectsNullNonNullableCmsCommandFieldBeforeControllerBinding()
      throws Exception {
    CmsContentService cmsContentService = mock(CmsContentService.class);
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new CmsContentWS(cmsContentService))
            .setControllerAdvice(advice)
            .build();

    mockMvc
        .perform(
            patch("/api/content-cms/entries/12")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":null,\"expectedVersion\":1}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).updateEntry(anyLong(), any());

    mockMvc
        .perform(
            post("/api/content-cms/projects/12/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentTypeId\":null}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).createEntry(anyLong(), any());

    mockMvc
        .perform(
            post("/api/content-cms/variants/12/field-mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fieldId\":null}"))
        .andExpect(status().isBadRequest());

    verify(cmsContentService, never()).upsertFieldMapping(anyLong(), any());
  }

  @Test
  public void beforeBodyReadPreservesValidCmsRequestBodyForBinding() throws Exception {
    String requestJson = "{\"name\":\"Welcome\",\"expectedVersion\":2}";

    assertPreservesValidRequestBody(
        updateEntryRequestParameter(), CmsContentService.EntryUpdateCommand.class, requestJson);
  }

  @Test
  public void beforeBodyReadPreservesOptionalCmsCreateSourceHandoffsForBinding() throws Exception {
    assertPreservesValidRequestBody(
        createProjectRequestParameter(),
        CmsContentService.ProjectCommand.class,
        "{\"projectKey\":\"campaign-copy\",\"name\":\"Campaign copy\",\"enabled\":true,\"repositoryId\":null}");
    assertPreservesValidRequestBody(
        createFirstCopyBlockRequestParameter(),
        CmsContentService.FirstCopyBlockCommand.class,
        "{\"entryKey\":\"welcome-email\",\"entryName\":\"Welcome email\",\"entryDescription\":\"Signup email\",\"fieldKey\":\"copy\",\"sourceContent\":\"Welcome\",\"sourceComment\":\"Headline\"}");
    assertPreservesValidRequestBody(
        makeEntryCopyPiecesPrivateRequestParameter(),
        CmsContentService.EntryCopyPiecesPrivateCommand.class,
        "{\"expectedVersion\":3}");
    assertPreservesValidRequestBody(
        addTargetLocalesRequestParameter(),
        CmsContentService.TargetLocalesCommand.class,
        "{\"localeTags\":[\"fr-FR\",\"ja-JP\"]}");
    assertPreservesValidRequestBody(
        createContentTypeFieldRequestParameter(),
        CmsContentService.ContentTypeFieldCommand.class,
        "{\"fieldKey\":\"cta\",\"name\":\"CTA\",\"fieldType\":\"TEXT\",\"localizable\":true,\"required\":true,\"sortOrder\":1,\"initialFieldSource\":{\"variantId\":21,\"sourceContent\":\"Start now\",\"sourceComment\":\"Button label\"}}");
    assertPreservesValidRequestBody(
        createEntryRequestParameter(),
        CmsContentService.EntryCommand.class,
        "{\"contentTypeId\":12,\"entryKey\":\"welcome-email\",\"name\":\"Welcome email\",\"status\":\"DRAFT\",\"initialFieldMappings\":[{\"fieldId\":13,\"sourceContent\":\"Welcome\",\"sourceComment\":\"Headline\"}]}");
  }

  private MethodParameter updateEntryRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "updateEntry", Long.class, CmsContentService.EntryUpdateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter createProjectRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod("createProject", CmsContentService.ProjectCommand.class);
    return new MethodParameter(method, 0);
  }

  private MethodParameter updateProjectRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "updateProject", Long.class, CmsContentService.ProjectUpdateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter updateContentTypeRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "updateContentType", Long.class, CmsContentService.ContentTypeUpdateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter createEntryRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "createEntry", Long.class, CmsContentService.EntryCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter createFirstCopyBlockRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "createFirstCopyBlock", Long.class, CmsContentService.FirstCopyBlockCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter makeEntryCopyPiecesPrivateRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "makeEntryCopyPiecesPrivate",
            Long.class,
            CmsContentService.EntryCopyPiecesPrivateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter addTargetLocalesRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "addTargetLocales", Long.class, CmsContentService.TargetLocalesCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter updateContentTypeFieldRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "updateContentTypeField",
            Long.class,
            CmsContentService.ContentTypeFieldUpdateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter createContentTypeFieldRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "createContentTypeField", Long.class, CmsContentService.ContentTypeFieldCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter updateVariantRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "updateVariant", Long.class, CmsContentService.VariantUpdateCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter publishProjectRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "publishProject", Long.class, List.class, CmsContentService.PublishCommand.class);
    return new MethodParameter(method, 2);
  }

  private MethodParameter upsertFieldMappingRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "upsertFieldMapping", Long.class, CmsContentService.FieldMappingCommand.class);
    return new MethodParameter(method, 1);
  }

  private MethodParameter unmapFieldMappingRequestParameter() throws Exception {
    Method method =
        CmsContentWS.class.getMethod(
            "unmapFieldMapping", Long.class, CmsContentService.FieldMappingDeleteCommand.class);
    return new MethodParameter(method, 1);
  }

  private void assertRejectsNullCommandField(
      MethodParameter requestParameter, Class<?> commandType, String requestJson) {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage(requestJson.getBytes()),
                    requestParameter,
                    commandType,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("must match the command schema");
  }

  private void assertRejectsCoercedCommandScalar(
      MethodParameter requestParameter, Class<?> commandType, String requestJson) {
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    new MockHttpInputMessage(requestJson.getBytes()),
                    requestParameter,
                    commandType,
                    MappingJackson2HttpMessageConverter.class))
        .isInstanceOf(HttpMessageNotReadableException.class)
        .hasMessageContaining("must match the command schema");
  }

  private void assertPreservesValidRequestBody(
      MethodParameter requestParameter, Class<?> commandType, String requestJson) throws Exception {
    String bufferedRequestJson =
        new String(
            advice
                .beforeBodyRead(
                    new MockHttpInputMessage(requestJson.getBytes()),
                    requestParameter,
                    commandType,
                    MappingJackson2HttpMessageConverter.class)
                .getBody()
                .readAllBytes());

    assertThat(bufferedRequestJson).isEqualTo(requestJson);
  }
}
