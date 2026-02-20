package com.box.l10n.mojito.slack.response;

import java.util.List;

public class ConversationsMembersResponse extends BaseResponse {

  List<String> members;
  ResponseMetadata response_metadata;

  public List<String> getMembers() {
    return members;
  }

  public void setMembers(List<String> members) {
    this.members = members;
  }

  public ResponseMetadata getResponse_metadata() {
    return response_metadata;
  }

  public void setResponse_metadata(ResponseMetadata response_metadata) {
    this.response_metadata = response_metadata;
  }

  public static class ResponseMetadata {
    String next_cursor;

    public String getNext_cursor() {
      return next_cursor;
    }

    public void setNext_cursor(String next_cursor) {
      this.next_cursor = next_cursor;
    }
  }
}
