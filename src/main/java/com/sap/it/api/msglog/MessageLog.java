package com.sap.it.api.msglog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lokaler Nachbau von com.sap.it.api.msglog.MessageLog.
 *
 * Alles, was ein Script hier hineinschreibt, wird aufgezeichnet und kann
 * nach dem Lauf ausgewertet (UI-Tab "Attachments" / Dateien im Output-Ordner)
 * werden.
 */
public class MessageLog {

    public static class Attachment {
        public final String name;
        public final String mimeType;
        public final String content;

        public Attachment(String name, String mimeType, String content) {
            this.name = name;
            this.mimeType = mimeType;
            this.content = content;
        }
    }

    private final List<Attachment> attachments = new ArrayList<Attachment>();
    private final Map<String, String> customHeaderProperties = new LinkedHashMap<String, String>();
    private final Map<String, Object> stringProperties = new LinkedHashMap<String, Object>();

    public void addAttachmentAsString(String name, String value, String mimeType) {
        attachments.add(new Attachment(name, mimeType, value));
    }

    public void addAttachmentAsPlainText(String name, String value) {
        attachments.add(new Attachment(name, "text/plain", value));
    }

    public void addAttachmentAsByteArray(String name, byte[] value, String mimeType) {
        attachments.add(new Attachment(name, mimeType, value == null ? "" : new String(value)));
    }

    public void addCustomHeaderProperty(String name, String value) {
        customHeaderProperties.put(name, value);
    }

    public void setStringProperty(String name, String value) {
        stringProperties.put(name, value);
    }

    public void setIntegerProperty(String name, Integer value) {
        stringProperties.put(name, value);
    }

    public void setBooleanProperty(String name, Boolean value) {
        stringProperties.put(name, value);
    }

    public void setDoubleProperty(String name, Double value) {
        stringProperties.put(name, value);
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public Map<String, String> getCustomHeaderProperties() {
        return customHeaderProperties;
    }

    public Map<String, Object> getStringProperties() {
        return stringProperties;
    }
}
