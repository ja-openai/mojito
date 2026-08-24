package com.box.l10n.mojito;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.box.l10n.mojito.json.JacksonConfigurationProperties;
import com.box.l10n.mojito.rest.asset.SourceAsset;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

public class ApplicationTest {

  @Test
  public void mvcConverterReadsSourceAssetLargerThanJacksonDefault() throws Exception {
    JacksonConfigurationProperties properties = new JacksonConfigurationProperties();
    MappingJackson2HttpMessageConverter converter =
        new Application().mappingJackson2HttpMessageConverter(properties);
    String content = "a".repeat(20_000_001);
    MockHttpInputMessage inputMessage =
        new MockHttpInputMessage(("{\"content\":\"" + content + "\"}").getBytes(UTF_8));
    inputMessage.getHeaders().setContentType(MediaType.APPLICATION_JSON);

    SourceAsset sourceAsset = (SourceAsset) converter.read(SourceAsset.class, inputMessage);

    assertThat(sourceAsset.getContent()).hasSize(20_000_001);
    assertThat(
            converter.getObjectMapper().getFactory().streamReadConstraints().getMaxStringLength())
        .isEqualTo(30_000_000);
  }

  @Test
  public void applicationObjectMappersUseConfiguredMaximumStringLength() {
    JacksonConfigurationProperties properties = new JacksonConfigurationProperties();
    properties.setMaxStringLength(40_000_000);
    Application application = new Application();

    assertThat(
            application
                .getObjectMapper(properties)
                .getFactory()
                .streamReadConstraints()
                .getMaxStringLength())
        .isEqualTo(40_000_000);
    assertThat(
            application
                .getObjectMapperFailOnUnknownPropertiesFalse(properties)
                .getFactory()
                .streamReadConstraints()
                .getMaxStringLength())
        .isEqualTo(40_000_000);
    assertThat(
            application
                .getSmileFormatObjectMapper(properties)
                .getFactory()
                .streamReadConstraints()
                .getMaxStringLength())
        .isEqualTo(40_000_000);
  }
}
