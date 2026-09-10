package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.entity.agentreview.AgentReviewProposal;
import com.box.l10n.mojito.entity.review.ReviewProject;
import com.box.l10n.mojito.entity.review.ReviewProjectTextUnit;
import com.box.l10n.mojito.service.agentreview.AgentReviewContracts.Artifact;
import com.box.l10n.mojito.service.review.ReviewProjectRepository;
import com.box.l10n.mojito.service.review.ReviewProjectService;
import com.box.l10n.mojito.service.review.ReviewProjectTextUnitRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Gives human reviewers access only to durable artifacts attached to their project proposals. */
@Service
public class AgentReviewEvidenceService {
  private final ReviewProjectService reviewProjects;
  private final ReviewProjectRepository projects;
  private final ReviewProjectTextUnitRepository rows;
  private final AgentReviewProposalRepository proposals;
  private final AgentReviewService reviews;
  private final ObjectMapper mapper;

  public AgentReviewEvidenceService(
      ReviewProjectService reviewProjects,
      ReviewProjectRepository projects,
      ReviewProjectTextUnitRepository rows,
      AgentReviewProposalRepository proposals,
      AgentReviewService reviews,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper mapper) {
    this.reviewProjects = reviewProjects;
    this.projects = projects;
    this.rows = rows;
    this.proposals = proposals;
    this.reviews = reviews;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public Artifact read(long projectId, long proposalId, String sha256) {
    ReviewProject project = projects.findById(projectId).orElseThrow(this::notFound);
    reviewProjects.assertCurrentUserCanReadProject(project);
    AgentReviewProposal proposal = proposals.findById(proposalId).orElseThrow(this::notFound);
    if (!Objects.equals(proposal.getReviewProjectId(), projectId)
        || proposal.getReviewProjectTextUnitId() == null
        || project.getAgentReviewRunId() == null
        || !Objects.equals(project.getAgentReviewRunId(), proposal.getRunId())) {
      throw notFound();
    }
    ReviewProjectTextUnit row =
        rows.findById(proposal.getReviewProjectTextUnitId()).orElseThrow(this::notFound);
    if (!Objects.equals(row.getReviewProject().getId(), projectId)
        || !Objects.equals(row.getTmTextUnit().getId(), proposal.getTmTextUnitId())
        || !Objects.equals(project.getLocale().getId(), proposal.getLocaleId())
        || !referencesArtifact(proposal.getEvidenceJson(), sha256)) {
      throw notFound();
    }
    return reviews.readStoredArtifact(proposal.getRunId(), sha256);
  }

  private boolean referencesArtifact(String evidenceJson, String sha256) {
    if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || evidenceJson == null) {
      return false;
    }
    try {
      JsonNode evidence = mapper.readTree(evidenceJson);
      if (evidence != null && evidence.isObject()) evidence = evidence.get("items");
      if (evidence == null || !evidence.isArray()) return false;
      for (JsonNode item : evidence) {
        JsonNode reference = item.get("artifactSha256");
        if (reference != null && reference.isTextual() && sha256.equals(reference.textValue())) {
          return true;
        }
      }
    } catch (JsonProcessingException exception) {
      // A malformed legacy evidence field must never grant access to an arbitrary run artifact.
      return false;
    }
    return false;
  }

  private ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Proposal evidence not found");
  }
}
