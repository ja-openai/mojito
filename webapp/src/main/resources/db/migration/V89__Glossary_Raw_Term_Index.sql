create table term_index_entry (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    normalized_key varchar(255) not null,
    display_term varchar(512) not null,
    source_locale_tag varchar(64) not null,
    occurrence_count bigint(20) not null default 0,
    repository_count integer not null default 0,
    signal_summary varchar(1024) DEFAULT NULL,
    first_seen_at datetime DEFAULT NULL,
    last_seen_at datetime DEFAULT NULL,
    primary key (id)
);

alter table term_index_entry
    add constraint UK__TERM_INDEX_ENTRY__LOCALE_KEY
        unique (source_locale_tag, normalized_key);

create table term_index_occurrence (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    term_index_entry_id bigint(20) not null,
    tm_text_unit_id bigint(20) not null,
    repository_id bigint(20) not null,
    asset_id bigint(20) DEFAULT NULL,
    matched_text varchar(512) not null,
    start_index integer not null,
    end_index integer not null,
    source_hash varchar(64) DEFAULT NULL,
    extractor_id varchar(64) not null,
    extraction_method varchar(64) not null,
    confidence integer DEFAULT NULL,
    metadata_json mediumtext DEFAULT NULL,
    primary key (id)
);

alter table term_index_occurrence
    add constraint FK__TERM_INDEX_OCCURRENCE__ENTRY
        foreign key (term_index_entry_id) references term_index_entry (id);

alter table term_index_occurrence
    add constraint FK__TERM_INDEX_OCCURRENCE__TM_TEXT_UNIT
        foreign key (tm_text_unit_id) references tm_text_unit (id);

alter table term_index_occurrence
    add constraint FK__TERM_INDEX_OCCURRENCE__REPOSITORY
        foreign key (repository_id) references repository (id);

alter table term_index_occurrence
    add constraint FK__TERM_INDEX_OCCURRENCE__ASSET
        foreign key (asset_id) references asset (id);

alter table term_index_occurrence
    add constraint UK__TERM_INDEX_OCCURRENCE__ENTRY_TU_SPAN_EXTRACTOR
        unique (term_index_entry_id, tm_text_unit_id, start_index, end_index, extractor_id);

create index I__TERM_INDEX_OCCURRENCE__TM_TEXT_UNIT
    on term_index_occurrence(tm_text_unit_id);

create index I__TERM_INDEX_OCCURRENCE__REPOSITORY_ENTRY
    on term_index_occurrence(repository_id, term_index_entry_id);

create table term_index_repository_cursor (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    repository_id bigint(20) not null,
    status varchar(32) not null,
    last_processed_created_at datetime DEFAULT NULL,
    last_processed_tm_text_unit_id bigint(20) DEFAULT NULL,
    last_successful_scan_at datetime DEFAULT NULL,
    lease_owner varchar(255) DEFAULT NULL,
    lease_token varchar(64) DEFAULT NULL,
    lease_expires_at datetime DEFAULT NULL,
    current_refresh_run_id bigint(20) DEFAULT NULL,
    error_message varchar(2048) DEFAULT NULL,
    primary key (id)
);

alter table term_index_repository_cursor
    add constraint FK__TERM_INDEX_REPOSITORY_CURSOR__REPOSITORY
        foreign key (repository_id) references repository (id);

alter table term_index_repository_cursor
    add constraint UK__TERM_INDEX_REPOSITORY_CURSOR__REPOSITORY
        unique (repository_id);

create table term_index_refresh_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    status varchar(32) not null,
    requested_repository_ids varchar(2048) DEFAULT NULL,
    processed_text_unit_count bigint(20) not null default 0,
    entry_count bigint(20) not null default 0,
    occurrence_count bigint(20) not null default 0,
    started_at datetime DEFAULT NULL,
    completed_at datetime DEFAULT NULL,
    error_message varchar(2048) DEFAULT NULL,
    primary key (id)
);

create table term_index_refresh_run_entry (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    refresh_run_id bigint(20) not null,
    term_index_entry_id bigint(20) not null,
    primary key (id)
);

alter table term_index_refresh_run_entry
    add constraint FK__TERM_INDEX_REFRESH_RUN_ENTRY__REFRESH_RUN
        foreign key (refresh_run_id) references term_index_refresh_run (id);

alter table term_index_refresh_run_entry
    add constraint FK__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_ENTRY
        foreign key (term_index_entry_id) references term_index_entry (id);

alter table term_index_refresh_run_entry
    add constraint UK__TERM_INDEX_REFRESH_RUN_ENTRY__RUN_ENTRY
        unique (refresh_run_id, term_index_entry_id);

create index I__TERM_INDEX_REFRESH_RUN_ENTRY__TERM_INDEX_ENTRY
    on term_index_refresh_run_entry(term_index_entry_id);

alter table term_index_repository_cursor
    add constraint FK__TERM_INDEX_REPOSITORY_CURSOR__CURRENT_REFRESH_RUN
        foreign key (current_refresh_run_id) references term_index_refresh_run (id);

create table glossary_term_index_link (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    glossary_term_metadata_id bigint(20) not null,
    term_index_entry_id bigint(20) not null,
    relation_type varchar(32) not null,
    confidence integer DEFAULT NULL,
    rationale varchar(2048) DEFAULT NULL,
    primary key (id)
);

alter table glossary_term_index_link
    add constraint FK__GLOSSARY_TERM_INDEX_LINK__TERM_METADATA
        foreign key (glossary_term_metadata_id) references glossary_term_metadata (id);

alter table glossary_term_index_link
    add constraint FK__GLOSSARY_TERM_INDEX_LINK__TERM_INDEX_ENTRY
        foreign key (term_index_entry_id) references term_index_entry (id);

alter table glossary_term_index_link
    add constraint UK__GLOSSARY_TERM_INDEX_LINK__TERM_ENTRY_RELATION
        unique (glossary_term_metadata_id, term_index_entry_id, relation_type);

create index I__GLOSSARY_TERM_INDEX_LINK__TERM_INDEX_ENTRY
    on glossary_term_index_link(term_index_entry_id);
