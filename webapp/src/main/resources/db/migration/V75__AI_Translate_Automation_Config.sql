create table ai_translate_automation_config (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    enabled bit not null default false,
    cron_expression varchar(255) default null,
    repository_ids_json longtext,
    source_text_max_count_per_locale int not null default 100,
    primary key (id)
);
