create table tm_text_unit_variant_leveraging (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    tm_text_unit_variant_id bigint(20) NOT NULL,
    source_tm_text_unit_id bigint(20) NOT NULL,
    source_tm_text_unit_variant_id bigint(20) NOT NULL,
    leveraging_type varchar(64) NOT NULL,
    unique_match bit not null default false,
    primary key (id)
);

alter table tm_text_unit_variant_leveraging add constraint FK__TM_TEXT_UNIT_VARIANT_LEVERAGING__TM_TEXT_UNIT_VARIANT foreign key (tm_text_unit_variant_id) references tm_text_unit_variant (id);
alter table tm_text_unit_variant_leveraging add constraint FK__TM_TEXT_UNIT_VARIANT_LEVERAGING__SOURCE_TM_TEXT_UNIT foreign key (source_tm_text_unit_id) references tm_text_unit (id);
alter table tm_text_unit_variant_leveraging add constraint FK__TM_TEXT_UNIT_VARIANT_LEVERAGING__SOURCE_TM_TEXT_UNIT_VARIANT foreign key (source_tm_text_unit_variant_id) references tm_text_unit_variant (id);
alter table tm_text_unit_variant_leveraging add constraint UK__TM_TEXT_UNIT_VARIANT_LEVERAGING__TM_TEXT_UNIT_VARIANT unique (tm_text_unit_variant_id);
