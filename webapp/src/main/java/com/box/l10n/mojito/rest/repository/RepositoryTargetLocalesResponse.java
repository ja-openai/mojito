package com.box.l10n.mojito.rest.repository;

import java.util.List;

public record RepositoryTargetLocalesResponse(
    String sourceLocaleTag, List<String> targetLocaleTags) {}
