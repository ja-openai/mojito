alter table review_automation
    add column team_id bigint(20) null after max_word_count_per_project,
    add column due_date_offset_days int not null default 1 after team_id;

alter table review_automation
    add constraint FK__REVIEW_AUTOMATION__TEAM
        foreign key (team_id) references team (id);

create index I__REVIEW_AUTOMATION__TEAM
    on review_automation(team_id);
