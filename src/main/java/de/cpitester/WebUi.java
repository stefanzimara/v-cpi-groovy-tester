package de.cpitester;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Kleine lokale Weboberflaeche: Script, Input, Header/Properties bearbeiten,
 * ausfuehren, Output/Konsole/Attachments ansehen.
 *
 * Sicherheitsmodell (siehe auch SECURITY.md):
 *  - Bindet standardmaessig nur auf 127.0.0.1. Netzwerkfreigabe via --bind
 *    erzwingt ein Zugriffstoken.
 *  - Dateizugriffe (laden/speichern) sind auf das Arbeitsverzeichnis
 *    beschraenkt (siehe resolveSafe).
 *  - Zustandsveraendernde Endpunkte (/api/run, /api/save) verlangen zusaetzlich
 *    einen Custom-Header (CSRF_HEADER), unabhaengig vom Token - das schuetzt
 *    auch im Default-Loopback-Modus davor, dass eine andere im Browser offene
 *    Webseite per Cross-Site-Request Code ausloesen kann.
 *  - /api/run fuehrt das eingereichte Script mit vollen Rechten des laufenden
 *    Benutzers aus. Das ist Kernfunktion, kein Fehler - dieser Tester ist ein
 *    lokales Entwicklerwerkzeug, kein sicherer Mehrbenutzer-Dienst, und darf
 *    nicht unauthentifiziert ins offene Internet gestellt werden.
 */
public final class WebUi {

    private static Path root = Paths.get("").toAbsolutePath().normalize();

    /** Wenn gesetzt, muss jede Anfrage das Token mitbringen. */
    private static String token = null;

    /**
     * Custom-Header, den zustandsveraendernde Endpunkte (Ausfuehren, Speichern)
     * verlangen - unabhaengig vom Zugriffstoken, auch im Default-Loopback-Modus.
     *
     * Hintergrund: /api/run fuehrt beliebiges Groovy aus. Ohne diese Pruefung
     * koennte JEDE Webseite, die im selben Browser in einem anderen Tab offen ist,
     * per fetch/POST heimlich Code auf diesem Rechner ausloesen, solange der
     * Tester laeuft ("localhost CSRF" - eine bekannte Schwachstellenklasse bei
     * Dev-Tools mit lokalem HTTP-Server). Ein simples HTML-<form> kann keine
     * eigenen Header setzen, und ein cross-origin fetch/XHR mit einem eigenen
     * Header loest einen CORS-Preflight aus, den dieser Server nicht beantwortet
     * - der Browser blockiert die eigentliche Anfrage dann von sich aus. Siehe
     * OWASP CSRF Cheat Sheet, Abschnitt "Custom Request Headers".
     */
    private static final String CSRF_HEADER = "X-Tester-Csrf";
    private static final String CSRF_VALUE = "1";

    /** Schutz gegen Speicher-Erschoepfung durch ueberlange Request-Bodies. */
    private static final int MAX_BODY_BYTES = 25 * 1024 * 1024;

    private WebUi() {
    }

    public static void start(String[] args) throws IOException {
        int port = 8899;
        boolean open = true;
        String bind = "127.0.0.1";
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--root".equals(args[i]) && i + 1 < args.length) {
                root = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else if ("--bind".equals(args[i]) && i + 1 < args.length) {
                bind = args[++i];
            } else if ("--token".equals(args[i]) && i + 1 < args.length) {
                token = args[++i];
            } else if ("--no-token".equals(args[i])) {
                token = "";
            } else if ("--no-open".equals(args[i])) {
                open = false;
            }
        }
        if ("all".equalsIgnoreCase(bind) || "lan".equalsIgnoreCase(bind)) {
            bind = "0.0.0.0";
        }

        InetAddress bindAddress = InetAddress.getByName(bind);
        boolean exposed = !bindAddress.isLoopbackAddress();

