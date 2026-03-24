package com.box.l10n.mojito.service.team;

import com.box.l10n.mojito.entity.TeamUserRole;

public record UserTeamByUserProjection(
    Long userId, Long teamId, String teamName, TeamUserRole role) {}
