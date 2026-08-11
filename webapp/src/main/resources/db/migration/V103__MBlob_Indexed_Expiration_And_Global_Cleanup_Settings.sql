alter table mblob
    add column expiration_date datetime generated always as
        (timestampadd(SECOND, expire_after_seconds, created_date)) virtual,
    algorithm=instant;

alter table mblob
    add index I__MBLOB__EXPIRATION_DATE_ID (expiration_date, id),
    algorithm=inplace,
    lock=none;

create table mblob_cleanup_settings (
    id bigint not null auto_increment,
    created_date datetime default null,
    last_modified_date datetime default null,
    enabled bit not null default false,
    batch_size int not null default 500,
    max_batches_per_run int not null default 100,
    pause_millis int not null default 250,
    max_retries int not null default 5,
    stop_requested bit not null default false,
    status varchar(32) not null default 'IDLE',
    last_started_date datetime default null,
    last_progress_date datetime default null,
    last_finished_date datetime default null,
    last_deleted_count bigint not null default 0,
    total_deleted_count bigint not null default 0,
    last_error varchar(2048) default null,
    primary key (id)
);

insert into mblob_cleanup_settings (enabled) values (false);
