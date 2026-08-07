package com.sap.it.api.msglog.factory;

import com.sap.it.api.msglog.MessageLog;

import de.cpitester.MessageSupport;

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

    /**
     * Nimmt bewusst {@link MessageSupport} entgegen, nicht eine der beiden
     * konkreten Message-Klassen: Scripte beider CPI-Generationen rufen
     * getMessageLog(message) mit ihrer jeweils eigenen Message-Klasse auf, und
     * auf dem Tenant funktioniert beides. Hier stand frueher die Klasse der
     * Version 1.x, wodurch jedes Script der Version 2.x mit einer
     * MissingMethodException scheiterte.
     *
     * Der Parameter wird nicht ausgewertet - er existiert nur, damit die
     * Aufrufform der echten API entspricht.
     */
    public MessageLog getMessageLog(MessageSupport message) {
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
