alter table tm_text_unit_current_variant
    add index I__TTUCV__TM_MODIFIED (tm_id, last_modified_date),
    add index I__TTUCV__ASSET_LOCALE_MODIFIED (asset_id, locale_id, last_modified_date),
    drop index I__TTUCV__TM_LOCALE_MODIFIED,
    algorithm=inplace,
    lock=none;
