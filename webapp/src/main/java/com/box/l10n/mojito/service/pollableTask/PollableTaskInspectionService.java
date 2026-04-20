package com.box.l10n.mojito.service.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.entity.Repository;
import com.box.l10n.mojito.json.ObjectMapper;
import com.box.l10n.mojito.service.repository.RepositoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.UncheckedIOException;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class PollableTaskInspectionService {

  private static final Pattern SIMPLE_CLASS_NAME = Pattern.compile(".*\\.([^.]+)$");

  private final PollableTaskService pollableTaskService;
  private final PollableTaskBlobStorage pollableTaskBlobStorage;
  private final RepositoryRepository repositoryRepository;
  private final ObjectMapper objectMapper;

  public PollableTaskInspectionService(
      PollableTaskService pollableTaskService,
      PollableTaskBlobStorage pollableTaskBlobStorage,
      RepositoryRepository repositoryRepository,
      @Qualifier("fail_on_unknown_properties_false") ObjectMapper objectMapper) {
    this.pollableTaskService = Objects.requireNonNull(pollableTaskService);
    this.pollableTaskBlobStorage = Objects.requireNonNull(pollableTaskBlobStorage);
    this.repositoryRepository = Objects.requireNonNull(repositoryRepository);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  public TaskInspection inspectTask(long pollableTaskId) {
    PollableTask pollableTask = pollableTaskService.getPollableTask(pollableTaskId);
    if (pollableTask == null) {
      throw new IllegalArgumentException("Pollable task not found: " + pollableTaskId);
    }

    List<PollableTask> failures =
        pollableTaskService.getAllPollableTasksWithError(pollableTask).stream()
            .sorted(Comparator.comparing(PollableTask::getId))
            .toList();

    List<TaskFailure> taskFailures = failures.stream().map(this::toFailure).toList();

    StoredTaskData storedTaskData = getStoredTaskData(pollableTask.getId());
    TaskRepositoryRef repository =
        firstNonNull(
            resolveRepository(pollableTask, storedTaskData, false),
            taskFailures.stream()
                .map(TaskFailure::repository)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null));

    return new TaskInspection(
        pollableTask.getId(),
        statusFor(pollableTask, failures),
        operationName(pollableTask.getName()),
        pollableTask.getName(),
        parseSemiStructuredValue(pollableTask.getMessage()),
        repository,
        pollableTask.getCreatedDate(),
        pollableTask.getFinishedDate(),
        pollableTask.getParentTask() == null ? null : pollableTask.getParentTask().getId(),
        pollableTask.getExpectedSubTaskNumber(),
        pollableTask.getSubTasks() == null ? 0 : pollableTask.getSubTasks().size(),
        pollableTask.getSubTasks() == null
            ? List.of()
            : pollableTask.getSubTasks().stream().map(PollableTask::getId).sorted().toList(),
        toError(pollableTask),
        storedTaskData.input(),
        storedTaskData.output(),
        taskFailures,
        linksFor(pollableTask.getId()));
  }

  private TaskFailure toFailure(PollableTask pollableTask) {
    StoredTaskData storedTaskData = getStoredTaskData(pollableTask.getId());
    return new TaskFailure(
        pollableTask.getId(),
        operationName(pollableTask.getName()),
        pollableTask.getName(),
        statusFor(pollableTask, List.of(pollableTask)),
        resolveRepository(pollableTask, storedTaskData, true),
        toError(pollableTask),
        storedTaskData.input(),
        storedTaskData.output(),
        pollableTask.getCreatedDate(),
        pollableTask.getFinishedDate(),
        linksFor(pollableTask.getId()));
  }

  private TaskStatus statusFor(PollableTask pollableTask, List<PollableTask> failures) {
    if (!failures.isEmpty()) {
      return TaskStatus.FAILED;
    }

    if (pollableTask.isAllFinished()) {
      return TaskStatus.SUCCEEDED;
    }

    return TaskStatus.IN_PROGRESS;
  }

  private StoredTaskData getStoredTaskData(Long pollableTaskId) {
    return new StoredTaskData(
        parseStoredJson(pollableTaskBlobStorage.findInputJson(pollableTaskId)),
        parseStoredJson(pollableTaskBlobStorage.findOutputJson(pollableTaskId)));
  }

  private JsonNode parseStoredJson(Optional<String> rawJson) {
    return rawJson
        .filter(value -> !value.isBlank())
        .map(this::parseSemiStructuredValue)
        .orElse(null);
  }

  private JsonNode parseSemiStructuredValue(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    try {
      return objectMapper.readTreeUnchecked(value);
    } catch (UncheckedIOException exception) {
      return TextNode.valueOf(value);
    }
  }

  private TaskError toError(PollableTask pollableTask) {
    JsonNode payload = parseSemiStructuredValue(pollableTask.getErrorMessage());
    if (payload == null
        && (pollableTask.getErrorStack() == null || pollableTask.getErrorStack().isBlank())) {
      return null;
    }

    String stackTraceHeadline = firstLine(pollableTask.getErrorStack());
    String exceptionType = exceptionTypeFromHeadline(stackTraceHeadline);
    String exceptionMessage = exceptionMessageFromHeadline(stackTraceHeadline);

    return new TaskError(
        jsonBoolean(payload, "expected"),
        jsonText(payload, "type"),
        jsonText(payload, "message"),
        exceptionType,
        exceptionMessage,
        stackTraceHeadline,
        pollableTask.getErrorStack(),
        payload);
  }

  private TaskRepositoryRef resolveRepository(
      PollableTask pollableTask, StoredTaskData storedTaskData, boolean includeParentFallback) {
    TaskRepositoryRef current =
        firstNonNull(
            resolveRepositoryFromJson(storedTaskData.input(), "input"),
            resolveRepositoryFromJson(storedTaskData.output(), "output"));
    if (current != null) {
      return current;
    }

    if (includeParentFallback && pollableTask.getParentTask() != null) {
      TaskRepositoryRef parentRepository =
          resolveRepository(
              pollableTask.getParentTask(),
              getStoredTaskData(pollableTask.getParentTask().getId()),
              true);
      if (parentRepository != null) {
        return new TaskRepositoryRef(
            parentRepository.id(),
            parentRepository.name(),
            "parent." + parentRepository.resolvedFrom());
      }
    }

    return null;
  }

  private TaskRepositoryRef resolveRepositoryFromJson(JsonNode jsonNode, String source) {
    if (jsonNode == null || jsonNode.isNull()) {
      return null;
    }

    Long repositoryId = null;
    String repositoryName = null;

    JsonNode repositoryNode = findField(jsonNode, "repository");
    if (repositoryNode != null) {
      if (repositoryNode.isObject()) {
        repositoryId = longValue(repositoryNode.get("id"));
        repositoryName = textValue(repositoryNode.get("name"));
      } else if (repositoryNode.isTextual()) {
        repositoryName = repositoryNode.asText();
      }
    }

    if (repositoryId == null) {
      repositoryId = longValue(findField(jsonNode, "repositoryId"));
    }

    if (repositoryName == null) {
      repositoryName = textValue(findField(jsonNode, "repositoryName"));
    }

    if (repositoryId == null && repositoryName == null) {
      return null;
    }

    Repository repository = null;
    if (repositoryId != null) {
      repository = repositoryRepository.findNoGraphById(repositoryId).orElse(null);
    }
    if (repository == null && repositoryName != null) {
      repository = repositoryRepository.findByName(repositoryName);
    }

    return new TaskRepositoryRef(
        repository != null ? repository.getId() : repositoryId,
        repository != null ? repository.getName() : repositoryName,
        source);
  }

  private JsonNode findField(JsonNode jsonNode, String fieldName) {
    if (jsonNode == null || jsonNode.isNull()) {
      return null;
    }

    if (jsonNode.isObject()) {
      JsonNode direct = jsonNode.get(fieldName);
      if (direct != null) {
        return direct;
      }

      var fields = jsonNode.fields();
      while (fields.hasNext()) {
        JsonNode nested = findField(fields.next().getValue(), fieldName);
        if (nested != null) {
          return nested;
        }
      }
    } else if (jsonNode.isArray()) {
      for (JsonNode child : jsonNode) {
        JsonNode nested = findField(child, fieldName);
        if (nested != null) {
          return nested;
        }
      }
    }

    return null;
  }

  private String operationName(String taskName) {
    if (taskName == null || taskName.isBlank()) {
      return null;
    }

    var matcher = SIMPLE_CLASS_NAME.matcher(taskName);
    if (matcher.matches()) {
      return matcher.group(1);
    }

    return taskName;
  }

  private String firstLine(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    int newlineIndex = value.indexOf('\n');
    return newlineIndex >= 0 ? value.substring(0, newlineIndex).trim() : value.trim();
  }

  private String exceptionTypeFromHeadline(String headline) {
    if (headline == null || headline.isBlank()) {
      return null;
    }

    int colonIndex = headline.indexOf(':');
    return colonIndex >= 0 ? headline.substring(0, colonIndex).trim() : headline.trim();
  }

  private String exceptionMessageFromHeadline(String headline) {
    if (headline == null || headline.isBlank()) {
      return null;
    }

    int colonIndex = headline.indexOf(':');
    if (colonIndex < 0 || colonIndex + 1 >= headline.length()) {
      return null;
    }

    return headline.substring(colonIndex + 1).trim();
  }

  private String jsonText(JsonNode jsonNode, String fieldName) {
    JsonNode value = jsonNode == null ? null : jsonNode.get(fieldName);
    return textValue(value);
  }

  private String textValue(JsonNode jsonNode) {
    if (jsonNode == null || jsonNode.isNull()) {
      return null;
    }

    return jsonNode.isTextual() ? jsonNode.asText() : jsonNode.toString();
  }

  private Boolean jsonBoolean(JsonNode jsonNode, String fieldName) {
    JsonNode value = jsonNode == null ? null : jsonNode.get(fieldName);
    if (value == null || value.isNull()) {
      return null;
    }

    return value.isBoolean() ? value.asBoolean() : Boolean.valueOf(value.asText());
  }

  private Long longValue(JsonNode jsonNode) {
    if (jsonNode == null || jsonNode.isNull()) {
      return null;
    }

    if (jsonNode.isNumber()) {
      return jsonNode.asLong();
    }

    if (jsonNode.isTextual()) {
      try {
        return Long.valueOf(jsonNode.asText());
      } catch (NumberFormatException exception) {
        return null;
      }
    }

    return null;
  }

  private TaskLinks linksFor(Long pollableTaskId) {
    String basePath = "/api/pollableTasks/" + pollableTaskId;
    return new TaskLinks(
        basePath, basePath + "/inspection", basePath + "/input", basePath + "/output");
  }

  private <T> T firstNonNull(T first, T second) {
    return first != null ? first : second;
  }

  private record StoredTaskData(JsonNode input, JsonNode output) {}

  public enum TaskStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
  }

  public record TaskInspection(
      Long id,
      TaskStatus status,
      String operation,
      String taskType,
      JsonNode message,
      TaskRepositoryRef repository,
      ZonedDateTime createdDate,
      ZonedDateTime finishedDate,
      Long parentTaskId,
      Integer expectedSubTaskCount,
      Integer subTaskCount,
      List<Long> subTaskIds,
      TaskError error,
      JsonNode input,
      JsonNode output,
      List<TaskFailure> failures,
      TaskLinks links) {}

  public record TaskFailure(
      Long id,
      String operation,
      String taskType,
      TaskStatus status,
      TaskRepositoryRef repository,
      TaskError error,
      JsonNode input,
      JsonNode output,
      ZonedDateTime createdDate,
      ZonedDateTime finishedDate,
      TaskLinks links) {}

  public record TaskRepositoryRef(Long id, String name, String resolvedFrom) {}

  public record TaskError(
      Boolean expected,
      String reportedType,
      String reportedMessage,
      String exceptionType,
      String exceptionMessage,
      String stackTraceHeadline,
      String stackTrace,
      JsonNode payload) {}

  public record TaskLinks(String task, String inspection, String input, String output) {}
}
