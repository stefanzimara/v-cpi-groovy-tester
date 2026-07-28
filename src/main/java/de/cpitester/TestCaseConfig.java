package de.cpitester;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Laedt eine Testfall-Konfiguration (config.json) und baut daraus eine {@link RunConfig}.
 *
 * Aufbau der Datei:
 * {
 *   "script":     "scripts/MeinScript.groovy",
 *   "body":       "testdata/input.json",
 *   "entryMethod":"processData",
 *   "messageLogEnabled": true,
 *   "headers":    { "SapAuthenticatedUserName": "S00001" },
 *   "properties": { "debugLoggingEnabled": "true", "processDebugLog": "" },
 *   "credentials":{ "MeinAlias": { "user": "u", "password": "p" } }
 * }
 *
 * Pfadangaben sind relativ zum Speicherort der config.json.
 */
public final class TestCaseConfig {

    public Path scriptFile;
    public Path bodyFile;
    public final RunConfig runConfig = new RunConfig();

    private TestCaseConfig() {
    }

    public static TestCaseConfig empty() {
        return new TestCaseConfig();
    }

    @SuppressWarnings("unchecked")
    public static TestCaseConfig load(Path configPath) throws IOException {
        TestCaseConfig config = new TestCaseConfig();
        Path base = configPath.toAbsolutePath().getParent();
        Map<String, Object> raw = Json.parseObject(Files.readString(configPath, StandardCharsets.UTF_8));

        String script = Json.stringAt(raw, "script", null);
        if (script != null) {
            config.scriptFile = base.resolve(script).normalize();
        }
        String body = Json.stringAt(raw, "body", null);
        if (body != null) {
            config.bodyFile = base.resolve(body).normalize();
        }

        config.runConfig.entryMethod = Json.stringAt(raw, "entryMethod", "processData");
        config.runConfig.messageLogEnabled = Json.boolAt(raw, "messageLogEnabled", true);
        config.runConfig.headers.putAll(Json.mapAt(raw, "headers"));
        config.runConfig.properties.putAll(Json.mapAt(raw, "properties"));

        Map<String, Object> credentials = Json.mapAt(raw, "credentials");
        for (Map.Entry<String, Object> entry : credentials.entrySet()) {
            Map<String, String> credential = new LinkedHashMap<String, String>();
            if (entry.getValue() instanceof Map) {
                Map<String, Object> value = (Map<String, Object>) entry.getValue();
                credential.put("user", Json.stringAt(value, "user", ""));
                credential.put("password", Json.stringAt(value, "password", ""));
            }
            config.runConfig.credentials.put(entry.getKey(), credential);
        }
        return config;
    }
}