        // Die UI fuehrt beliebiges Groovy aus und schreibt Dateien. Sobald sie
        // ueber das Netz erreichbar ist, gilt das fuer jeden, der sie erreicht -
        // deshalb dann Pflicht-Token.
        boolean tokenExplicitlyDisabled = "".equals(token);
        if (tokenExplicitlyDisabled) {
            token = null;
        } else if (exposed && token == null) {
            byte[] random = new byte[12];
            new java.security.SecureRandom().nextBytes(random);
            token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/", guarded(new StaticHandler(), false));
        server.createContext("/api/run", guarded(new RunHandler(), true));
        server.createContext("/api/check", guarded(new CheckHandler(), true));
        server.createContext("/api/list", guarded(new ListHandler(), false));
        server.createContext("/api/load", guarded(new LoadHandler(), false));
        server.createContext("/api/save", guarded(new SaveHandler(), true));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();

        String query = token == null ? "" : "?t=" + token;
        String localUrl = "http://localhost:" + port + "/" + query;
        System.out.println("CPI Groovy Tester UI laeuft auf " + localUrl);
        if (exposed) {
            System.out.println();
            System.out.println("ACHTUNG: gebunden an " + bind + " - im Netzwerk erreichbar.");
            System.out.println("Wer diese Adresse samt Token erreicht, kann beliebiges Groovy");
            System.out.println("unter deinem Benutzer ausfuehren und Dateien im Projektordner");
            System.out.println("lesen und schreiben. Nur in vertrauenswuerdigen Netzen nutzen.");
            if (tokenExplicitlyDisabled) {
                System.out.println();
                System.out.println("ACHTUNG: --no-token UND Netzwerkbindung gleichzeitig gesetzt.");
                System.out.println("Jeder, der diese Adresse erreicht, kann ohne jede Huerde Code");
                System.out.println("auf diesem Rechner ausfuehren. Nur mit gutem Grund verwenden.");
            }
            System.out.println();
            for (String address : localAddresses()) {
                System.out.println("  http://" + address + ":" + port + "/" + query);
            }
            try {
                String mdns = java.net.InetAddress.getLocalHost().getHostName();
                if (mdns != null && !mdns.isEmpty()) {
                    System.out.println("  (Hostname laut System: " + mdns + ")");
                }
            } catch (Exception ignored) {
                // Hostname nicht ermittelbar -> IP-Adressen oben genuegen
            }
            System.out.println();
        }
        if (token != null) {
            System.out.println("Zugriffstoken: " + token + "   (muss als ?t=... in der URL stehen)");
        }
        System.out.println("Arbeitsverzeichnis: " + root);
        System.out.println("Beenden mit Ctrl+C");
        if (open) {
            openBrowser(localUrl);
        }
    }

    /** Alle IPv4-Adressen der aktiven Schnittstellen, fuer die Ausgabe der erreichbaren URLs. */
    private static List<String> localAddresses() {
        List<String> addresses = new ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                java.util.Enumeration<InetAddress> candidates = networkInterface.getInetAddresses();
                while (candidates.hasMoreElements()) {
                    InetAddress address = candidates.nextElement();
                    if (address instanceof java.net.Inet4Address) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // ohne Interface-Liste laeuft der Server trotzdem
        }
        return addresses;
    }

