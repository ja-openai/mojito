package com.box.l10n.mojito.service.content;

import com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/** Scalar metadata and exact branch-extraction membership; source templates never come from SQL. */
@Repository
public class RepositoryContentQueries {
  private static final String MEMBERSHIP =
      """
      from AssetExtractionByBranch mapping
      join mapping.asset asset join mapping.branch branch join mapping.assetExtraction extraction
      where mapping.deleted = false and branch.deleted = false and asset.deleted = false
        and extraction.asset = asset and branch.repository = asset.repository
        and asset.repository.id = :repositoryId and branch.id = :branchId
        and lower(asset.path) like '%.mdx'
      """;

  private static final String TEMPLATES =
      """
      select new com.box.l10n.mojito.service.review.ReviewProjectDocumentTemplate(
        asset.id, asset.path, asset.repository.id, branch.id, branch.name,
        case when extraction.filterOptionsMd5 is not null then extraction.contentMd5 else null end,
        asset.repository.sourceLocale.bcp47Tag)
      """
          + MEMBERSHIP;

  private static final String BRANCHES =
      """
      select new com.box.l10n.mojito.service.content.RepositoryContentIndex$Branch(branch.id, branch.name)
      from Branch branch where branch.repository.id = :repositoryId and branch.deleted = false
      """;

  /** Search compares the full asset path. Only the legacy contains mode folds case. */
  public record Filter(String search, String searchMode, String directory, boolean recursive) {
    public Filter(String search, String searchMode, String directory) {
      this(search, searchMode, directory, true);
    }
  }

  private final EntityManager entityManager;

  public RepositoryContentQueries(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public List<ReviewProjectDocumentTemplate> list(
      Long repositoryId, Long branchId, String pathSearch, int offset, int limit) {
    return list(repositoryId, branchId, new Filter(pathSearch, "contains", ""), offset, limit);
  }

  public List<RepositoryContentIndex.Branch> branches(Long repositoryId, int limit) {
    return entityManager
        .createQuery(BRANCHES + " order by branch.id", RepositoryContentIndex.Branch.class)
        .setParameter("repositoryId", repositoryId)
        .setMaxResults(limit)
        .getResultList();
  }

  public List<RepositoryContentIndex.Branch> defaultBranches(Long repositoryId) {
    return entityManager
        .createQuery(
            BRANCHES + " and branch.name is null order by branch.id",
            RepositoryContentIndex.Branch.class)
        .setParameter("repositoryId", repositoryId)
        .setMaxResults(2)
        .getResultList();
  }

  public RepositoryContentIndex.Branch branch(Long repositoryId, Long branchId) {
    return entityManager
        .createQuery(BRANCHES + " and branch.id = :branchId", RepositoryContentIndex.Branch.class)
        .setParameter("repositoryId", repositoryId)
        .setParameter("branchId", branchId)
        .getResultStream()
        .findFirst()
        .orElse(null);
  }

  public List<ReviewProjectDocumentTemplate> list(
      Long repositoryId, Long branchId, Filter filter, int offset, int limit) {
    var parameters = parameters(repositoryId, branchId);
    String predicates = predicates(filter, parameters);
    return bind(
            entityManager.createQuery(
                TEMPLATES + predicates + " order by asset.path, asset.id",
                ReviewProjectDocumentTemplate.class),
            parameters)
        .setFirstResult(offset)
        .setMaxResults(limit)
        .getResultList();
  }

  public List<ReviewProjectDocumentTemplate> seek(
      Long repositoryId,
      Long branchId,
      Filter filter,
      RepositoryContentCursor cursor,
      boolean backward,
      int limit) {
    var parameters = parameters(repositoryId, branchId);
    String predicates = predicates(filter, parameters);
    String comparator = backward ? "<" : ">";
    if (cursor != null) {
      predicates +=
          " and (asset.path "
              + comparator
              + " :cursorPath or (asset.path = :cursorPath and asset.id "
              + comparator
              + " :cursorId))";
      parameters.put("cursorPath", cursor.path());
      parameters.put("cursorId", cursor.assetId());
    }
    String order =
        backward ? " order by asset.path desc, asset.id desc" : " order by asset.path, asset.id";
    return bind(
            entityManager.createQuery(
                TEMPLATES + predicates + order, ReviewProjectDocumentTemplate.class),
            parameters)
        .setMaxResults(limit)
        .getResultList();
  }

  public List<String> directories(
      Long repositoryId, Long branchId, String directory, String after, int limit) {
    var parameters = parameters(repositoryId, branchId);
    String predicates = predicates(new Filter("", "prefix", directory), parameters);
    // Let SQL count characters consistently with locate/substring (Java length counts UTF-16
    // units).
    parameters.put("directoryBase", directory);
    String separator = "locate('/', asset.path, length(:directoryBase) + 1)";
    String child = "substring(asset.path, 1, " + separator + ")";
    predicates += " and " + separator + " > 0";
    if (after != null) {
      predicates += " and " + child + " > :afterDirectory";
      parameters.put("afterDirectory", after);
    }
    return bind(
            entityManager.createQuery(
                "select distinct "
                    + child
                    + " as directoryPath "
                    + MEMBERSHIP
                    + predicates
                    + " order by directoryPath",
                String.class),
            parameters)
        .setMaxResults(limit)
        .getResultList();
  }

  private static Map<String, Object> parameters(Long repositoryId, Long branchId) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("repositoryId", repositoryId);
    parameters.put("branchId", branchId);
    return parameters;
  }

