package de.cpitester;

import java.util.LinkedHashMap;
import java.util.Map;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;

/**
 * Duenne JSON-Huelle um die ohnehin vorhandene Groovy-JSON-Implementierung,
 * damit der Tester ohne zusaetzliche JSON-Library auskommt.
 */
public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return new JsonSlurper().parseText(text);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object parsed = parse(text);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("Erwartet wurde ein JSON-Objekt, gefunden: "
                + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
    }

    public static String write(Object value) {
        return JsonOutput.toJson(value);
    }

    public static String writePretty(Object value) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(value));
    }

    /** Liest ein Unterobjekt als Map<String,Object>; fehlt es, kommt eine leere Map zurueck. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapAt(Map<String, Object> source, String key) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    public static String stringAt(Map<String, Object> source, String key, String defaultValue) {
        Object value = source == null ? null : source.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public static boolean boolAt(Map<String, Object> source, String key, boolean defaultValue) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
