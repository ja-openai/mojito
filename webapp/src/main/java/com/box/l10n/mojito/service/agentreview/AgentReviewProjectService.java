package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.Locale;
import com.box.l10n.mojito.entity.TMTextUnit;
import com.box.l10n.mojito.entity.TranslationIncident;
import com.box.l10n.mojito.entity.TranslationIncidentResolution;
import com.box.l10n.mojito.entity.TranslationIncidentStatus;
import com.box.l10n.mojito.entity.agentreview.*;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.service.badtranslation.TranslationIncidentRepository;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.tm.TMTextUnitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.ZonedDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Typed incidents route committed review groups into ordinary, immutable-membership projects. */
@Service
public class AgentReviewProjectService {
  private static final Logger logger = LoggerFactory.getLogger(AgentReviewProjectService.class);
  private final AgentReviewService reviews;
  private final AgentReviewRunRepository runs;
  private final AgentReviewProposalRepository proposals;
  private final TranslationIncidentRepository incidents;
  private final TMTextUnitRepository textUnits;
  private final ReviewProjectService reviewProjects;
  private final ReviewProjectRepository projects;
  private final TransactionTemplate transactions;
  private final String automaticReviewType;
  @PersistenceContext private EntityManager entityManager;

  public AgentReviewProjectService(
      AgentReviewService reviews,
      AgentReviewRunRepository runs,
      AgentReviewProposalRepository proposals,
      TranslationIncidentRepository incidents,
      TMTextUnitRepository textUnits,
      ReviewProjectService reviewProjects,
      ReviewProjectRepository projects,
      PlatformTransactionManager transactionManager,
      @Value("${l10n.agent-review.automatic-review-type:TRANSLATION_QUALITY}")
          String automaticReviewType) {
    this.reviews = reviews;
    this.runs = runs;
    this.proposals = proposals;
    this.incidents = incidents;
    this.textUnits = textUnits;
    this.reviewProjects = reviewProjects;
    this.projects = projects;
    this.automaticReviewType = automaticReviewType;
    this.transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public record RouteResult(
      long runId,
      List<Long> projectIds,
      int proposalCount,
      int skippedCount,
      List<String> errors) {}

  private record RoutingGroup(Long repositoryId, Long localeId, String groupKey) {}

  /** Kept outside the checkpoint transaction: routing failure must not erase durable progress. */
  public RouteResult routeRun(long runId) {
    reviews.getRun(
        runId); // Authorize before any recovery handler; denial is never a routing result.
    try {
      return transactions.execute(status -> routeLockedRun(runId));
    } catch (AccessDeniedException | IllegalArgumentException exception) {
      throw exception;
    } catch (ResponseStatusException exception) {
      if (exception.getStatusCode().is4xxClientError()) throw exception;
      return routingFailure(runId, exception);
    } catch (RuntimeException exception) {
      return routingFailure(runId, exception);
    }
  }

  private RouteResult routingFailure(long runId, RuntimeException exception) {
    logger.error("Automatic agent review project routing failed for run {}", runId, exception);
    return new RouteResult(
        runId,
        List.of(),
        0,
        0,
        List.of("Project creation failed. Review progress is saved; retry routing."));
  }

  private RouteResult routeLockedRun(long runId) {
    AgentReviewRun run =
        runs.findForUpdateById(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review run not found"));
    AgentReviewContracts.RunView view = reviews.getRun(runId);
    if (!Objects.equals(automaticReviewType, run.getReviewType())
        || run.getStatus() == RunStatus.CANCELLED) {
      return new RouteResult(runId, projectIds(runId), 0, 0, List.of());
    }
    List<AgentReviewProposal> ready = reviews.findReadyProposalsForUpdate(runId);
    Map<RoutingGroup, List<AgentReviewProposal>> groups = new LinkedHashMap<>();
    List<String> errors = new ArrayList<>();
    int skipped = 0;
    for (AgentReviewProposal proposal : ready) {
      AgentReviewContracts.GroupCheckpoint checkpoint =
          view.checkpoint().groups().get(proposal.getGroupKey());
      if (checkpoint == null
          || checkpoint.status() != AgentReviewContracts.GroupStatus.COMPLETED
          || proposal.getCategory() == Category.OPTIONAL_IMPROVEMENT
          || proposal.getReviewProjectTextUnitId() != null) {
        skipped++;
        continue;
      }
      Optional<TranslationIncident> existingIncident =
          incidents.findForUpdateByReviewFindingId(proposal.getFindingId());
      if (existingIncident.isPresent()
          && existingIncident.get().getStatus() == TranslationIncidentStatus.CLOSED) {
        skipped++;
        errors.add(
            "Finding "
                + proposal.getFindingId()
                + ": its incident is closed. Reopen the incident explicitly before routing.");
        continue;
      }
      groups
          .computeIfAbsent(
              new RoutingGroup(
                  proposal.getRepositoryId(), proposal.getLocaleId(), proposal.getGroupKey()),
              key -> new ArrayList<>())
          .add(proposal);
    }
    int routed = 0;
    for (Map.Entry<RoutingGroup, List<AgentReviewProposal>> group : groups.entrySet()) {
      // Multiple issue reports for one string remain independent, but a project row has one target.
      // Route one proposal per string in each wave rather than overwrite an alternative proposal.
      List<AgentReviewProposal> remaining = new ArrayList<>(group.getValue());
      while (!remaining.isEmpty()) {
        Set<Long> seen = new HashSet<>();
        List<AgentReviewProposal> wave = new ArrayList<>();
        for (Iterator<AgentReviewProposal> iterator = remaining.iterator(); iterator.hasNext(); ) {
          AgentReviewProposal proposal = iterator.next();
          if (seen.add(proposal.getTmTextUnitId())) {
            wave.add(proposal);
            iterator.remove();
          }
        }
        List<Long> created =
            reviewProjects.createAgentReviewProjects(
                runId,
                group.getKey().localeId(),
                "Translation review · Run " + runId + " · " + group.getKey().groupKey(),
                run.getTeamId(),
                ZonedDateTime.now().plusDays(run.getDueDateOffsetDays()),
                run.getMaxWordCountPerProject(),
                run.getAssignTranslator(),
                wave.stream()
                    .map(
                        proposal ->
                            new ReviewProjectService.AgentReviewCandidate(
                                proposal.getTmTextUnitId(),
                                proposal.getBaselineVariantId(),
                                proposal.getSource()))
                    .toList());
        List<ReviewProjectTextUnit> rows =
            entityManager
                .createQuery(
                    "select r from ReviewProjectTextUnit r join fetch r.reviewProject where r.reviewProject.id in :ids",
                    ReviewProjectTextUnit.class)
                .setParameter("ids", created)
                .getResultList();
        Map<Long, ReviewProjectTextUnit> rowsByString = new HashMap<>();
        for (ReviewProjectTextUnit row : rows) rowsByString.put(row.getTmTextUnit().getId(), row);
        for (AgentReviewProposal proposal : wave) {
          ReviewProjectTextUnit row = rowsByString.get(proposal.getTmTextUnitId());
          if (row == null)
            throw new IllegalStateException("Created project omitted a reviewed string");
          TranslationIncident incident =
              incidents
                  .findByReviewFindingId(proposal.getFindingId())
                  .orElseGet(() -> createIncident(run, proposal));
          incident.setResolutionReviewProjectId(row.getReviewProject().getId());
          reviews.linkProposal(
              proposal.getId(), incident.getId(), row.getReviewProject().getId(), row.getId());
          routed++;
        }
      }
    }
    return new RouteResult(runId, projectIds(runId), routed, skipped, errors);
  }

  private TranslationIncident createIncident(AgentReviewRun run, AgentReviewProposal proposal) {
    TMTextUnit unit =
        textUnits
            .findById(proposal.getTmTextUnitId())
            .orElseThrow(() -> new IllegalStateException("Reviewed string no longer exists"));
    Locale locale = entityManager.find(Locale.class, proposal.getLocaleId());
    TranslationIncident incident = new TranslationIncident();
    incident.setStatus(TranslationIncidentStatus.OPEN);
    incident.setResolution(TranslationIncidentResolution.PENDING_REVIEW);
    incident.setReviewType(run.getReviewType());
    incident.setReviewRunId(run.getId());
    incident.setReviewFindingId(proposal.getFindingId());
    incident.setLookupResolutionStatus("UNIQUE_MATCH");
    incident.setLocaleResolutionStrategy("EXACT");
    incident.setLocaleUsedFallback(false);
    incident.setRepositoryName(unit.getAsset().getRepository().getName());
    incident.setStringId(unit.getName());
    incident.setObservedLocale(locale.getBcp47Tag());
    incident.setResolvedLocale(locale.getBcp47Tag());
    incident.setResolvedLocaleId(locale.getId());
    incident.setReason(proposal.getRationale());
    incident.setSourceReference("agent-review:" + run.getId() + "/" + proposal.getFindingId());
    incident.setLookupCandidateCount(1);
    incident.setSelectedTmTextUnitId(unit.getId());
    incident.setSelectedTmTextUnitVariantId(proposal.getBaselineVariantId());
    incident.setSelectedSource(proposal.getSource());
    incident.setSelectedTarget(proposal.getBaselineTarget());
    incident.setSelectedTranslationStatus(proposal.getBaselineStatus());
    incident.setSelectedIncludedInLocalizedFile(proposal.getBaselineIncludedInLocalizedFile());
    incident.setSelectedAssetPath(unit.getAsset().getPath());
    incident.setSelectedCanReject(false);
    return incidents.save(incident);
  }

  private List<Long> projectIds(long runId) {
    return entityManager
        .createQuery(
            "select p.id from ReviewProject p where p.agentReviewRunId = :runId order by p.id",
            Long.class)
        .setParameter("runId", runId)
        .getResultList();
  }

  public record FeedbackView(
      Long id,
      Long proposalId,
      int proposalRevision,
      String actorType,
      String actorIdentity,
      String action,
      String originalAssessment,
      String suggestionAssessment,
      String explanation,
      String evidenceJson,
      String finalTarget,
      ZonedDateTime createdDate,
      Long respondsToFeedbackId,
      Long responseProposalId) {}

  @Transactional(readOnly = true)
  public List<FeedbackView> history(long projectId, long proposalId) {
    return history(projectId, proposalId, 0, 200);
  }

  @Transactional(readOnly = true)
  public List<FeedbackView> history(long projectId, long proposalId, long afterId, int limit) {
    if (afterId < 0 || limit < 1 || limit > 200) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "afterId must be nonnegative and limit between 1 and 200");
    }
    ReviewProject project =
        projects
            .findById(projectId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Review project not found"));
    reviewProjects.assertCurrentUserCanReadProject(project);
    AgentReviewProposal proposal =
        proposals
            .findById(proposalId)
            .filter(p -> Objects.equals(p.getReviewProjectId(), projectId))
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Proposal not found in project"));
    List<Object[]> rows =
        entityManager
            .createQuery(
                "select f, p.proposalRevision from AgentReviewFeedback f, AgentReviewProposal p "
                    + "where f.proposalId = p.id and p.findingId = :findingId and f.id > :afterId order by f.id",
                Object[].class)
            .setParameter("findingId", proposal.getFindingId())
            .setParameter("afterId", afterId)
            .setMaxResults(limit)
            .getResultList();
    return rows.stream()
        .map(
            row -> {
              AgentReviewFeedback f = (AgentReviewFeedback) row[0];
              return new FeedbackView(
                  f.getId(),
                  f.getProposalId(),
                  (Integer) row[1],
                  f.getActorType().name(),
                  f.getActorIdentity(),
                  f.getAction().name(),
                  f.getOriginalAssessment() == null ? null : f.getOriginalAssessment().name(),
                  f.getSuggestionAssessment() == null ? null : f.getSuggestionAssessment().name(),
                  f.getExplanation(),
                  f.getEvidenceJson(),
                  f.getFinalTarget(),
                  f.getCreatedDate(),
                  f.getRespondsToFeedbackId(),
                  f.getResponseProposalId());
            })
        .toList();
  }
}
