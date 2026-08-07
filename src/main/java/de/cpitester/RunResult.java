package de.cpitester;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ergebnis eines Testlaufs. */
public class RunResult {

    public boolean success;

    /** Message-Body nach dem Lauf. */
    public String body = "";

    /** Header nach dem Lauf. */
    public Map<String, Object> headers = new LinkedHashMap<String, Object>();

    /** Properties nach dem Lauf (inkl. z.B. processDebugLog). */
    public Map<String, Object> properties = new LinkedHashMap<String, Object>();

    /** Alles, was das Script auf System.out / System.err geschrieben hat. */
    public String console = "";

    /** Ueber messageLog.addAttachmentAsString(..) erzeugte Anhaenge. */
    public List<AttachmentView> attachments = new ArrayList<AttachmentView>();

    /** Ueber messageLog.addCustomHeaderProperty(..) gesetzte Werte. */
    public Map<String, String> customHeaderProperties = new LinkedHashMap<String, String>();

    /** Kurzform des Fehlers, falls der Lauf abgebrochen ist. */
    public String error = "";

    /** Vollstaendiger Stacktrace, falls der Lauf abgebrochen ist. */
    public String stackTrace = "";

    public long durationMs;

    /** Groovy-Laufzeit, mit der ausgefuehrt wurde (CPI 1.x = 2.4, CPI 2.x = 4). */
    public String groovyVersion = groovy.lang.GroovySystem.getVersion();

    /** Erkannte Script-API-Generation anhand der Signatur von processData(..). */
    public String apiVersion = "";

    /** Aufgezeichneter Ablauf, sofern {@code RunConfig.trace} gesetzt war. */
    public List<TraceRecorder.Step> trace = new ArrayList<TraceRecorder.Step>();

    /** Tatsaechlich ausgefuehrte Statements - kann groesser sein als {@code trace.size()}. */
    public int traceExecuted;

    /** true, wenn die Aufzeichnung am Limit abgeschnitten wurde. */
    public boolean traceTruncated;

    public static class AttachmentView {
        public String name;
        public String mimeType;
        public String content;

        public AttachmentView(String name, String mimeType, String content) {
            this.name = name;
            this.mimeType = mimeType;
            this.content = content;
        }
    }
}
