create table ai_review_request_usage (
    id bigint(20) not null auto_increment,
    user_id bigint(20) default null,
    pollable_task_id bigint(20) default null,
    tm_text_unit_id bigint(20) default null,
    locale varchar(64) default null,
    surface varchar(32) not null,
    request_type varchar(32) not null,
    profile_id varchar(32) not null,
    model_name varchar(255) not null,
    reasoning_effort varchar(32) default null,
    requested_service_tier varchar(32) default null,
    returned_model varchar(255) default null,
    returned_service_tier varchar(32) default null,
    status varchar(32) not null,
    started_at datetime not null,
    finished_at datetime default null,
    duration_ms bigint(20) default null,
    primary key (id),
    constraint FK__AIRRU__USER__ID
        foreign key (user_id) references user(id) on delete set null
);

create index I__AIRRU__USER_STARTED on ai_review_request_usage(user_id, started_at);
create index I__AIRRU__PROFILE_TYPE_STARTED
    on ai_review_request_usage(profile_id, request_type, started_at);
create index I__AIRRU__POLLABLE_TASK on ai_review_request_usage(pollable_task_id);
