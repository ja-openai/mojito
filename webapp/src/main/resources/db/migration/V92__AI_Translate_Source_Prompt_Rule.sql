create table ai_translate_source_prompt_rule (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    name varchar(255) not null,
    description longtext DEFAULT NULL,
    enabled tinyint(1) not null,
    priority int not null,
    match_type varchar(50) not null,
    source_regex longtext not null,
    prompt_suffix longtext not null,
    primary key (id)
);

create unique index UK__AI_TRANSLATE_SOURCE_PROMPT_RULE__NAME
    on ai_translate_source_prompt_rule(name);

create index I__AI_TRANSLATE_SOURCE_PROMPT_RULE__ENABLED_PRIORITY_ID
    on ai_translate_source_prompt_rule(enabled, priority, id);
