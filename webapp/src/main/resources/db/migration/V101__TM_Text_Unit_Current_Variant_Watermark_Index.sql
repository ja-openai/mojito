alter table tm_text_unit_current_variant
    add index I__TTUCV__TM_LOCALE_MODIFIED (tm_id, locale_id, last_modified_date),
    algorithm=inplace,
    lock=none;
