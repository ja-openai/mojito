package com.box.l10n.mojito.slack.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.Test;

public class MessageTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void omitsUnfurlOptionsWhenUnset() {
    JsonNode json = objectMapper.valueToTree(new Message());

    assertThat(json.has("unfurl_links")).isFalse();
    assertThat(json.has("unfurl_media")).isFalse();
    assertThat(json.has("unfurlLinks")).isFalse();
    assertThat(json.has("unfurlMedia")).isFalse();
  }

  @Test
  public void serializesDisabledUnfurlOptions() {
    Message message = new Message();
    message.setUnfurlLinks(false);
    message.setUnfurlMedia(false);

    JsonNode json = objectMapper.valueToTree(message);

    assertThat(json.get("unfurl_links")).isEqualTo(BooleanNode.FALSE);
    assertThat(json.get("unfurl_media")).isEqualTo(BooleanNode.FALSE);
    assertThat(json.has("unfurlLinks")).isFalse();
    assertThat(json.has("unfurlMedia")).isFalse();
  }
}
