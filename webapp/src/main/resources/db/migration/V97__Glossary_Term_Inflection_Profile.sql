create table glossary_term_inflection_profile (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_term_metadata_id bigint(20) not null,
    locale_tag varchar(64) not null,
    profile_schema varchar(128) not null,
    status varchar(32) not null,
    morphology_json longtext not null,
    forms_json longtext not null,
    diagnostics_json longtext not null,
    provenance_json longtext not null,
    primary key (id)
);

alter table glossary_term_inflection_profile
    add constraint FK__GLOSSARY_TERM_INFLECTION_PROFILE__TERM_METADATA
        foreign key (glossary_term_metadata_id)
        references glossary_term_metadata (id)
        on delete cascade;

create unique index UK__GLOSSARY_TERM_INFLECTION_PROFILE__TERM_LOCALE
    on glossary_term_inflection_profile(glossary_term_metadata_id, locale_tag);

create index I__GLOSSARY_TERM_INFLECTION_PROFILE__TERM
    on glossary_term_inflection_profile(glossary_term_metadata_id);

create index I__GLOSSARY_TERM_INFLECTION_PROFILE__LOCALE_STATUS
    on glossary_term_inflection_profile(locale_tag, status);
