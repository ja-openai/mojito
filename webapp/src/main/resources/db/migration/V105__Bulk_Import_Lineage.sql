create table bulk_import_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    completed_date datetime DEFAULT NULL,
    run_id varchar(36) NOT NULL,
    repository_id bigint(20) NOT NULL,
    asset_id bigint(20) NOT NULL,
    locale_id bigint(20) NOT NULL,
    pollable_task_id bigint(20) DEFAULT NULL,
    initiating_user_id bigint(20) DEFAULT NULL,
    actor_type varchar(16) NOT NULL,
    actor_identity varchar(255) DEFAULT NULL,
    source varchar(128) NOT NULL,
    import_mode varchar(32) NOT NULL,
    integrity_checks_type varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    requested_count int NOT NULL,
    imported_count int NOT NULL,
    skipped_count int NOT NULL,
    input_payload_blob_name varchar(1024) DEFAULT NULL,
    output_payload_blob_name varchar(1024) DEFAULT NULL,
    error_message varchar(1024) DEFAULT NULL,
    primary key (id)
);

alter table bulk_import_run
    add constraint FK__BULK_IMPORT_RUN__REPOSITORY
        foreign key (repository_id) references repository (id);
alter table bulk_import_run
    add constraint FK__BULK_IMPORT_RUN__ASSET
        foreign key (asset_id) references asset (id);
alter table bulk_import_run
    add constraint FK__BULK_IMPORT_RUN__LOCALE
        foreign key (locale_id) references locale (id);
alter table bulk_import_run
    add constraint FK__BULK_IMPORT_RUN__POLLABLE_TASK
        foreign key (pollable_task_id) references pollable_task (id);
alter table bulk_import_run
    add constraint FK__BULK_IMPORT_RUN__INITIATING_USER
        foreign key (initiating_user_id) references user (id);

create unique index UK__BULK_IMPORT_RUN__RUN_ID on bulk_import_run(run_id);
create index I__BULK_IMPORT_RUN__REPOSITORY_LOCALE
    on bulk_import_run(repository_id, locale_id, created_date);
create index I__BULK_IMPORT_RUN__POLLABLE_TASK on bulk_import_run(pollable_task_id);

create table bulk_import_run_item (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    bulk_import_run_id bigint(20) NOT NULL,
    tm_text_unit_id bigint(20) DEFAULT NULL,
    locale_id bigint(20) NOT NULL,
    previous_tm_text_unit_variant_id bigint(20) DEFAULT NULL,
    resulting_tm_text_unit_variant_id bigint(20) DEFAULT NULL,
    text_unit_name varchar(1024) DEFAULT NULL,
    status varchar(16) NOT NULL,
    translator_identity varchar(255) NOT NULL,
    reviewer_identity varchar(255) NOT NULL,
    primary key (id)
);

alter table bulk_import_run_item
    add constraint FK__BULK_IMPORT_RUN_ITEM__RUN
        foreign key (bulk_import_run_id) references bulk_import_run (id);
alter table bulk_import_run_item
    add constraint FK__BULK_IMPORT_RUN_ITEM__TEXT_UNIT
        foreign key (tm_text_unit_id) references tm_text_unit (id);
alter table bulk_import_run_item
    add constraint FK__BULK_IMPORT_RUN_ITEM__LOCALE
        foreign key (locale_id) references locale (id);
alter table bulk_import_run_item
    add constraint FK__BULK_IMPORT_RUN_ITEM__PREVIOUS_VARIANT
        foreign key (previous_tm_text_unit_variant_id) references tm_text_unit_variant (id);
alter table bulk_import_run_item
    add constraint FK__BULK_IMPORT_RUN_ITEM__RESULTING_VARIANT
        foreign key (resulting_tm_text_unit_variant_id) references tm_text_unit_variant (id);

create index I__BULK_IMPORT_RUN_ITEM__RUN on bulk_import_run_item(bulk_import_run_id);
create index I__BULK_IMPORT_RUN_ITEM__TEXT_UNIT_LOCALE
    on bulk_import_run_item(tm_text_unit_id, locale_id);
create index I__BULK_IMPORT_RUN_ITEM__PREVIOUS_VARIANT
    on bulk_import_run_item(previous_tm_text_unit_variant_id);
create index I__BULK_IMPORT_RUN_ITEM__RESULTING_VARIANT
    on bulk_import_run_item(resulting_tm_text_unit_variant_id);
