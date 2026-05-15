package com.box.l10n.mojito.service.tm.search;

import static com.box.l10n.mojito.entity.TMTextUnitVariant.Status.TRANSLATION_NEEDED;
import static com.box.l10n.mojito.entity.TMTextUnitVariant.Status.valueOf;

import com.box.l10n.mojito.JSR310Migration;
import com.box.l10n.mojito.entity.TMTextUnitVariant;
import com.github.pnowy.nc.core.CriteriaResult;
import com.github.pnowy.nc.core.mappers.NativeObjectMapper;

/**
 * @author jaurambault
 */
public class TextUnitDTONativeObjectMapper implements NativeObjectMapper<TextUnitDTO> {

  boolean mapAssetTextUnitUsages;

  public TextUnitDTONativeObjectMapper(boolean mapAssetTextUnitUsages) {
    this.mapAssetTextUnitUsages = mapAssetTextUnitUsages;
  }

  @Override
  public TextUnitDTO mapObject(CriteriaResult cr) {

    int idx = 0;

    TextUnitDTO t = new TextUnitDTO();
    t.setTmTextUnitId(cr.getLong(idx++));
    t.setTmTextUnitVariantId(cr.getLong(idx++));

    // TODO(PO) THIS NOT CONSISTANT !! chooose
    t.setLocaleId(cr.getLong(idx++));
    t.setTargetLocale(cr.getString(idx++));
    t.setName(cr.getString(idx++));
    t.setSource(cr.getString(idx++));
    t.setComment(cr.getString(idx++));
    t.setTarget(cr.getString(idx++));
    t.setTargetComment(cr.getString(idx++));
    t.setAssetId(cr.getLong(idx++));
    t.setLastSuccessfulAssetExtractionId(cr.getLong(idx++));
    t.setAssetExtractionId(cr.getLong(idx++));
    t.setTmTextUnitCurrentVariantId(cr.getLong(idx++));
    t.setStatus(getStatus(cr.getString(idx++)));

    t.setIncludedInLocalizedFile(parseNativeBoolean(cr.getString(idx++)));
    t.setCreatedDate(JSR310Migration.newDateTimeCtorWithDate(cr.getDate(idx++)));
    t.setAssetDeleted(parseNativeBoolean(cr.getString(idx++)));
    t.setPluralForm(cr.getString(idx++));
    t.setPluralFormOther(cr.getString(idx++));
    t.setRepositoryName(cr.getString(idx++));
    t.setAssetPath(cr.getString(idx++));
    t.setAssetTextUnitId(cr.getLong(idx++));
    t.setTmTextUnitCreatedDate(JSR310Migration.newDateTimeCtorWithDate(cr.getDate(idx++)));

    t.setDoNotTranslate(parseNativeBoolean(cr.getString(idx++)));

    t.setBranchId(cr.getLong(idx++));

    if (mapAssetTextUnitUsages) {
      t.setAssetTextUnitUsages(cr.getString(idx++));
    }

    return t;
  }

  /**
   * Gets the status for the status string returned by the query. That string can be null for text
   * unit that maps to an untranslated string. In that case the status will be {@link
   * TMTextUnitVariant.Status.TRANSLATION_NEEDED} .
   *
   * @param statusStr status string coming from the dataset (can be null)
   * @return the status
   */
  public TMTextUnitVariant.Status getStatus(String statusStr) {

    TMTextUnitVariant.Status status;

    if (statusStr == null) {
      status = TRANSLATION_NEEDED;
    } else {
      status = valueOf(statusStr);
    }

    return status;
  }

  static boolean parseNativeBoolean(String value) {
    return value != null
        && ("true".equalsIgnoreCase(value)
            || "1".equals(value)
            || "\u0001".equals(value)
            || "yes".equalsIgnoreCase(value));
  }
}
