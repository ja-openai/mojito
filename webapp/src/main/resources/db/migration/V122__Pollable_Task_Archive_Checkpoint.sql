create table pollable_task_archive_checkpoint (
    id integer not null,
    last_finished_date datetime(6) not null,
    last_task_id bigint not null,
    cutoff_finished_before datetime(6) default null,
    high_water_finished_date datetime(6) default null,
    high_water_task_id bigint default null,
    delete_source_mode bit not null default false,
    retention_days integer not null default 90,
    lease_token varchar(64) default null,
    lease_expires_at datetime(6) default null,
    primary key (id)
);

insert into pollable_task_archive_checkpoint (
    id,
    last_finished_date,
    last_task_id,
    delete_source_mode,
    retention_days
) values (1, '1970-01-01 00:00:00', 0, false, 90);

create table pollable_task_archive_retry (
    task_id bigint not null,
    finished_date datetime(6) not null,
    attempt_count integer not null,
    next_attempt_at datetime(6) not null,
    last_error varchar(2048) default null,
    primary key (task_id),
    index I__POLLABLE_TASK_ARCHIVE_RETRY__NEXT_ATTEMPT_TASK (next_attempt_at, task_id)
);
