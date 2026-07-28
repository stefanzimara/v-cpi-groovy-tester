package de.cpitester;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Alles, was ein Testlauf an Eingaben braucht. */
public class RunConfig {

    /** Groovy-Quelltext des zu testenden Scripts. */
    public String scriptSource = "";

    /** Anzeigename des Scripts (taucht in Stacktraces auf). */
    public String scriptName = "Script.groovy";

    /** Message-Body beim Eintritt in das Script. */
    public String body = "";

    /** Exchange-Header. */
    public Map<String, Object> headers = new LinkedHashMap<String, Object>();

    /** Exchange-Properties. */
    public Map<String, Object> properties = new LinkedHashMap<String, Object>();

    /** Alias -> {user, password} fuer den SecureStore-Mock. */
    public Map<String, Map<String, String>> credentials = new LinkedHashMap<String, Map<String, String>>();

    /** Aufzurufende Methode; in CPI immer processData. */
    public String entryMethod = "processData";

    /** false = messageLogFactory.getMessageLog(msg) liefert null (Log-Level "None"). */
    public boolean messageLogEnabled = true;

    public Charset charset = StandardCharsets.UTF_8;
}
