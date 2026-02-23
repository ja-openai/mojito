create table team (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    name varchar(255) NOT NULL,
    enabled bit not null default true,
    primary key (id)
);

alter table team add constraint UK__TEAM__NAME unique (name);

create table team_user (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    team_id bigint(20) NOT NULL,
    user_id bigint(20) NOT NULL,
    role varchar(32) NOT NULL,
    primary key (id)
);

alter table team_user add constraint FK__TEAM_USER__TEAM foreign key (team_id) references team (id);
alter table team_user add constraint FK__TEAM_USER__USER foreign key (user_id) references user (id);
alter table team_user add constraint UK__TEAM_USER__TEAM_USER_ROLE unique (team_id, user_id, role);
create index I__TEAM_USER__USER_ROLE on team_user(user_id, role);

create table team_locale_pool (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    team_id bigint(20) NOT NULL,
    locale_id bigint(20) NOT NULL,
    translator_user_id bigint(20) NOT NULL,
    position int NOT NULL,
    primary key (id)
);

alter table team_locale_pool add constraint FK__TEAM_LOCALE_POOL__TEAM foreign key (team_id) references team (id);
alter table team_locale_pool add constraint FK__TEAM_LOCALE_POOL__LOCALE foreign key (locale_id) references locale (id);
alter table team_locale_pool add constraint FK__TEAM_LOCALE_POOL__TRANSLATOR_USER foreign key (translator_user_id) references user (id);
alter table team_locale_pool add constraint UK__TEAM_LOCALE_POOL__TEAM_LOCALE_TRANSLATOR unique (team_id, locale_id, translator_user_id);
alter table team_locale_pool add constraint UK__TEAM_LOCALE_POOL__TEAM_LOCALE_POSITION unique (team_id, locale_id, position);

create table team_pm_pool (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    team_id bigint(20) NOT NULL,
    pm_user_id bigint(20) NOT NULL,
    position int NOT NULL,
    primary key (id)
);

alter table team_pm_pool add constraint FK__TEAM_PM_POOL__TEAM foreign key (team_id) references team (id);
alter table team_pm_pool add constraint FK__TEAM_PM_POOL__PM_USER foreign key (pm_user_id) references user (id);
alter table team_pm_pool add constraint UK__TEAM_PM_POOL__TEAM_PM unique (team_id, pm_user_id);
alter table team_pm_pool add constraint UK__TEAM_PM_POOL__TEAM_POSITION unique (team_id, position);

alter table review_project add column team_id bigint(20) DEFAULT NULL;
alter table review_project add column assigned_pm_user_id bigint(20) DEFAULT NULL;
alter table review_project add column assigned_translator_user_id bigint(20) DEFAULT NULL;

alter table review_project add constraint FK__REVIEW_PROJECT__TEAM foreign key (team_id) references team (id);
alter table review_project add constraint FK__REVIEW_PROJECT__ASSIGNED_PM foreign key (assigned_pm_user_id) references user (id);
alter table review_project add constraint FK__REVIEW_PROJECT__ASSIGNED_TRANSLATOR foreign key (assigned_translator_user_id) references user (id);

create table review_project_assignment_history (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    created_date datetime DEFAULT NULL,
    last_modified_date datetime DEFAULT NULL,
    review_project_id bigint(20) NOT NULL,
    team_id bigint(20) DEFAULT NULL,
    assigned_pm_user_id bigint(20) DEFAULT NULL,
    assigned_translator_user_id bigint(20) DEFAULT NULL,
    event_type varchar(32) NOT NULL,
    note varchar(512) DEFAULT NULL,
    created_by_user_id bigint(20) DEFAULT NULL,
    primary key (id)
);

alter table review_project_assignment_history add constraint FK__RP_ASSIGNMENT_HISTORY__PROJECT foreign key (review_project_id) references review_project (id);
alter table review_project_assignment_history add constraint FK__RP_ASSIGNMENT_HISTORY__TEAM foreign key (team_id) references team (id);
alter table review_project_assignment_history add constraint FK__RP_ASSIGNMENT_HISTORY__ASSIGNED_PM foreign key (assigned_pm_user_id) references user (id);
alter table review_project_assignment_history add constraint FK__RP_ASSIGNMENT_HISTORY__ASSIGNED_TRANSLATOR foreign key (assigned_translator_user_id) references user (id);
alter table review_project_assignment_history add constraint FK__RP_ASSIGNMENT_HISTORY__CREATED_BY_USER foreign key (created_by_user_id) references user (id);

create index I__RP_ASSIGNMENT_HISTORY__PROJECT_ID on review_project_assignment_history(review_project_id);
create index I__RP_ASSIGNMENT_HISTORY__PROJECT_CREATED on review_project_assignment_history(review_project_id, created_date);
