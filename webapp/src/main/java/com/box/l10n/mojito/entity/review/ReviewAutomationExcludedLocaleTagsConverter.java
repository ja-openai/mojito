package com.box.l10n.mojito.entity.review;

import com.box.l10n.mojito.json.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

@Converter
public class ReviewAutomationExcludedLocaleTagsConverter
    implements AttributeConverter<List<String>, String> {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Override
  public String convertToDatabaseColumn(List<String> localeTags) {
    return OBJECT_MAPPER.writeValueAsStringUnchecked(localeTags == null ? List.of() : localeTags);
  }

  @Override
  public List<String> convertToEntityAttribute(String json) {
    return json == null
        ? List.of()
        : List.of(OBJECT_MAPPER.readValueUnchecked(json, String[].class));
  }
}
