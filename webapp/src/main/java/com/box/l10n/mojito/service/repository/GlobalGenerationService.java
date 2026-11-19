package com.box.l10n.mojito.service.repository;

import com.box.l10n.mojito.entity.GenerationExcludedLocaleTagsConverter;
import com.box.l10n.mojito.service.locale.LocaleService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlobalGenerationService {

  private final JdbcTemplate jdbcTemplate;
  private final LocaleService localeService;
  private final GenerationExcludedLocaleTagsConverter converter =
      new GenerationExcludedLocaleTagsConverter();

  public GlobalGenerationService(JdbcTemplate jdbcTemplate, LocaleService localeService) {
    this.jdbcTemplate = jdbcTemplate;
    this.localeService = localeService;
  }

  @Transactional(readOnly = true)
  public List<String> getExcludedLocaleTags() {
    return converter.convertToEntityAttribute(
        jdbcTemplate.queryForObject(
            "select excluded_locale_tags_json from generation_settings where id = 1",
            String.class));
  }

  @Transactional
  public List<String> updateExcludedLocaleTags(List<String> localeTags) {
    List<String> exclusions =
        GenerationExcludedLocaleTags.normalize(localeTags, localeService, null);
    int updated =
        jdbcTemplate.update(
            "update generation_settings set excluded_locale_tags_json = ? where id = 1",
            converter.convertToDatabaseColumn(exclusions));
    if (updated != 1) {
      throw new IllegalStateException("Global generation settings are missing");
    }
    return exclusions;
  }
}
