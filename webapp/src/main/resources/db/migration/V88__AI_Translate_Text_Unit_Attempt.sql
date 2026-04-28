create table ai_translate_text_unit_attempt (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    ai_translate_run_id bigint(20) DEFAULT NULL,
    pollable_task_id bigint(20) NOT NULL,
    tm_text_unit_id bigint(20) NOT NULL,
    locale_id bigint(20) NOT NULL,
    tm_text_unit_variant_id bigint(20) DEFAULT NULL,
    request_group_id varchar(255) NOT NULL,
    translate_type varchar(64) NOT NULL,
    model varchar(255) DEFAULT NULL,
    status varchar(32) NOT NULL,
    completion_id varchar(255) DEFAULT NULL,
    request_payload_blob_name varchar(1024) DEFAULT NULL,
    response_payload_blob_name varchar(1024) DEFAULT NULL,
    error_message longtext DEFAULT NULL,
    primary key (id)
);

alter table ai_translate_text_unit_attempt
    add constraint FK__AITTA__AI_TRANSLATE_RUN__ID
        foreign key (ai_translate_run_id) references ai_translate_run (id);

alter table ai_translate_text_unit_attempt
    add constraint FK__AITTA__POLLABLE_TASK__ID
        foreign key (pollable_task_id) references pollable_task (id);

alter table ai_translate_text_unit_attempt
    add constraint FK__AITTA__TM_TEXT_UNIT__ID
        foreign key (tm_text_unit_id) references tm_text_unit (id);

alter table ai_translate_text_unit_attempt
    add constraint FK__AITTA__LOCALE__ID
        foreign key (locale_id) references locale (id);

alter table ai_translate_text_unit_attempt
    add constraint FK__AITTA__TM_TEXT_UNIT_VARIANT__ID
        foreign key (tm_text_unit_variant_id) references tm_text_unit_variant (id);

create index I__AITTA__POLLABLE_GROUP
    on ai_translate_text_unit_attempt(pollable_task_id, request_group_id);
create index I__AITTA__TM_TEXT_UNIT__LOCALE
    on ai_translate_text_unit_attempt(tm_text_unit_id, locale_id, created_date);
