package de.cpitester;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nimmt waehrend eines Laufs auf, was das Script Schritt fuer Schritt tut.
 *
 * Der {@link TraceTransformer} haengt vor jedes Statement einen Aufruf von
 * {@link #step(int, Map)}; dort landen Zeilennummer, die zu dem Zeitpunkt
 * sichtbaren lokalen Variablen, alles seither auf der Konsole Ausgegebene und
 * die Aenderungen am Message-Zustand. Damit laesst sich der Ablauf hinterher
 * vor- und zurueckspulen, statt ihn mit println-Aufrufen zu rekonstruieren.
 *
 * Aufgezeichnet werden nur Aenderungen, nicht der volle Zustand pro Schritt -
 * eine Schleife ueber tausend Datensaetze wuerde sonst ein Vielfaches der
 * Nutzdaten erzeugen.
 *
 * Die Aufzeichnung darf den Lauf unter keinen Umstaenden kippen: alles in
 * {@link #step(int, Map)} ist gegen Fehler abgeschirmt.
 */
public final class TraceRecorder {

    /** Laenge, ab der ein Wert in der Aufzeichnung gekuerzt wird. */
    private static final int MAX_TEXT = 400;

    /**
     * Der Lauf ist prozessweit serialisiert (siehe ScriptRunner.CONSOLE_LOCK),
     * deshalb genuegt eine statische Referenz - das instrumentierte Script
     * ruft {@link #step(int, Map)} ohne jeden Kontext auf.
     */
    private static volatile TraceRecorder active;

    private final MessageSupport message;
    private final ConsoleBuffer consoleBuffer;
    private final int maxSteps;

    private final List<Step> steps = new ArrayList<Step>();

    private Map<String, String> lastProperties;
    private Map<String, String> lastHeaders;
    private String lastBody;

    /** Gezaehlt werden auch Schritte jenseits des Limits - sonst waere unklar, wie viel fehlt. */
    private int executed = 0;

    private TraceRecorder(MessageSupport message, ConsoleBuffer consoleBuffer, int maxSteps) {
        this.message = message;
        this.consoleBuffer = consoleBuffer;
        this.maxSteps = maxSteps < 1 ? 1 : maxSteps;
        this.lastProperties = snapshot(message.getProperties());
        this.lastHeaders = snapshot(message.getHeaders());
        this.lastBody = text(message.getBody());
        consoleBuffer.drain();   // was vor dem Lauf anfiel, gehoert zu keinem Schritt
    }

    // ------------------------------------------------------------- Datenmodell

    /** Eine Aenderung am Message-Zustand zwischen zwei Schritten. */
    public static class Change {
        /** {@code property}, {@code header} oder {@code body}. */
        public String kind;
        public String name;
        /** {@code null} bedeutet: gab es vorher nicht. */
        public String before;
        /** {@code null} bedeutet: wurde entfernt. */
        public String after;
    }

    public static class Step {
        public int line;
        public Map<String, String> variables = new LinkedHashMap<String, String>();
        public List<Change> changes = new ArrayList<Change>();
        /** Was seit dem vorigen Schritt auf System.out/System.err ging. */
        public String console = "";
    }

    // ----------------------------------------------------------- Aufzeichnung

    static TraceRecorder start(MessageSupport message, ConsoleBuffer consoleBuffer, int maxSteps) {
        TraceRecorder recorder = new TraceRecorder(message, consoleBuffer, maxSteps);
        active = recorder;
        return recorder;
    }

    static void stop() {
        active = null;
    }

    /** Einstiegspunkt fuer das instrumentierte Script. Muss oeffentlich bleiben. */
    public static void step(int line, Map<String, Object> variables) {
        TraceRecorder recorder = active;
        if (recorder == null) {
            return;
        }
        try {
            recorder.record(line, variables);
        } catch (Throwable ignored) {
            // Eine kaputte Aufzeichnung darf den Lauf nicht mitreissen -
            // das Ergebnis des Scripts ist wichtiger als das Protokoll.
        }
    }

    private void record(int line, Map<String, Object> variables) {
        executed++;
        if (steps.size() >= maxSteps) {
            return;
        }

        Step step = new Step();
        step.line = line;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                step.variables.put(entry.getKey(), text(entry.getValue()));
            }
        }
        step.console = consoleBuffer.drain();

        Map<String, String> properties = snapshot(message.getProperties());
        Map<String, String> headers = snapshot(message.getHeaders());
        String body = text(message.getBody());

        diff("property", lastProperties, properties, step.changes);
        diff("header", lastHeaders, headers, step.changes);
        if (!body.equals(lastBody)) {
            Change change = new Change();
            change.kind = "body";
            change.name = "body";
            change.before = lastBody;
            change.after = body;
            step.changes.add(change);
        }

        lastProperties = properties;
        lastHeaders = headers;
        lastBody = body;
        steps.add(step);
    }

    List<Step> getSteps() {
        return steps;
    }

    /** Wie viele Statements tatsaechlich liefen - kann groesser sein als die Zahl der Schritte. */
    int getExecuted() {
        return executed;
    }

    boolean isTruncated() {
        return executed > steps.size();
    }

    // ----------------------------------------------------------------- Helfer

    private static Map<String, String> snapshot(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (source != null) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                result.put(entry.getKey(), text(entry.getValue()));
            }
        }
        return result;
    }

    private static void diff(String kind, Map<String, String> before, Map<String, String> after,
                             List<Change> target) {
        Set<String> names = new LinkedHashSet<String>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            String oldValue = before.get(name);
            String newValue = after.get(name);
            if (oldValue == null ? newValue == null : oldValue.equals(newValue)) {
                continue;
            }
            Change change = new Change();
            change.kind = kind;
            change.name = name;
            change.before = oldValue;
            change.after = newValue;
            target.add(change);
        }
    }

    /**
     * Werte werden sofort in Text umgewandelt und gekuerzt. Eine Referenz zu
     * behalten waere falsch: das Objekt kann sich bis zum Ende des Laufs noch
     * aendern, die Aufzeichnung soll aber den Stand von damals zeigen.
     */
    private static String text(Object value) {
        if (value == null) {
            return "null";
        }
        String result;
        try {
            if (value instanceof byte[]) {
                result = "byte[" + ((byte[]) value).length + "]";
            } else if (value.getClass().isArray()) {
                result = arrayText(value);
            } else {
                result = String.valueOf(value);
            }
        } catch (Throwable t) {
            return "<" + value.getClass().getSimpleName() + ": toString() warf "
                    + t.getClass().getSimpleName() + ">";
        }
        if (result.length() > MAX_TEXT) {
            return result.substring(0, MAX_TEXT) + " … (" + result.length() + " Zeichen)";
        }
        return result;
    }

    /**
     * Arrays haben kein brauchbares toString - ohne das stuende in der
     * Aufzeichnung {@code [Ljava.lang.String;@1a2b3c} statt der Werte. Ueber
     * Reflection, damit auch primitive Arrays abgedeckt sind.
     */
    private static String arrayText(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (builder.length() > MAX_TEXT) {
                builder.append(", … (").append(length).append(" Elemente)");
                break;
            }
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.valueOf(java.lang.reflect.Array.get(array, i)));
        }
        return builder.append(']').toString();
    }

    /**
     * Konsolenpuffer, der herausgeben kann, was seit dem letzten Abruf
     * dazugekommen ist. Verhaelt sich sonst wie ein normaler
     * ByteArrayOutputStream, damit der Lauf ohne Aufzeichnung unveraendert
     * bleibt.
     */
    public static class ConsoleBuffer extends ByteArrayOutputStream {

        private int consumed = 0;

        public synchronized String drain() {
            int end = endOfLastCompleteCharacter();
            if (end <= consumed) {
                return "";
            }
            String text = new String(buf, consumed, end - consumed, StandardCharsets.UTF_8);
            consumed = end;
            return text;
        }

        /**
         * Ein an der Puffergrenze halb geschriebenes UTF-8-Zeichen bleibt
         * liegen, bis es vollstaendig ist - sonst stuende im Protokoll ein
         * Ersatzzeichen statt des Umlauts, und zwar dauerhaft.
         */
        private int endOfLastCompleteCharacter() {
            int index = count - 1;
            int continuations = 0;
            while (index >= consumed && continuations < 3 && (buf[index] & 0xC0) == 0x80) {
                index--;
                continuations++;
            }
            if (index < consumed) {
                return count;
            }
            int lead = buf[index] & 0xFF;
            int needed = lead < 0x80 ? 1
                    : lead >= 0xF0 ? 4
                    : lead >= 0xE0 ? 3
                    : lead >= 0xC0 ? 2 : 1;
            return (needed > 1 && continuations + 1 < needed) ? index : count;
        }
    }
}
