alter table team add column slack_notifications_enabled bit not null default false;
alter table team add column slack_client_id varchar(255) DEFAULT NULL;
alter table team add column slack_channel_id varchar(64) DEFAULT NULL;

create table team_slack_user_mapping (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    team_id bigint(20) NOT NULL,
    mojito_user_id bigint(20) NOT NULL,
    slack_user_id varchar(64) NOT NULL,
    slack_username varchar(255) DEFAULT NULL,
    match_source varchar(32) DEFAULT NULL,
    last_verified_at datetime DEFAULT NULL,
    primary key (id)
);

alter table team_slack_user_mapping add constraint FK__TEAM_SLACK_USER_MAPPING__TEAM foreign key (team_id) references team (id);
alter table team_slack_user_mapping add constraint FK__TEAM_SLACK_USER_MAPPING__MOJITO_USER foreign key (mojito_user_id) references user (id);
alter table team_slack_user_mapping add constraint UK__TEAM_SLACK_USER_MAPPING__TEAM_MOJITO_USER unique (team_id, mojito_user_id);

create index I__TEAM_SLACK_USER_MAPPING__TEAM on team_slack_user_mapping(team_id);
create index I__TEAM_SLACK_USER_MAPPING__SLACK_USER on team_slack_user_mapping(slack_user_id);
