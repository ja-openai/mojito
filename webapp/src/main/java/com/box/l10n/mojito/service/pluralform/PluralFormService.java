package com.box.l10n.mojito.service.pluralform;

import static com.box.l10n.mojito.CacheType.Names.PLURAL_FORMS;

import com.box.l10n.mojito.entity.PluralForm;
import com.box.l10n.mojito.service.cache.CacheKey;
import com.box.l10n.mojito.service.cache.CacheService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @author jaurambault
 */
@Service
public class PluralFormService {

  @Autowired PluralFormRepository pluralFormRepository;

  @Autowired CacheService cacheService;

  /**
   * @return Map "plural_form" => PluralForm. The map will be cached.
   */
  private Map<String, PluralForm> getPluralFormMap() {
    return cacheService.get(
        PLURAL_FORMS,
        CacheKey.of(PluralFormService.class, "getPluralFormMap"),
        this::getPluralFormMapUncached);
  }

  private Map<String, PluralForm> getPluralFormMapUncached() {
    Map<String, PluralForm> pluralFormsMap = new HashMap<>();
    List<PluralForm> pluralForms = pluralFormRepository.findAll();

    for (PluralForm pluralForm : pluralForms) {
      pluralFormsMap.put(pluralForm.getName(), pluralForm);
    }

    return pluralFormsMap;
  }

  /**
   * @return Map ID => PluralForm. The map will be cached.
   */
  private Map<Long, PluralForm> getPluralFormIdMap() {
    return cacheService.get(
        PLURAL_FORMS,
        CacheKey.of(PluralFormService.class, "getPluralFormIdMap"),
        this::getPluralFormIdMapUncached);
  }

  private Map<Long, PluralForm> getPluralFormIdMapUncached() {
    Map<Long, PluralForm> pluralFormsMap = new HashMap<>();
    List<PluralForm> pluralForms = pluralFormRepository.findAll();

    for (PluralForm pluralForm : pluralForms) {
      pluralFormsMap.put(pluralForm.getId(), pluralForm);
    }

    return pluralFormsMap;
  }

  /**
   * Returns the PluralForm for the given plural form string.
   *
   * @param pluralFormString the plural form string
   * @return The corresponding plural form or {@code null} if none found
   */
  public PluralForm findByPluralFormString(String pluralFormString) {

    PluralForm pluralForm = null;

    if (pluralFormString != null) {
      pluralForm = getPluralFormMap().get(pluralFormString.toLowerCase());
    }

    return pluralForm;
  }

  /**
   * Returns the plural form for the given ID.
   *
   * @param pluralFormId The ID of the plural form
   * @return The corresponding plural form or {@code null} if none found
   */
  public PluralForm findById(Long pluralFormId) {
    return getPluralFormIdMap().get(pluralFormId);
  }
}
