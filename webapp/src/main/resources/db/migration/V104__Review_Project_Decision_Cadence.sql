alter table review_project_time_spent_stat
    add column decision_interval_count bigint(20) default null,
    add column rapid_decision_interval_count bigint(20) default null,
    add column median_decision_interval_seconds bigint(20) default null,
    add column p90_decision_interval_seconds bigint(20) default null,
    add column p95_decision_interval_seconds bigint(20) default null;