    /**
     * Prueft Zugriffstoken (falls aktiv) und - fuer zustandsveraendernde
     * Endpunkte - den CSRF-Header. Beides greift unabhaengig voneinander:
     * das Token schuetzt vor Fremdzugriff aus dem Netz, der CSRF-Header vor
     * Ausloesung durch eine andere im selben Browser offene Webseite.
     */
    private static HttpHandler guarded(final HttpHandler delegate, final boolean requireCsrfHeader) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (token != null && !tokenMatches(exchange)) {
                    send(exchange, 403, "text/plain; charset=utf-8",
                            "Zugriff verweigert: Token fehlt oder ist falsch (?t=...)"
                                    .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (requireCsrfHeader && !CSRF_VALUE.equals(exchange.getRequestHeaders().getFirst(CSRF_HEADER))) {
                    send(exchange, 403, "text/plain; charset=utf-8",
                            ("Zugriff verweigert: Header " + CSRF_HEADER + ": " + CSRF_VALUE + " fehlt. "
                                    + "Dieser Endpunkt fuehrt Code aus bzw. schreibt Dateien und verlangt "
                                    + "deshalb einen eigenen Request-Header, den ein fremdes <form> oder ein "
                                    + "cross-origin fetch/XHR nicht setzen kann.")
                                    .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                delegate.handle(exchange);
            }
        };
    }

    private static boolean tokenMatches(HttpExchange exchange) {
        String provided = exchange.getRequestHeaders().getFirst("X-Tester-Token");
        if (provided == null) {
            provided = queryParams(exchange.getRequestURI()).get("t");
        }
        if (provided == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ignored) {
            // Browser laesst sich nicht oeffnen -> URL steht auf der Konsole
        }
    }

    // ------------------------------------------------------------- Handler

    private static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                path = "/index.html";
            }
            try (InputStream in = WebUi.class.getResourceAsStream("/ui" + path)) {
                if (in == null) {
                    send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(exchange, 200, contentType(path), in.readAllBytes());
            }
        }
    }

    private static class RunHandler implements HttpHandler {
        @Override
        @SuppressWarnings("unchecked")
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "POST erwartet".getBytes(StandardCharsets.UTF_8));
                return;
            }
            try {
                Map<String, Object> request = Json.parseObject(readBody(exchange));

                RunConfig config = new RunConfig();
                config.scriptSource = Json.stringAt(request, "script", "");
                config.scriptName = Json.stringAt(request, "scriptName", "Script.groovy");
                config.body = Json.stringAt(request, "body", "");
                config.entryMethod = Json.stringAt(request, "entryMethod", "processData");
                config.messageLogEnabled = Json.boolAt(request, "messageLogEnabled", true);
                config.trace = Json.boolAt(request, "trace", false);
                config.traceMaxSteps = Json.intAt(request, "traceMaxSteps", 5000, 1, 20000);
                config.headers.putAll(Json.mapAt(request, "headers"));
                config.properties.putAll(Json.mapAt(request, "properties"));
                Map<String, Object> credentials = Json.mapAt(request, "credentials");
                for (Map.Entry<String, Object> entry : credentials.entrySet()) {
                    Map<String, String> credential = new LinkedHashMap<String, String>();
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> value = (Map<String, Object>) entry.getValue();
                        credential.put("user", Json.stringAt(value, "user", ""));
                        credential.put("password", Json.stringAt(value, "password", ""));
                    }
                    config.credentials.put(entry.getKey(), credential);
                }

                RunResult result = new ScriptRunner().run(config);

                List<Object> attachments = new ArrayList<Object>();
                for (RunResult.AttachmentView attachment : result.attachments) {
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("name", attachment.name);
                    view.put("mimeType", attachment.mimeType);
                    view.put("content", attachment.content);
                    attachments.add(view);
                }

                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("success", result.success);
                response.put("body", result.body);
                response.put("headers", stringify(result.headers));
                response.put("properties", stringify(result.properties));
                response.put("console", result.console);
                response.put("attachments", attachments);
                response.put("customHeaderProperties", result.customHeaderProperties);
                response.put("error", result.error);
                response.put("stackTrace", result.stackTrace);
                response.put("durationMs", result.durationMs);
                response.put("groovyVersion", result.groovyVersion);
                response.put("apiVersion", result.apiVersion);
                response.put("trace", traceView(result.trace));
                response.put("traceExecuted", result.traceExecuted);
                response.put("traceTruncated", result.traceTruncated);
                sendJson(exchange, 200, response);
            } catch (Exception e) {
                Map<String, Object> response = new LinkedHashMap<String, Object>();
                response.put("success", false);
                response.put("error", "Fehler im Tester selbst: " + e);
                response.put("stackTrace", "");
                response.put("body", "");
                response.put("console", "");
                sendJson(exchange, 200, response);
            }
        }
    }

    /**
     * Prueft das Script, ohne es auszufuehren, und liefert die Befunde fuer
     * die Live-Anzeige im Editor.
     *
     * Steht bewusst hinter demselben CSRF-Schutz wie /api/run: die Pruefung
     * kompiliert bis CANONICALIZATION, und dabei laufen AST-Transformationen -
     * also fremder Code. "Nur pruefen" ist hier nicht gleichbedeutend mit
     * "harmlos".
     */
    private static class CheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "POST erwartet".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            try {
                Map<String, Object> request = Json.parseObject(readBody(exchange));

                CodeInspector.Context context = new CodeInspector.Context();
                context.scriptName = Json.stringAt(request, "scriptName", "Script.groovy");
                context.entryMethod = Json.stringAt(request, "entryMethod", "processData");
                context.knownHeaders.addAll(Json.mapAt(request, "headers").keySet());
                context.knownProperties.addAll(Json.mapAt(request, "properties").keySet());

                List<CodeInspector.Finding> findings =
                        new CodeInspector().inspect(Json.stringAt(request, "script", ""), context);

                List<Object> views = new ArrayList<Object>();
                for (CodeInspector.Finding finding : findings) {
                    Map<String, Object> view = new LinkedHashMap<String, Object>();
                    view.put("rule", finding.rule);
                    view.put("severity", finding.severity);
                    view.put("line", finding.line);
                    view.put("column", finding.column);
                    view.put("endLine", finding.endLine);
                    view.put("endColumn", finding.endColumn);
                    view.put("message", finding.message);
                    view.put("params", finding.params);
                    views.add(view);
                }
                response.put("ok", true);
                response.put("findings", views);
            } catch (Exception e) {
                response.put("ok", false);
                response.put("error", "Pruefung fehlgeschlagen: " + e);
                response.put("findings", new ArrayList<Object>());
            }
            sendJson(exchange, 200, response);
        }
    }

    private static class ListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            String dir = query.getOrDefault("dir", ".");
            Path target = resolveSafe(dir);
            List<String> files = new ArrayList<String>();
            if (target != null && Files.isDirectory(target)) {
                try (Stream<Path> stream = Files.walk(target, 4)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> !p.toString().contains("/target/"))
                            .map(p -> root.relativize(p).toString())
                            .sorted(Comparator.naturalOrder())
                            .limit(500)
                            .forEach(files::add);
                }
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("root", root.toString());
            response.put("files", files);
            sendJson(exchange, 200, response);
        }
    }

    private static class LoadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, String> query = queryParams(exchange.getRequestURI());
            Path target = resolveSafe(query.get("path"));
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            if (target == null || !Files.isRegularFile(target)) {
                response.put("ok", false);
                response.put("error", "Datei nicht gefunden oder ausserhalb des Arbeitsverzeichnisses.");
            } else {
                response.put("ok", true);
                response.put("path", root.relativize(target).toString());
                response.put("content", Files.readString(target, StandardCharsets.UTF_8));
            }
            sendJson(exchange, 200, response);
        }
    }

    private static class SaveHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain; charset=utf-8", "POST erwartet".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            try {
                Map<String, Object> request = Json.parseObject(readBody(exchange));
                Path target = resolveSafe(Json.stringAt(request, "path", null));
                if (target == null) {
                    response.put("ok", false);
                    response.put("error", "Pfad liegt ausserhalb des Arbeitsverzeichnisses.");
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.writeString(target, Json.stringAt(request, "content", ""), StandardCharsets.UTF_8);
                    response.put("ok", true);
                    response.put("path", root.relativize(target).toString());
                }
            } catch (Exception e) {
                response.put("ok", false);
                response.put("error", "Fehler beim Speichern: " + e.getMessage());
            }
            sendJson(exchange, 200, response);
        }
    }

    // --------------------------------------------------------------- Helper

    /** Loest einen relativen Pfad auf und verhindert Ausbrueche aus dem Arbeitsverzeichnis. */
    private static Path resolveSafe(String relative) {
        if (relative == null || relative.trim().isEmpty()) {
            return null;
        }
        Path candidate = root.resolve(relative).normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    /**
     * Die Aufzeichnung wird von Hand in Maps uebersetzt statt einen
     * Objekt-Serialisierer zu bemuehen - so steht hier, was tatsaechlich ueber
     * die Leitung geht.
     */
    private static List<Object> traceView(List<TraceRecorder.Step> steps) {
        List<Object> result = new ArrayList<Object>();
        for (TraceRecorder.Step step : steps) {
            List<Object> changes = new ArrayList<Object>();
            for (TraceRecorder.Change change : step.changes) {
                Map<String, Object> view = new LinkedHashMap<String, Object>();
                view.put("kind", change.kind);
                view.put("name", change.name);
                view.put("before", change.before);
                view.put("after", change.after);
                changes.add(view);
            }
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("line", step.line);
            view.put("variables", step.variables);
            view.put("changes", changes);
            view.put("console", step.console);
            result.add(view);
        }
        return result;
    }

    private static Map<String, Object> stringify(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    /** Liest den Request-Body, bricht aber jenseits von {@link #MAX_BODY_BYTES} ab. */
    private static String readBody(HttpExchange exchange) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        InputStream in = exchange.getRequestBody();
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > MAX_BODY_BYTES) {
                throw new IOException("Request-Body ueberschreitet das Limit von " + MAX_BODY_BYTES + " Bytes");
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        String query = uri.getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                params.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                Json.write(payload).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] payload)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
