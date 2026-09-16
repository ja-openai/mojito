-- Snapshot evidence is intentionally separate from canonical blob locations. No source data is changed.
create table mblob_migration_run (
    id varchar(36) not null,
    allowed_prefixes varchar(2048) not null,
    destination_root varchar(2048) not null,
    high_water_id bigint not null,
    cursor_id bigint not null default 0,
    max_rows integer not null,
    max_bytes bigint not null,
    max_seconds integer not null,
    max_retries integer not null,
    status varchar(32) not null,
    requested bit not null default false,
    lease_token varchar(36),
    lease_until datetime(6),
    scanned_count bigint not null default 0,
    verified_count bigint not null default 0,
    verified_bytes bigint not null default 0,
    preserved_count bigint not null default 0,
    failed_count bigint not null default 0,
    last_error varchar(256),
    primary key (id)
);
create index I__MBLOB_MIGRATION_RUN__READY on mblob_migration_run (requested, status);
create table mblob_migration_item (
    run_id varchar(36) not null,
    source_id bigint not null,
    source_name varchar(255),
    source_length bigint,
    source_created_date datetime(6),
    source_expire_seconds bigint,
    sha256 varchar(64),
    snapshot_name varchar(255),
    disposition varchar(32) not null,
    attempts integer not null,
    last_error varchar(256),
    verified_at datetime(6),
    primary key (run_id, source_id),
    constraint FK__MBLOB_MIGRATION_ITEM__RUN foreign key (run_id) references mblob_migration_run(id)
);
create index I__MBLOB_MIGRATION_ITEM__RETRY on mblob_migration_item (run_id, disposition, source_id);

-- Canonical promotion is explicit maintenance work and has no scheduled/default-enabled path.
create table mblob_migration_promotion (
    id varchar(36) not null,
    snapshot_run_id varchar(36) not null,
    fence_id varchar(128) not null,
    manifest_sha256 varchar(64) not null,
    destination_root varchar(2048) not null,
    source_high_water_id bigint not null,
    promotion_cursor bigint not null default 0,
    reconciliation_cursor bigint not null default 0,
    phase varchar(32) not null,
    status varchar(32) not null,
    lease_token varchar(36),
    lease_until datetime(6),
    reconciled_count bigint not null default 0,
    canonical_count bigint not null default 0,
    retained_count bigint not null default 0,
    retained_bytes bigint not null default 0,
    reconciled_at datetime(6),
    fence_valid_until datetime(6),
    last_error varchar(256),
    primary key (id),
    constraint FK__MBLOB_MIGRATION_PROMOTION__RUN foreign key (snapshot_run_id) references mblob_migration_run(id)
);
create table mblob_migration_promotion_item (
    promotion_id varchar(36) not null,
    source_id bigint not null,
    source_name varchar(255),
    source_length bigint,
    source_created_date datetime(6),
    source_expire_seconds bigint,
    source_sha256 varchar(64),
    canonical_etag varchar(255),
    disposition varchar(64) not null,
    reconciled bit not null default false,
    verified_at datetime(6),
    last_error varchar(256),
    primary key (promotion_id, source_id),
    constraint FK__MBLOB_MIGRATION_PROMOTION_ITEM__RUN foreign key (promotion_id) references mblob_migration_promotion(id)
);
create index I__MBLOB_PROMOTION_ITEM__DISPOSITION on mblob_migration_promotion_item (promotion_id, disposition, reconciled);
