package com.box.l10n.mojito.rest.resttemplate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.JacksonConfigurationProperties;
import com.box.l10n.mojito.rest.entity.SourceAsset;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.client.RestTemplate;

public class AuthenticatedRestTemplateObjectMapperTest {

  @Test
  public void jsonConverterReadsSourceAssetLargerThanJacksonDefault() throws Exception {
    RestTemplate restTemplate = new RestTemplate();
    AuthenticatedRestTemplate authenticatedRestTemplate = new AuthenticatedRestTemplate();
    JacksonConfigurationProperties properties = new JacksonConfigurationProperties();
    properties.setMaxStringLength(40_000_000);
    authenticatedRestTemplate.jacksonConfigurationProperties = properties;
    authenticatedRestTemplate.makeRestTemplateWithCustomObjectMapper(restTemplate);
    MappingJackson2HttpMessageConverter converter =
        restTemplate.getMessageConverters().stream()
            .filter(MappingJackson2HttpMessageConverter.class::isInstance)
            .map(MappingJackson2HttpMessageConverter.class::cast)
            .findFirst()
            .orElseThrow();
    String content = "a".repeat(20_000_001);
    MockHttpInputMessage inputMessage =
        new MockHttpInputMessage(("{\"content\":\"" + content + "\"}").getBytes(UTF_8));
    inputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);

    SourceAsset sourceAsset = (SourceAsset) converter.read(SourceAsset.class, inputMessage);

    assertThat(sourceAsset.getContent()).hasSize(20_000_001);
    assertThat(
            converter.getObjectMapper().getFactory().streamReadConstraints().getMaxStringLength())
        .isEqualTo(40_000_000);
  }
}
