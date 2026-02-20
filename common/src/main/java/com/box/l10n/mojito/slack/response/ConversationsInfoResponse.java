package com.box.l10n.mojito.slack.response;

import com.box.l10n.mojito.slack.request.Channel;

public class ConversationsInfoResponse extends BaseResponse {

  Channel channel;

  public Channel getChannel() {
    return channel;
  }

  public void setChannel(Channel channel) {
    this.channel = channel;
  }
}