  private static String predicates(Filter filter, Map<String, Object> parameters) {
    String result = "";
    if (!filter.directory().isEmpty()) {
      result += " and asset.path like :directoryPrefix escape '!'";
      parameters.put("directoryPrefix", escapeLike(filter.directory()) + "%");
    }
    if (!filter.recursive()) {
      result += " and locate('/', asset.path, length(:directoryBase) + 1) = 0";
      parameters.put("directoryBase", filter.directory());
    }
    if (!filter.search().isEmpty()) {
      switch (filter.searchMode()) {
        case "exact" -> {
          result += " and asset.path = :pathSearch";
          parameters.put("pathSearch", filter.search());
        }
        case "prefix" -> {
          result += " and asset.path like :pathSearch escape '!'";
          parameters.put("pathSearch", escapeLike(filter.search()) + "%");
        }
        case "contains" -> {
          result += " and locate(:pathSearch, lower(asset.path)) > 0";
          parameters.put("pathSearch", filter.search());
        }
        default -> throw new IllegalArgumentException("Unknown path search mode");
      }
    }
    return result;
  }

  private static String escapeLike(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static <T> TypedQuery<T> bind(TypedQuery<T> query, Map<String, Object> parameters) {
    parameters.forEach(query::setParameter);
    return query;
  }

  public ReviewProjectDocumentTemplate find(Long repositoryId, Long branchId, Long assetId) {
    return entityManager
        .createQuery(TEMPLATES + " and asset.id = :assetId", ReviewProjectDocumentTemplate.class)
        .setParameter("repositoryId", repositoryId)
        .setParameter("branchId", branchId)
        .setParameter("assetId", assetId)
        .getResultStream()
        .findFirst()
        .orElse(null);
  }

  public List<RepositoryContentTextUnit> textUnits(
      ReviewProjectDocumentTemplate template,
      Long localeId,
      List<String> names,
      int maxTargetCharacters) {
    return entityManager
        .createQuery(
            """
        select new com.box.l10n.mojito.service.content.RepositoryContentTextUnit(
          asset.id, tu.name, tu.content, tu.id, variant.id,
          substring(variant.content, 1, :maxTargetCharacters), variant.status)
        from AssetExtractionByBranch branchMapping
        join branchMapping.asset asset join branchMapping.branch branch
        join branchMapping.assetExtraction extraction
        join AssetTextUnitToTMTextUnit mapping on mapping.assetExtraction = extraction
        join mapping.tmTextUnit tu
        left join TMTextUnitCurrentVariant currentVariant
          on currentVariant.tmTextUnit = tu and currentVariant.locale.id = :localeId
        left join currentVariant.tmTextUnitVariant variant
          on variant.tmTextUnit = tu and variant.locale.id = :localeId
        where branchMapping.deleted = false and branch.deleted = false and asset.deleted = false
          and asset.repository.id = :repositoryId and branch.repository = asset.repository
          and branch.id = :branchId and asset.id = :assetId and extraction.asset = asset
          and extraction.contentMd5 = :contentMd5 and extraction.filterOptionsMd5 is not null
          and mapping.assetTextUnit.assetExtraction = extraction and tu.asset = asset
          and mapping.assetTextUnit.doNotTranslate = false
          and tu.name in :names
        order by tu.id
        """,
            RepositoryContentTextUnit.class)
        .setParameter("repositoryId", template.repositoryId())
        .setParameter("branchId", template.branchId())
        .setParameter("assetId", template.assetId())
        .setParameter("contentMd5", template.contentMd5())
        .setParameter("localeId", localeId)
        .setParameter("names", names)
        .setParameter("maxTargetCharacters", maxTargetCharacters)
        .setMaxResults(names.size() + 1)
        .getResultList();
  }
}
