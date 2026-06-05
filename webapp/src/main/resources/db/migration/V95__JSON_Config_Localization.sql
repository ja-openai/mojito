create table json_config_localization (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    name varchar(255) not null,
    repository_id bigint(20) not null,
    asset_path varchar(255) not null,
    provider varchar(32) not null default 'GENERIC_JSON',
    provider_config_id varchar(255) DEFAULT NULL,
    schema_json longtext DEFAULT NULL,
    source_config_json longtext DEFAULT NULL,
    extraction_mapping_json longtext DEFAULT NULL,
    output_locale_mapping_json longtext DEFAULT NULL,
    automation_enabled bit(1) not null default b'0',
    automation_cron_expression varchar(255) DEFAULT NULL,
    automation_time_zone varchar(128) DEFAULT NULL,
    automation_options_json longtext DEFAULT NULL,
    primary key (id)
);

alter table json_config_localization
    add constraint FK__JSON_CONFIG_LOCALIZATION__REPOSITORY
        foreign key (repository_id) references repository (id);

create index I__JSON_CONFIG_LOCALIZATION__REPOSITORY
    on json_config_localization(repository_id);

create unique index UK__JSON_CONFIG_LOCALIZATION__REPOSITORY_NAME
    on json_config_localization(repository_id, name);

create unique index UK__JSON_CONFIG_LOCALIZATION__REPOSITORY_ASSET_PATH
    on json_config_localization(repository_id, asset_path);

create index I__JSON_CONFIG_LOCALIZATION__NAME
    on json_config_localization(name);

create table json_config_localization_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    json_config_localization_id bigint(20) not null,
    trigger_source varchar(32) not null,
    status varchar(32) not null,
    started_at datetime DEFAULT NULL,
    finished_at datetime DEFAULT NULL,
    pull_enabled bit(1) not null default b'0',
    extract_enabled bit(1) not null default b'0',
    translate_enabled bit(1) not null default b'0',
    merge_enabled bit(1) not null default b'0',
    save_config_enabled bit(1) not null default b'0',
    push_enabled bit(1) not null default b'0',
    pulled bit(1) not null default b'0',
    extracted bit(1) not null default b'0',
    translated bit(1) not null default b'0',
    merged bit(1) not null default b'0',
    saved_config bit(1) not null default b'0',
    pushed bit(1) not null default b'0',
    push_skipped bit(1) not null default b'0',
    summary varchar(1024) DEFAULT NULL,
    error_message varchar(4000) DEFAULT NULL,
    primary key (id)
);

alter table json_config_localization_run
    add constraint FK__JSON_CONFIG_LOCALIZATION_RUN__SETUP
        foreign key (json_config_localization_id) references json_config_localization (id);

create index I__JSON_CONFIG_LOCALIZATION_RUN__CREATED_DATE
    on json_config_localization_run(created_date);

create index I__JSON_CONFIG_LOCALIZATION_RUN__SETUP
    on json_config_localization_run(json_config_localization_id);
