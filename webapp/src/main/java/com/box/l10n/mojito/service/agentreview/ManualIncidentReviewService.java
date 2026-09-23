package com.box.l10n.mojito.service.agentreview;

import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.PlannedIncident;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Request;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Result;
import com.box.l10n.mojito.service.agentreview.IncidentReviewBatchService.Skipped;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Manual selections are fresh, finite plans; scheduled sweeps retain their independent cursor. */
@Service
public class ManualIncidentReviewService {
  public record Preview(
      int eligibleIncidentCount,
      int skippedIncidentCount,
      int projectCount,
      List<String> localeTags,
      List<Long> projectIds,
      List<Long> requestIds,
      List<Skipped> skipped,
      int scannedIncidentCount,
      boolean hasMore,
      List<List<Long>> incidentBatches,
      boolean limitReached) {}

  private record Group(Long runId, long repositoryId, long localeId, String key) {}

  private static class Wave {
    final List<List<Long>> projects = new ArrayList<>();
    long wordCount;

    void add(PlannedIncident incident, int maxWords, int maxIncidents) {
      if (projects.isEmpty()
          || projects.getLast().size() == maxIncidents
          || wordCount + incident.wordCount() > maxWords) {
        projects.add(new ArrayList<>());
        wordCount = 0;
      }
      projects.getLast().add(incident.incidentId());
      wordCount += incident.wordCount();
    }
  }

  private final IncidentReviewBatchService batches;

  public ManualIncidentReviewService(IncidentReviewBatchService batches) {
    this.batches = batches;
  }

  public Preview preview(Request request, Long actor) {
    return preview(request, actor, ignored -> {});
  }

  public Preview preview(Request request, Long actor, Consumer<String> progress) {
    int maxIncidents =
        request != null && request.maxIncidentsPerProject() != null
            ? request.maxIncidentsPerProject()
            : 500;
    int maxWords =
        request != null && request.maxWordCountPerProject() != null
            ? request.maxWordCountPerProject()
            : Integer.MAX_VALUE;
    int maxTotal =
        request != null && request.maxIncidentCount() != null
            ? request.maxIncidentCount()
            : Integer.MAX_VALUE;
    Map<Group, List<Wave>> groups = new LinkedHashMap<>();
    Map<Group, Map<Long, Integer>> occurrences = new HashMap<>();
    Set<String> locales = new TreeSet<>();
    List<Skipped> skipped = new ArrayList<>();
    int selected = 0, skippedCount = 0, scannedCount = 0;
    long afterId = 0;
    Long upperBoundId = null;
    boolean limitReached = false;
    while (true) {
      // A separate bounded read transaction avoids retaining entities/locks for the whole queue.
      var page = batches.previewManualPage(request, actor, afterId, upperBoundId);
      upperBoundId = page.upperBoundId();
      scannedCount += page.scannedCount();
      skippedCount += page.skipped().size();
      skipped.addAll(page.skipped());
      if (skipped.size() > 100) skipped.subList(0, skipped.size() - 100).clear();
      for (PlannedIncident incident : page.candidates()) {
        if (selected == maxTotal) {
          limitReached = true;
          break;
        }
        Group group =
            new Group(
                incident.runId(),
                incident.repositoryId(),
                incident.localeId(),
                incident.groupKey());
        int occurrence =
            occurrences
                    .computeIfAbsent(group, ignored -> new HashMap<>())
                    .merge(incident.unitId(), 1, Integer::sum)
                - 1;
        List<Wave> waves = groups.computeIfAbsent(group, ignored -> new ArrayList<>());
        if (waves.size() <= occurrence) waves.add(new Wave());
        waves.get(occurrence).add(incident, maxWords, maxIncidents);
        locales.add(incident.localeTag());
        selected++;
      }
      progress.accept("Preparing incidents: scanned " + scannedCount + "; eligible " + selected);
      if (limitReached || !page.hasMore()) break;
      if (page.lastScannedId() <= afterId)
        throw new IllegalStateException("Incident preview did not advance");
      afterId = page.lastScannedId();
    }
    List<List<Long>> plans =
        groups.values().stream()
            .flatMap(Collection::stream)
            .flatMap(wave -> wave.projects.stream())
            .map(List::copyOf)
            .toList();
    return new Preview(
        selected,
        skippedCount,
        plans.size(),
        List.copyOf(locales),
        List.of(),
        List.of(),
        List.copyOf(skipped),
        scannedCount,
        false,
        plans,
        limitReached);
  }

  public Result create(Request request, Long actor) {
    return create(request, actor, ignored -> {}, ignored -> {});
  }

  /**
   * Each batch returns only after its transaction commits; publish its result before proceeding.
   */
  public Result create(
      Request request, Long actor, Consumer<String> progress, Consumer<Result> partialResult) {
    Result result = new Result(0, 0, 0, List.of(), List.of(), List.of(), List.of(), 0, true);
    partialResult.accept(result);
    if (request.incidentIds() != null) {
      result = batches.createManualProject(request, actor);
      partialResult.accept(result);
      progress.accept("Created " + result.projectCount() + " incident review projects");
      return result;
    }

    Preview plan = preview(request, actor, progress);
    result =
        new Result(
            0,
            plan.skippedIncidentCount(),
            0,
            List.of(),
            List.of(),
            List.of(),
            plan.skipped(),
            plan.scannedIncidentCount(),
            !plan.incidentBatches().isEmpty());
    partialResult.accept(result);
    int checked = 0;
    for (List<Long> ids : plan.incidentBatches()) {
      Result next = batches.createManualProject(withIncidentIds(request, ids), actor);
      checked++;
      List<Skipped> skipped = new ArrayList<>(result.skipped());
      skipped.addAll(next.skipped());
      if (skipped.size() > 100) skipped.subList(0, skipped.size() - 100).clear();
      Set<String> locales = new TreeSet<>(result.localeTags());
      locales.addAll(next.localeTags());
      List<Long> projects = new ArrayList<>(result.projectIds());
      projects.addAll(next.projectIds());
      List<Long> requests = new ArrayList<>(result.requestIds());
      requests.addAll(next.requestIds());
      result =
          new Result(
              result.eligibleIncidentCount() + next.eligibleIncidentCount(),
              result.skippedIncidentCount() + next.skippedIncidentCount(),
              result.projectCount() + next.projectCount(),
              List.copyOf(locales),
              List.copyOf(projects),
              List.copyOf(requests),
              List.copyOf(skipped),
              plan.scannedIncidentCount(),
              checked < plan.incidentBatches().size());
      partialResult.accept(result);
      progress.accept(
          "Creating projects: checked "
              + checked
              + " of "
              + plan.projectCount()
              + "; created "
              + result.projectCount()
              + " projects");
    }
    progress.accept("Created " + result.projectCount() + " incident review projects");
    return result;
  }

  private Request withIncidentIds(Request request, List<Long> ids) {
    return new Request(
        request.repositoryIds(),
        request.reviewFeatureIds(),
        request.localeTags(),
        request.excludedLocaleTags(),
        request.reviewType(),
        request.teamId(),
        request.name(),
        request.dueDate(),
        request.maxWordCountPerProject(),
        request.assignTranslator(),
        request.type(),
        request.notes(),
        request.screenshotImageIds(),
        request.allRepositories(),
        request.maxIncidentCount(),
        request.maxIncidentsPerProject(),
        ids);
  }
}
