package com.box.l10n.mojito.entity;

import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.junit.Test;

public class PollableTaskTest {

  @Test
  public void serializesMessageAndErrorMessageAsValidJson() throws Exception {
    PollableTask pollableTask = new PollableTask();
    pollableTask.setMessage("{\"a\": 1, \"b\": [1,2,3]}");
    pollableTask.setErrorMessage("This is a simple string that doesn't contain JSON");

    String serialized = writeAsJson(pollableTask);

    assertTrue(serialized.contains("\"message\":{\"a\": 1, \"b\": [1,2,3]}"));
    assertTrue(
        serialized.contains(
            "\"errorMessage\":\"This is a simple string that doesn't contain JSON\""));
    assertValidJson(serialized);
  }

  private String writeAsJson(PollableTask pollableTask) throws JsonProcessingException {
    return new ObjectMapper().writeValueAsString(pollableTask);
  }

  private void assertValidJson(String serialized) throws ParseException {
    new JSONParser().parse(serialized);
  }
}
