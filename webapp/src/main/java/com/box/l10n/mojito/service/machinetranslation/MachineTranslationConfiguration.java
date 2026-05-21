package com.box.l10n.mojito.service.machinetranslation;

import com.box.l10n.mojito.openai.OpenAIClient;
import com.box.l10n.mojito.service.machinetranslation.microsoft.MicrosoftMTEngine;
import com.box.l10n.mojito.service.machinetranslation.microsoft.MicrosoftMTEngineConfiguration;
import com.box.l10n.mojito.service.machinetranslation.openai.OpenAIMTEngine;
import com.box.l10n.mojito.service.oaitranslate.AiTranslateConfigurationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the implementation for {@link MachineTranslationEngine} The default implementation is
 * the {@link NoOpEngine} which is a stub. Consider implementing & configuring an actual MT Engine
 * using the "l10n.mt.impl" property.
 *
 * @author garion
 */
@Configuration
public class MachineTranslationConfiguration {

  static Logger logger = LoggerFactory.getLogger(MachineTranslationConfiguration.class);

  @ConditionalOnProperty(value = "l10n.mt.impl", havingValue = "MicrosoftMTEngine")
  @Configuration
  static class MicrosoftEngineConfiguration {

    final MicrosoftMTEngineConfiguration microsoftMTEngineConfiguration;
    final PlaceholderEncoder placeholderEncoder;
    final MeterRegistry meterRegistry;

    public MicrosoftEngineConfiguration(
        MicrosoftMTEngineConfiguration microsoftMTEngineConfiguration,
        PlaceholderEncoder placeholderEncoder,
        MeterRegistry meterRegistry) {
      this.microsoftMTEngineConfiguration = microsoftMTEngineConfiguration;
      this.placeholderEncoder = placeholderEncoder;
      this.meterRegistry = meterRegistry;
    }

    @Bean
    public MicrosoftMTEngine microsoftMTEngine() {
      logger.info("Configure microsoftMTEngine");
      return new MicrosoftMTEngine(
          microsoftMTEngineConfiguration, placeholderEncoder, meterRegistry);
    }
  }

  @ConditionalOnProperty(value = "l10n.mt.impl", havingValue = "OpenAIMTEngine")
  @Configuration
  static class OpenAIEngineConfiguration {

    final AiTranslateConfigurationProperties aiTranslateConfigurationProperties;
    final PlaceholderEncoder placeholderEncoder;
    final MeterRegistry meterRegistry;

    public OpenAIEngineConfiguration(
        AiTranslateConfigurationProperties aiTranslateConfigurationProperties,
        PlaceholderEncoder placeholderEncoder,
        MeterRegistry meterRegistry) {
      this.aiTranslateConfigurationProperties = aiTranslateConfigurationProperties;
      this.placeholderEncoder = placeholderEncoder;
      this.meterRegistry = meterRegistry;
    }

    @Bean
    public OpenAIMTEngine openAIMTEngine() {
      logger.info("Configure openAIMTEngine");
      String openaiClientToken = aiTranslateConfigurationProperties.getOpenaiClientToken();
      if (openaiClientToken == null || openaiClientToken.isBlank()) {
        throw new IllegalStateException(
            "OpenAIMTEngine requires l10n.ai-translate.openai-client-token");
      }
      return new OpenAIMTEngine(
          new OpenAIClient.Builder().apiKey(openaiClientToken).build(),
          aiTranslateConfigurationProperties,
          placeholderEncoder,
          meterRegistry);
    }
  }

  @ConditionalOnProperty(value = "l10n.mt.impl", havingValue = "NoOpEngine", matchIfMissing = true)
  @Configuration
  static class NoOpEngineConfiguration {

    @Bean
    public NoOpEngine noOpEngine() {
      logger.info("Configure noOpEngine");
      return new NoOpEngine();
    }
  }
}
