package com.box.l10n.mojito.rest.pollableTask;

import com.box.l10n.mojito.entity.PollableTask;
import com.box.l10n.mojito.service.pollableTask.PollableTaskBlobStorage;
import com.box.l10n.mojito.service.pollableTask.PollableTaskInspectionService;
import com.box.l10n.mojito.service.pollableTask.PollableTaskService;
import com.box.l10n.mojito.service.tm.TMXliffRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * WS to get access to {@link PollableTask}s
 *
 * @author Jean
 */
@RestController
public class PollableTaskWS {

  @Autowired PollableTaskService pollableTaskService;

  @Autowired TMXliffRepository tmXliffRepository;

  @Autowired PollableTaskBlobStorage pollableTaskBlobStorage;

  @Autowired PollableTaskInspectionService pollableTaskInspectionService;

  /**
   * Gets a {@link PollableTask} by id.
   *
   * @param pollableTaskId
   * @return
   */
  @RequestMapping(method = RequestMethod.GET, value = "/api/pollableTasks/{pollableTaskId}")
  public PollableTask getPollableTaskById(@PathVariable Long pollableTaskId) {
    return pollableTaskService.getPollableTask(pollableTaskId);
  }

  @RequestMapping(method = RequestMethod.GET, value = "/api/pollableTasks/{pollableTaskId}/output")
  public String getPollableTaskOutput(@PathVariable Long pollableTaskId) {
    String outputJson = pollableTaskBlobStorage.getOutputJson(pollableTaskId);
    return outputJson;
  }

  @RequestMapping(method = RequestMethod.GET, value = "/api/pollableTasks/{pollableTaskId}/input")
  public String getPollableTaskInput(@PathVariable Long pollableTaskId) {
    String inputJson = pollableTaskBlobStorage.getInputJson(pollableTaskId);
    return inputJson;
  }

  @RequestMapping(
      method = RequestMethod.GET,
      value = "/api/pollableTasks/{pollableTaskId}/inspection")
  public PollableTaskInspectionService.TaskInspection getPollableTaskInspection(
      @PathVariable Long pollableTaskId) {
    try {
      return pollableTaskInspectionService.inspectTask(pollableTaskId);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
    }
  }
}
