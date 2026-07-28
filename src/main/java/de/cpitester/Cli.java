package de.cpitester;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Kommandozeilen-Modus: Script + Eingabedatei rein, Dateien raus. */
public final class Cli {

    private Cli() {
    }

    public static int run(String[] args) throws IOException {
        Path configPath = null;
        Path scriptPath = null;
        Path bodyPath = null;
        Path outFile = null;
        Path outDir = null;
        Charset charset = StandardCharsets.UTF_8;
        Boolean messageLogEnabled = null;
        String entryMethod = null;
        boolean quiet = false;
        Map<String, Object> propertyOverrides = new LinkedHashMap<String, Object>();
        Map<String, Object> headerOverrides = new LinkedHashMap<String, Object>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--config":
                case "-c":
                    configPath = Paths.get(requireValue(args, ++i, arg));
                    break;
                case "--script":
                case "-s":
                    scriptPath = Paths.get(requireValue(args, ++i, arg));
                    break;
                case "--body":
                case "-b":
                    bodyPath = Paths.get(requireValue(args, ++i, arg));
                    break;
                case "--out":
                case "-o":
                    outFile = Paths.get(requireValue(args, ++i, arg));
                    break;
                case "--outdir":
                case "-d":
                    outDir = Paths.get(requireValue(args, ++i, arg));
                    break;
                case "--property":
                case "-p":
                    putKeyValue(propertyOverrides, requireValue(args, ++i, arg));
                    break;
                case "--header":
                case "-H":
                    putKeyValue(headerOverrides, requireValue(args, ++i, arg));
                    break;
                case "--entry":
                    entryMethod = requireValue(args, ++i, arg);
                    break;
                case "--encoding":
                    charset = Charset.forName(requireValue(args, ++i, arg));
                    break;
                case "--no-messagelog":
                    messageLogEnabled = Boolean.FALSE;
                    break;
                case "--quiet":
                case "-q":
                    quiet = true;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return 0;
                default:
                    System.err.println("Unbekannte Option: " + arg);
                    printUsage();
                    return 2;
            }
        }

        TestCaseConfig testCase = configPath != null ? TestCaseConfig.load(configPath) : TestCaseConfig.empty();
        if (scriptPath != null) {
            testCase.scriptFile = scriptPath;
        }
        if (bodyPath != null) {
            testCase.bodyFile = bodyPath;
        }
        if (entryMethod != null) {
            testCase.runConfig.entryMethod = entryMethod;
        }
        if (messageLogEnabled != null) {
            testCase.runConfig.messageLogEnabled = messageLogEnabled;
        }
        testCase.runConfig.properties.putAll(propertyOverrides);
        testCase.runConfig.headers.putAll(headerOverrides);
        testCase.runConfig.charset = charset;

        if (testCase.scriptFile == null) {
            System.err.println("Es wurde kein Script angegeben (--script oder \"script\" in der config.json).");
            printUsage();
            return 2;
        }
        if (!Files.isRegularFile(testCase.scriptFile)) {
            System.err.println("Script nicht gefunden: " + testCase.scriptFile.toAbsolutePath());
            return 2;
        }

        RunConfig runConfig = testCase.runConfig;
        runConfig.scriptSource = Files.readString(testCase.scriptFile, charset);
        runConfig.scriptName = testCase.scriptFile.getFileName().toString();
        if (testCase.bodyFile != null) {
            if (!Files.isRegularFile(testCase.bodyFile)) {
                System.err.println("Eingabedatei nicht gefunden: " + testCase.bodyFile.toAbsolutePath());
                return 2;
            }
            runConfig.body = Files.readString(testCase.bodyFile, charset);
        }

        RunResult result = new ScriptRunner().run(runConfig);

        if (outDir == null && outFile == null) {
            outDir = Paths.get("out");
        }
        List<Path> written = new ArrayList<Path>();

        if (outFile != null) {
            writeFile(outFile, result.body, charset);
            written.add(outFile);
        }
        if (outDir != null) {
            Files.createDirectories(outDir);
            Path bodyOut = outDir.resolve("output" + guessExtension(result.body));
            writeFile(bodyOut, result.body, charset);
            written.add(bodyOut);

            written.add(writeFile(outDir.resolve("properties.json"), Json.writePretty(result.properties), charset));
            written.add(writeFile(outDir.resolve("headers.json"), Json.writePretty(result.headers), charset));
            written.add(writeFile(outDir.resolve("console.log"), result.console, charset));
            if (!result.success) {
                written.add(writeFile(outDir.resolve("error.log"),
                        result.error + System.lineSeparator() + System.lineSeparator() + result.stackTrace, charset));
            }
            // Properties, die typischerweise das Debug-Log tragen, zusaetzlich als eigene
            // Textdatei ablegen - deutlich angenehmer zu lesen als JSON-escaped.
            for (Map.Entry<String, Object> entry : result.properties.entrySet()) {
                String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                if (value.length() > 200 && value.contains("\n")) {
                    written.add(writeFile(outDir.resolve("property_" + safeFileName(entry.getKey()) + ".log"),
                            value, charset));
                }
            }
            int index = 1;
            for (RunResult.AttachmentView attachment : result.attachments) {
                Path target = outDir.resolve("attachments")
                        .resolve(String.format("%02d_%s.txt", index++, safeFileName(attachment.name)));
                Files.createDirectories(target.getParent());
                writeFile(target, attachment.content, charset);
                written.add(target);
            }
        }

        if (!quiet) {
            System.out.println(result.success
                    ? "Lauf erfolgreich (" + result.durationMs + " ms)"
                    : "Lauf mit Fehler beendet (" + result.durationMs + " ms): " + result.error);
            System.out.println("Laufzeit: Groovy " + result.groovyVersion
                    + (result.apiVersion.isEmpty() ? "" : " | " + result.apiVersion));
            System.out.println("Body-Laenge: " + result.body.length() + " Zeichen");
            System.out.println("Geschriebene Dateien:");
            for (Path path : written) {
                System.out.println("  " + path.toAbsolutePath().normalize());
            }
            if (!result.console.isEmpty()) {
                System.out.println("--- Konsole des Scripts ---");
                System.out.print(result.console);
                if (!result.console.endsWith(System.lineSeparator())) {
                    System.out.println();
                }
            }
            if (!result.success) {
                System.out.println("--- Stacktrace ---");
                System.out.println(result.stackTrace);
            }
        }
        return result.success ? 0 : 1;
    }

    private static Path writeFile(Path path, String content, Charset charset) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content == null ? "" : content, charset);
        return path;
    }

    private static void putKeyValue(Map<String, Object> target, String assignment) {
        int index = assignment.indexOf('=');
        if (index < 0) {
            target.put(assignment, "");
        } else {
            target.put(assignment.substring(0, index), assignment.substring(index + 1));
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Zu Option " + option + " fehlt der Wert.");
        }
        return args[index];
    }

    private static String guessExtension(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("<")) {
            return ".xml";
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return ".json";
        }
        return ".txt";
    }

    private static String safeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "unnamed";
        }
        return name.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public static void printUsage() {
        System.out.println(String.join(System.lineSeparator(),
                "CPI Groovy Tester",
                "",
                "  run  Script einmal ausfuehren und Ergebnis als Datei(en) ablegen",
                "  ui   lokale Weboberflaeche starten",
                "",
                "Beispiele:",
                "  ./run.sh run --script scripts/MeinScript.groovy --body testdata/input.json \\",
                "                --config testdata/config.json --outdir out",
                "  ./run.sh run --config testdata/config.json --out out/ergebnis.xml",
                "  ./run.sh ui --port 8899",
                "",
                "Optionen fuer 'run':",
                "  -s, --script <datei>    Groovy-Script (Pflicht, falls nicht in der config.json)",
                "  -b, --body <datei>      Eingabedatei fuer den Message-Body (XML/JSON/Text)",
                "  -c, --config <datei>    config.json mit headers/properties/credentials",
                "  -o, --out <datei>       Ausgabe-Body zusaetzlich unter diesem Pfad ablegen",
                "  -d, --outdir <ordner>   Ausgabeordner (Default: out)",
                "  -p, --property k=v      Einzelne Property setzen/ueberschreiben (mehrfach moeglich)",
                "  -H, --header k=v        Einzelnen Header setzen/ueberschreiben (mehrfach moeglich)",
                "      --entry <methode>   Einstiegsmethode (Default: processData)",
                "      --encoding <name>   Zeichensatz fuer Ein-/Ausgabe (Default: UTF-8)",
                "      --no-messagelog     messageLogFactory.getMessageLog(msg) liefert null",
                "  -q, --quiet             Keine Zusammenfassung auf der Konsole",
                "",
                "Exit-Code: 0 = ok, 1 = Script-Fehler, 2 = Aufruffehler"));
    }
}
