package com.box.l10n.mojito.rest.cms;

import com.box.l10n.mojito.json.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

@ControllerAdvice(assignableTypes = CmsContentWS.class)
public class CmsContentRequestBodyAdvice extends RequestBodyAdviceAdapter {

  private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

  private final com.fasterxml.jackson.databind.ObjectMapper strictRequestObjectMapper;

  public CmsContentRequestBodyAdvice(ObjectMapper objectMapper) {
    this.strictRequestObjectMapper =
        objectMapper
            .copy()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @Override
  public boolean supports(
      MethodParameter methodParameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) {
    return CmsContentWS.class.equals(methodParameter.getContainingClass());
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage inputMessage,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType)
      throws IOException {
    byte[] requestBody = inputMessage.getBody().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
    validateRequestBodySize(requestBody, inputMessage);
    JsonNode requestJson = parseRequestJson(requestBody, inputMessage);
    validateRequiredCommandBody(requestJson, parameter, inputMessage);
    validateCommandSchema(requestBody, targetType, inputMessage);
    return new BufferedHttpInputMessage(inputMessage, requestBody);
  }

  private void validateRequestBodySize(byte[] requestBody, HttpInputMessage inputMessage) {
    if (requestBody.length > MAX_REQUEST_BODY_BYTES) {
      throw new HttpMessageNotReadableException(
          "CMS request body must be at most " + MAX_REQUEST_BODY_BYTES + " bytes", inputMessage);
    }
  }

  private JsonNode parseRequestJson(byte[] requestBody, HttpInputMessage inputMessage) {
    if (requestBody.length == 0) {
      return null;
    }
    try {
      return strictRequestObjectMapper.readTree(requestBody);
    } catch (IOException ex) {
      throw new HttpMessageNotReadableException(
          "CMS request body must be one valid JSON document with unique object keys",
          ex,
          inputMessage);
    }
  }

  private void validateRequiredCommandBody(
      JsonNode requestJson, MethodParameter parameter, HttpInputMessage inputMessage) {
    RequestBody requestBody = parameter.getParameterAnnotation(RequestBody.class);
    if (requestBody != null
        && requestBody.required()
        && (requestJson == null || requestJson.isNull())) {
      throw new HttpMessageNotReadableException(
          "CMS required request body must not be JSON null", inputMessage);
    }
  }

  private void validateCommandSchema(
      byte[] requestBody, Type targetType, HttpInputMessage inputMessage) {
    if (requestBody.length == 0 || !(targetType instanceof Class<?> targetClass)) {
      return;
    }
    try {
      strictRequestObjectMapper.readerFor(targetClass).readValue(requestBody);
    } catch (IOException ex) {
      throw new HttpMessageNotReadableException(
          "CMS request body must match the command schema with no unknown fields",
          ex,
          inputMessage);
    }
  }

  private record BufferedHttpInputMessage(HttpInputMessage inputMessage, byte[] requestBody)
      implements HttpInputMessage {

    @Override
    public ByteArrayInputStream getBody() {
      return new ByteArrayInputStream(requestBody);
    }

    @Override
    public org.springframework.http.HttpHeaders getHeaders() {
      return inputMessage.getHeaders();
    }
  }
}
