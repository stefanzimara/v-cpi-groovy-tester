package de.cpitester;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import com.sap.gateway.ip.core.customdev.util.Message;
import com.sap.it.api.ITApiFactory;
import com.sap.it.api.msglog.MessageLog;
import com.sap.it.api.msglog.factory.MessageLogFactory;
import com.sap.it.api.securestore.SecureStoreService;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;

/**
 * Fuehrt ein CPI-Groovy-Script lokal aus: baut eine Message auf, ruft
 * processData(Message) auf und sammelt Body, Header, Properties, Konsole
 * und MessageLog-Attachments ein.
 */
public class ScriptRunner {

    /** System.out/System.err sind prozessweit - Laeufe daher serialisieren. */
    private static final Object CONSOLE_LOCK = new Object();

    public RunResult run(RunConfig config) {
        RunResult result = new RunResult();
        long start = System.currentTimeMillis();

        // Welche Message-Klasse gebaut wird, entscheidet sich erst nach dem
        // Kompilieren anhand der Signatur von processData(..).
        MessageSupport message = null;

        MessageLogFactory messageLogFactory = new MessageLogFactory();
        messageLogFactory.setEnabled(config.messageLogEnabled);

        SecureStoreService secureStore = new SecureStoreService();
        if (config.credentials != null) {
            for (Map.Entry<String, Map<String, String>> entry : config.credentials.entrySet()) {
                Map<String, String> value = entry.getValue();
                String user = value == null ? null : value.get("user");
                String password = value == null ? null : value.get("password");
                secureStore.register(entry.getKey(), user, password);
            }
        }
        ITApiFactory.reset();
        ITApiFactory.registerService(SecureStoreService.class, secureStore);

        ByteArrayOutputStream consoleBuffer = new ByteArrayOutputStream();

        synchronized (CONSOLE_LOCK) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream capture = new PrintStream(consoleBuffer, true, java.nio.charset.StandardCharsets.UTF_8);
            GroovyClassLoader classLoader = null;
            try {
                System.setOut(capture);
                System.setErr(capture);

                CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
                compilerConfiguration.setSourceEncoding("UTF-8");
                classLoader = new GroovyClassLoader(
                        Thread.currentThread().getContextClassLoader(), compilerConfiguration);

                GroovyCodeSource codeSource = new GroovyCodeSource(
                        config.scriptSource == null ? "" : config.scriptSource,
                        sanitizeScriptName(config.scriptName),
                        "/groovy/cpitester");
                codeSource.setCachable(false);

                Class<?> scriptClass = classLoader.parseClass(codeSource, false);

                String entryMethod = (config.entryMethod == null || config.entryMethod.isEmpty())
                        ? "processData" : config.entryMethod;

                message = createMessage(scriptClass, entryMethod, config);
                result.apiVersion = (message instanceof com.sap.it.script.v2.api.Message)
                        ? "Script Version 2.x (com.sap.it.script.v2.api.Message)"
                        : "Script Version 1.x (com.sap.gateway.ip.core.customdev.util.Message)";

                Binding binding = new Binding();
                binding.setVariable("messageLogFactory", messageLogFactory);
                binding.setVariable("message", message);

                Object instance = scriptClass.getDeclaredConstructor().newInstance();
                if (instance instanceof Script) {
                    ((Script) instance).setBinding(binding);
                }

                Object returned = org.codehaus.groovy.runtime.InvokerHelper
                        .invokeMethod(instance, entryMethod, new Object[] { message });

                MessageSupport returnedMessage =
                        (returned instanceof MessageSupport) ? (MessageSupport) returned : message;
                result.body = toStringBody(returnedMessage);
                result.headers.putAll(returnedMessage.getHeaders());
                result.properties.putAll(returnedMessage.getProperties());
                result.success = true;
            } catch (Throwable t) {
                Throwable cause = unwrap(t);
                result.success = false;
                result.error = cause.getClass().getName() + ": " + cause.getMessage();
                result.stackTrace = stackTraceOf(cause);
                // Zustand bis zum Fehlerzeitpunkt trotzdem mitgeben - dort steht
                // bei CPI-Scripten meist das aussagekraeftige processDebugLog drin.
                try {
                    if (message != null) {   // null, wenn schon das Kompilieren fehlschlug
                        result.body = toStringBody(message);
                        result.headers.putAll(message.getHeaders());
                        result.properties.putAll(message.getProperties());
                    }
                } catch (Exception ignored) {
                    // Body nicht lesbar -> irrelevant, der Fehler ist bereits erfasst
                }
            } finally {
                System.out.flush();
                System.err.flush();
                System.setOut(originalOut);
                System.setErr(originalErr);
                if (classLoader != null) {
                    try {
                        classLoader.close();
                    } catch (Exception ignored) {
                        // Aufraeumen best effort
                    }
                }
            }
        }

        result.console = consoleBuffer.toString(java.nio.charset.StandardCharsets.UTF_8);

        MessageLog log = messageLogFactory.getRecordedMessageLog();
        for (MessageLog.Attachment attachment : log.getAttachments()) {
            result.attachments.add(new RunResult.AttachmentView(
                    attachment.name, attachment.mimeType, attachment.content));
        }
        result.customHeaderProperties.putAll(log.getCustomHeaderProperties());
        result.durationMs = System.currentTimeMillis() - start;
        return result;
    }

    /**
     * Baut die Message passend zur Signatur von processData(..): Scripte der
     * Version 2.x deklarieren com.sap.it.script.v2.api.Message, aeltere die
     * Klasse aus com.sap.gateway.ip.core.customdev.util. Laesst sich das nicht
     * ermitteln, wird die Version-1-Klasse verwendet.
     */
    private static MessageSupport createMessage(Class<?> scriptClass, String entryMethod, RunConfig config) {
        Class<?> parameterType = null;
        for (java.lang.reflect.Method method : scriptClass.getDeclaredMethods()) {
            if (method.getName().equals(entryMethod) && method.getParameterCount() == 1) {
                parameterType = method.getParameterTypes()[0];
                break;
            }
        }

        MessageSupport message =
                (parameterType != null && com.sap.it.script.v2.api.Message.class.isAssignableFrom(parameterType))
                        ? new com.sap.it.script.v2.api.Message()
                        : new Message();

        message.setCharset(config.charset);
        message.setBody(config.body == null ? "" : config.body);
        if (config.headers != null) {
            message.getHeaders().putAll(config.headers);
        }
        if (config.properties != null) {
            message.getProperties().putAll(config.properties);
        }
        return message;
    }

    private static String toStringBody(MessageSupport message) {
        Object body = message.getBody();
        if (body == null) {
            return "";
        }
        return message.getBody(String.class);
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** Groovy leitet aus dem Namen einen Klassennamen ab - nur sichere Zeichen zulassen. */
    private static String sanitizeScriptName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Script.groovy";
        }
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (!base.endsWith(".groovy")) {
            base = base + ".groovy";
        }
        if (!Character.isJavaIdentifierStart(base.charAt(0))) {
            base = "_" + base;
        }
        return base;
    }
}
