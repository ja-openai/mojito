create table ai_translate_locale_prompt_suffix (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    locale_id bigint(20) not null,
    prompt_suffix longtext not null,
    primary key (id),
    constraint FK__AI_TRANSLATE_LOCALE_PROMPT_SUFFIX__LOCALE__ID
        foreign key (locale_id) references locale(id)
);

create unique index UK__AI_TRANSLATE_LOCALE_PROMPT_SUFFIX__LOCALE__ID
    on ai_translate_locale_prompt_suffix(locale_id);
