package com.amazonaws.services.glue.catalog;

import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackMessage;

public class SlackNotify {

    private String webhookUrl;
    private String channel;

    public SlackNotify(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.channel = "";
    }

    public void sendNotification(String message) {
        SlackApi api = new SlackApi(webhookUrl);
        api.call(new SlackMessage(channel, null, message));
    }
}
