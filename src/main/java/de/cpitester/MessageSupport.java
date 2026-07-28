package de.cpitester;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gemeinsame Implementierung hinter beiden CPI-Message-APIs:
 *
 *   com.sap.gateway.ip.core.customdev.util.Message   (Script Version 1.x)
 *   com.sap.it.script.v2.api.Message                 (Script Version 2.x)
 *
 * Beide Klassen sind reine Hüllen um diese Basis, damit sich der Runner nicht
 * um die Unterschiede kümmern muss und beide Generationen dasselbe Verhalten
 * zeigen.
 */
public abstract class MessageSupport {

    private Object body;
    private Charset charset = StandardCharsets.UTF_8;

    private Map<String, Object> headers = new LinkedHashMap<String, Object>();
    private Map<String, Object> properties = new LinkedHashMap<String, Object>();
    private Map<String, Object> attachments = new LinkedHashMap<String, Object>();

    // ------------------------------------------------------------------ body

    public Object getBody() {
        return body;
    }

    @SuppressWarnings("unchecked")
    public <T> T getBody(Class<T> type) {
        if (type == null) {
            return (T) body;
        }
        if (body == null) {
            return null;
        }
        if (type.isInstance(body)) {
            return (T) body;
        }
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
            return (T) asString();
        }
        if (type == byte[].class) {
            return (T) asBytes();
        }
        if (InputStream.class.isAssignableFrom(type)) {
            return (T) new ByteArrayInputStream(asBytes());
        }
        if (Reader.class.isAssignableFrom(type)) {
            return (T) new InputStreamReader(new ByteArrayInputStream(asBytes()), charset);
        }
        throw new IllegalArgumentException(
                "Konvertierung des Message-Body von " + body.getClass().getName()
                        + " nach " + type.getName() + " wird vom lokalen Tester nicht unterstuetzt.");
    }

    public void setBody(Object body) {
        this.body = body;
    }

    private String asString() {
        if (body instanceof byte[]) {
            return new String((byte[]) body, charset);
        }
        return String.valueOf(body);
    }

    private byte[] asBytes() {
        if (body instanceof byte[]) {
            return (byte[]) body;
        }
        return asString().getBytes(charset);
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset charset) {
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
    }

    // --------------------------------------------------------------- headers

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, Object> headers) {
        this.headers = headers == null ? new LinkedHashMap<String, Object>() : headers;
    }

    public Object getHeader(String name) {
        return headers.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getHeader(String name, Class<T> type) {
        return (T) convert(headers.get(name), type);
    }

    public void setHeader(String name, Object value) {
        headers.put(name, value);
    }

    public void clearHeaders() {
        headers.clear();
    }

    // ------------------------------------------------------------ properties

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties == null ? new LinkedHashMap<String, Object>() : properties;
    }

    public Object getProperty(String name) {
        return properties.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T getProperty(String name, Class<T> type) {
        return (T) convert(properties.get(name), type);
    }

    public void setProperty(String name, Object value) {
        properties.put(name, value);
    }

    public void clearProperties() {
        properties.clear();
    }

    private Object convert(Object value, Class<?> type) {
        if (value == null || type == null || type.isInstance(value)) {
            return value;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        return value;
    }

    // ----------------------------------------------------------- attachments

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, Object> attachments) {
        this.attachments = attachments == null ? new LinkedHashMap<String, Object>() : attachments;
    }

    public Object getAttachmentObject(String name) {
        return attachments.get(name);
    }

    public void setAttachmentObject(String name, Object value) {
        attachments.put(name, value);
    }

    public void clearAttachments() {
        attachments.clear();
    }

    @Override
    public String toString() {
        return getClass().getName() + "[headers=" + headers.size()
                + ", properties=" + properties.size()
                + ", attachments=" + attachments.size()
                + ", body=" + (body == null ? "null" : body.getClass().getSimpleName()) + "]";
    }
}
