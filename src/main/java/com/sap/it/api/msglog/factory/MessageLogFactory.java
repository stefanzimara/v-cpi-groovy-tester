package com.sap.it.api.msglog.factory;

import com.sap.gateway.ip.core.customdev.util.Message;
import com.sap.it.api.msglog.MessageLog;

/**
 * Lokaler Nachbau von com.sap.it.api.msglog.factory.MessageLogFactory.
 *
 * In CPI wird die Instanz unter dem Binding-Namen "messageLogFactory" bereitgestellt.
 * getMessageLog() liefert dort null, wenn das Log-Level nicht mindestens "Info" ist -
 * das laesst sich hier ueber {@link #setEnabled(boolean)} nachstellen.
 */
public class MessageLogFactory {

    private final MessageLog messageLog = new MessageLog();
    private boolean enabled = true;

    public MessageLog getMessageLog(Message message) {
        return enabled ? messageLog : null;
    }

    public MessageLog getMessageLog() {
        return enabled ? messageLog : null;
    }

    public MessageLog getRecordedMessageLog() {
        return messageLog;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
