create table ai_translate_run (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    trigger_source varchar(32) not null,
    repository_id bigint(20) not null,
    requested_by_user_id bigint(20) DEFAULT NULL,
    pollable_task_id bigint(20) DEFAULT NULL,
    model varchar(255) not null,
    translate_type varchar(64) not null,
    related_strings_type varchar(64) not null,
    source_text_max_count_per_locale int not null,
    status varchar(32) not null,
    started_at datetime DEFAULT NULL,
    finished_at datetime DEFAULT NULL,
    input_tokens bigint(20) not null DEFAULT 0,
    cached_input_tokens bigint(20) not null DEFAULT 0,
    output_tokens bigint(20) not null DEFAULT 0,
    reasoning_tokens bigint(20) not null DEFAULT 0,
    estimated_cost_usd decimal(18,6) DEFAULT NULL,
    primary key (id),
    constraint FK__AI_TRANSLATE_RUN__REPOSITORY__ID
        foreign key (repository_id) references repository(id),
    constraint FK__AI_TRANSLATE_RUN__USER__ID
        foreign key (requested_by_user_id) references user(id),
    constraint FK__AI_TRANSLATE_RUN__POLLABLE_TASK__ID
        foreign key (pollable_task_id) references pollable_task(id)
);

create index I__AI_TRANSLATE_RUN__CREATED_DATE on ai_translate_run(created_date);
create index I__AI_TRANSLATE_RUN__REPOSITORY__ID on ai_translate_run(repository_id);
create unique index UK__AI_TRANSLATE_RUN__POLLABLE_TASK__ID on ai_translate_run(pollable_task_id);
