package de.cpitester;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.control.messages.WarningMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import groovy.lang.GroovyClassLoader;

/**
 * Prueft ein Groovy-Script, ohne es auszufuehren, und meldet Probleme mit
 * Zeile/Spalte zurueck - fuer die Live-Anzeige im Editor der Web-UI.
 *
 * Zwei Quellen:
 *  1. Der Groovy-Compiler selbst, bis Phase CANONICALIZATION. Das deckt
 *     Syntaxfehler, nicht aufloesbare Imports/Klassen und die Compiler-
 *     Warnungen ab - also alles, was auch beim echten Lauf scheitern wuerde.
 *  2. CPI-spezifische Regeln auf dem AST ({@link CpiRuleVisitor}), also
 *     Stolperfallen, die ein normaler Groovy-Compiler nicht kennen kann:
 *     zweimal gelesener Body, fehlender Null-Check am MessageLog, im
 *     Testfall nicht gesetzte Properties usw.
 *
 * WICHTIG: Kompilieren bis CANONICALIZATION fuehrt AST-Transformationen aus,
 * und die sind beliebiger Code. Diese Pruefung ist deshalb genauso wenig
 * "harmlos" wie ein Lauf und haengt in der WebUi hinter demselben Schutz.
 */
public class CodeInspector {

    /** Obergrenze, damit ein kaputtes Script die UI nicht mit Meldungen flutet. */
    private static final int MAX_FINDINGS = 200;

    public static final String SEVERITY_ERROR = "error";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_INFO = "info";

    /**
     * Ein Befund. {@code rule} ist der stabile Schluessel fuer die
     * Uebersetzung in der UI, {@code message} der englische Fallback-Text -
     * bei Compilerfehlern ist er die einzige Quelle, weil dieser Text vom
     * Groovy-Compiler kommt und nicht uebersetzt wird.
     */
    public static class Finding {
        public String rule;
        public String severity;
        public int line = 1;
        public int column = 1;
        public int endLine = 1;
        public int endColumn = 1;
        public String message = "";
        public List<String> params = new ArrayList<String>();

        Finding(String rule, String severity, String message) {
            this.rule = rule;
            this.severity = severity;
            this.message = message == null ? "" : message.trim();
        }

        Finding at(int line, int column, int endLine, int endColumn) {
            this.line = Math.max(1, line);
            this.column = Math.max(1, column);
            this.endLine = Math.max(this.line, endLine);
            this.endColumn = Math.max(1, endColumn);
            return this;
        }

        Finding at(org.codehaus.groovy.ast.ASTNode node) {
            return at(node.getLineNumber(), node.getColumnNumber(),
                    node.getLastLineNumber(), node.getLastColumnNumber());
        }

        Finding with(String... values) {
            Collections.addAll(this.params, values);
            return this;
        }
    }

    /** Was der aktuell eingestellte Testfall bereitstellt - fuer die Property-/Header-Regeln. */
    public static class Context {
        public String scriptName = "Script.groovy";
        public String entryMethod = "processData";
        public Set<String> knownHeaders = new LinkedHashSet<String>();
        public Set<String> knownProperties = new LinkedHashSet<String>();
    }

    public List<Finding> inspect(String source, Context context) {
        List<Finding> findings = new ArrayList<Finding>();
        if (source == null || source.trim().isEmpty()) {
            return findings;
        }
        if (context == null) {
            context = new Context();
        }

        CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
        compilerConfiguration.setSourceEncoding("UTF-8");
        // Compiler-Warnungen mitnehmen, nicht nur Fehler.
        compilerConfiguration.setWarningLevel(WarningMessage.LIKELY_ERRORS);

        GroovyClassLoader classLoader = new GroovyClassLoader(
                Thread.currentThread().getContextClassLoader(), compilerConfiguration);
        CompilationUnit unit = new CompilationUnit(compilerConfiguration, null, classLoader);
        SourceUnit sourceUnit = unit.addSource(
                ScriptRunner.sanitizeScriptName(context.scriptName), source);

        try {
            // Bis CANONICALIZATION: Parsen, Imports/Klassen aufloesen, AST-
            // Transformationen anwenden - aber keinen Bytecode erzeugen.
            unit.compile(Phases.CANONICALIZATION);
        } catch (org.codehaus.groovy.control.MultipleCompilationErrorsException expected) {
            // Die Meldungen stehen im ErrorCollector, unten eingesammelt.
        } catch (Throwable t) {
            findings.add(new Finding("compile", SEVERITY_ERROR,
                    t.getClass().getSimpleName() + ": " + t.getMessage()));
        } finally {
            try {
                classLoader.close();
            } catch (Exception ignored) {
                // Aufraeumen best effort
            }
        }

        collectCompilerMessages(unit.getErrorCollector(), findings);

        // Ein Script, das nicht einmal parst, hat keinen brauchbaren AST -
        // die CPI-Regeln wuerden daran nur Unsinn melden.
        boolean hasErrors = false;
        for (Finding finding : findings) {
            if (SEVERITY_ERROR.equals(finding.severity)) {
                hasErrors = true;
                break;
            }
        }
        if (!hasErrors) {
            try {
                ModuleNode module = moduleOf(unit);
                if (module != null) {
                    findings.addAll(new CpiRuleVisitor(sourceUnit, context).analyse(module));
                }
            } catch (Throwable t) {
                // Die Regeln sind eine Zugabe - wenn eine davon stolpert, darf
                // das die Compiler-Meldungen nicht mitreissen.
                findings.add(new Finding("ruleFailure", SEVERITY_INFO,
                        "Inspection rules could not run: " + t));
            }
        }

        if (findings.size() > MAX_FINDINGS) {
            return new ArrayList<Finding>(findings.subList(0, MAX_FINDINGS));
        }
        return findings;
    }

    private static ModuleNode moduleOf(CompilationUnit unit) {
        if (unit.getAST() == null || unit.getAST().getModules().isEmpty()) {
            return null;
        }
        return unit.getAST().getModules().get(0);
    }

    // --------------------------------------------------------- Compilermeldungen

    private static void collectCompilerMessages(ErrorCollector collector, List<Finding> findings) {
        if (collector == null) {
            return;
        }
        List<?> errors = collector.getErrors();
        if (errors != null) {
            for (Object entry : errors) {
                findings.add(toFinding(entry));
            }
        }
        List<?> warnings = collector.getWarnings();
        if (warnings != null) {
            for (Object entry : warnings) {
                findings.add(toWarningFinding(entry));
            }
        }
    }

    private static Finding toFinding(Object message) {
        if (message instanceof SyntaxErrorMessage) {
            SyntaxException cause = ((SyntaxErrorMessage) message).getCause();
            return new Finding("compile", SEVERITY_ERROR, cause.getOriginalMessage())
                    .at(cause.getStartLine(), cause.getStartColumn(),
                        cause.getEndLine(), cause.getEndColumn());
        }
        if (message instanceof ExceptionMessage) {
            Throwable cause = ((ExceptionMessage) message).getCause();
            return new Finding("compile", SEVERITY_ERROR,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
        return new Finding("compile", SEVERITY_ERROR, textOf(message));
    }

    private static Finding toWarningFinding(Object message) {
        if (message instanceof WarningMessage) {
            WarningMessage warning = (WarningMessage) message;
            Finding finding = new Finding("compileWarning", SEVERITY_WARNING, warning.getMessage());
            org.codehaus.groovy.syntax.CSTNode context = warning.getContext();
            if (context != null) {
                finding.at(context.getStartLine(), context.getStartColumn(),
                        context.getStartLine(), context.getStartColumn() + 1);
            }
            return finding;
        }
        return new Finding("compileWarning", SEVERITY_WARNING, textOf(message));
    }

    private static String textOf(Object message) {
        if (message instanceof org.codehaus.groovy.control.messages.Message) {
            StringWriter writer = new StringWriter();
            PrintWriter printer = new PrintWriter(writer);
            ((org.codehaus.groovy.control.messages.Message) message).write(printer);
            printer.flush();
            return writer.toString().trim();
        }
        return String.valueOf(message);
    }

    // ------------------------------------------------------------- CPI-Regeln

    /**
     * Laeuft einmal ueber den AST und sammelt die CPI-typischen Stolperfallen
     * ein. Bewusst konservativ: lieber eine Falle nicht melden als staendig
     * falschen Alarm schlagen - ein Linter, dem man nicht glaubt, wird
     * abgeschaltet.
     */
    private static final class CpiRuleVisitor extends ClassCodeVisitorSupport {

        private static final String MESSAGE_V1 = "com.sap.gateway.ip.core.customdev.util.Message";
        private static final String MESSAGE_V2 = "com.sap.it.script.v2.api.Message";

        private static final Pattern SECRET_NAME =
                Pattern.compile("(?i).*(password|passwd|pwd|secret|apikey|api_key|credential).*");

        private final SourceUnit sourceUnit;
        private final Context context;
        private final List<Finding> findings = new ArrayList<Finding>();

        /** Name des Message-Parameters der Einstiegsmethode, Default {@code message}. */
        private String messageVar = "message";

        /** Anker fuer Befunde, die das Script als Ganzes betreffen. */
        private MethodNode entryMethod = null;

        /** Variablen, die ein MessageLog aus der Factory halten. */
        private final Set<String> messageLogVars = new LinkedHashSet<String>();
        /** Diese Variablen werden irgendwo gegen null geprueft oder mit ?. benutzt. */
        private final Set<String> nullCheckedVars = new LinkedHashSet<String>();
        /** MessageLog-Variablen, auf denen ungeschuetzt eine Methode gerufen wird. */
        private final List<Object[]> unguardedLogCalls = new ArrayList<Object[]>();

        /** Property-/Header-Namen, die das Script selbst setzt - die zaehlen als bekannt. */
        private final Set<String> scriptDefinedProperties = new LinkedHashSet<String>();
        private final Set<String> scriptDefinedHeaders = new LinkedHashSet<String>();
        /** Gelesene Namen, erst am Ende gegen die bekannten abgeglichen. */
        private final List<Object[]> readProperties = new ArrayList<Object[]>();
        private final List<Object[]> readHeaders = new ArrayList<Object[]>();

        private final List<org.codehaus.groovy.ast.ASTNode> untypedBodyReads =
                new ArrayList<org.codehaus.groovy.ast.ASTNode>();
        private boolean sawSetBody = false;
        private org.codehaus.groovy.ast.ASTNode firstPrintln = null;
        private int printlnCount = 0;

        CpiRuleVisitor(SourceUnit sourceUnit, Context context) {
            this.sourceUnit = sourceUnit;
            this.context = context;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        List<Finding> analyse(ModuleNode module) {
            entryMethod = findEntryMethod(module);
            if (entryMethod != null && entryMethod.getParameters().length == 1) {
                messageVar = entryMethod.getParameters()[0].getName();
            }

            checkEntryMethod(entryMethod);
            checkApiVersionMix(module);

            for (ClassNode classNode : module.getClasses()) {
                visitClass(classNode);
            }

            finishBodyRules();
            finishMessageLogRules();
            finishNameRules();
            finishPrintlnRule();
            return findings;
        }

        // ---------- Regeln, die nur die Signatur brauchen

        private MethodNode findEntryMethod(ModuleNode module) {
            String name = (context.entryMethod == null || context.entryMethod.trim().isEmpty())
                    ? "processData" : context.entryMethod.trim();
            for (ClassNode classNode : module.getClasses()) {
                for (MethodNode method : classNode.getDeclaredMethods(name)) {
                    if (method.getParameters().length == 1) {
                        return method;
                    }
                }
            }
            for (MethodNode method : module.getMethods()) {
                if (method.getName().equals(name) && method.getParameters().length == 1) {
                    return method;
                }
            }
            return null;
        }

        private void checkEntryMethod(MethodNode entry) {
            String name = (context.entryMethod == null || context.entryMethod.trim().isEmpty())
                    ? "processData" : context.entryMethod.trim();
            if (entry == null) {
                findings.add(new Finding("entryMissing", SEVERITY_ERROR,
                        "No method '" + name + "(Message)' found - the runner cannot call this script.")
                        .with(name));
                return;
            }
            String parameterType = entry.getParameters()[0].getType().getName();
            if (!MESSAGE_V1.equals(parameterType) && !MESSAGE_V2.equals(parameterType)
                    && !"java.lang.Object".equals(parameterType)) {
                findings.add(new Finding("entryParamType", SEVERITY_WARNING,
                        "'" + name + "' takes a " + parameterType
                                + " - CPI always hands in a Message.")
                        .with(name, parameterType)
                        .at(entry.getParameters()[0]));
            }
            if (entry.getReturnType() != null && "void".equals(entry.getReturnType().getName())) {
                findings.add(new Finding("entryReturnsVoid", SEVERITY_ERROR,
                        "'" + name + "' is declared void - it must return the Message.")
                        .with(name)
                        .at(entry));
            }
        }

        /**
         * Beide Message-Generationen gleichzeitig importiert: laeuft lokal
         * womoeglich, in CPI aber sicher nicht - dort gibt es pro Script
         * genau eine Generation.
         */
        private void checkApiVersionMix(ModuleNode module) {
            ImportNode v1 = null;
            ImportNode v2 = null;
            for (ImportNode importNode : module.getImports()) {
                if (importNode.getType() == null) {
                    continue;
                }
                String type = importNode.getType().getName();
                if (MESSAGE_V1.equals(type)) {
                    v1 = importNode;
                } else if (MESSAGE_V2.equals(type)) {
                    v2 = importNode;
                }
            }
            if (v1 != null && v2 != null) {
                findings.add(new Finding("apiVersionMix", SEVERITY_WARNING,
                        "Both the Script Version 1.x and 2.x Message classes are imported - "
                                + "a CPI script uses exactly one generation.")
                        .at(v2));
            }
        }

        // ---------- AST-Besuch

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            String method = call.getMethodAsString();
            Expression receiver = call.getObjectExpression();

            if (method != null) {
                if (isMessageVar(receiver)) {
                    handleMessageCall(call, method);
                }
                if ("sleep".equals(method) && isTypeReference(receiver, "Thread")) {
                    findings.add(new Finding("forbiddenSleep", SEVERITY_WARNING,
                            "Thread.sleep blocks a CPI worker thread.").at(call));
                }
                if ("exit".equals(method) && isTypeReference(receiver, "System")) {
                    findings.add(new Finding("forbiddenExit", SEVERITY_ERROR,
                            "System.exit would kill the whole runtime, not just this script.").at(call));
                }
                if (("println".equals(method) || "print".equals(method)) && call.isImplicitThis()) {
                    printlnCount++;
                    if (firstPrintln == null) {
                        firstPrintln = call;
                    }
                }
                // MessageLog-Variable ohne Null-Schutz benutzt?
                if (receiver instanceof VariableExpression
                        && messageLogVars.contains(((VariableExpression) receiver).getName())
                        && !call.isSafe()) {
                    unguardedLogCalls.add(new Object[] { ((VariableExpression) receiver).getName(), call });
                }
            }
            if (call.isSafe() && receiver instanceof VariableExpression) {
                nullCheckedVars.add(((VariableExpression) receiver).getName());
            }
            // getProperties().get("x") / getHeaders().get("x")
            handleMapAccessCall(call);

            super.visitMethodCallExpression(call);
        }

        private void handleMessageCall(MethodCallExpression call, String method) {
            List<Expression> arguments = argumentsOf(call);
            if ("getBody".equals(method) && arguments.isEmpty()) {
                untypedBodyReads.add(call);
            } else if ("setBody".equals(method)) {
                sawSetBody = true;
            } else if ("getProperty".equals(method) && !arguments.isEmpty()) {
                String name = literalOf(arguments.get(0));
                if (name != null) {
                    readProperties.add(new Object[] { name, call });
                }
            } else if ("setProperty".equals(method) && !arguments.isEmpty()) {
                String name = literalOf(arguments.get(0));
                if (name != null) {
                    scriptDefinedProperties.add(name);
                }
            } else if ("getHeader".equals(method) && !arguments.isEmpty()) {
                String name = literalOf(arguments.get(0));
                if (name != null) {
                    readHeaders.add(new Object[] { name, call });
                }
            } else if ("setHeader".equals(method) && !arguments.isEmpty()) {
                String name = literalOf(arguments.get(0));
                if (name != null) {
                    scriptDefinedHeaders.add(name);
                }
            }
        }

        /** {@code message.getProperties().get("x")} - die andere gaengige Schreibweise. */
        private void handleMapAccessCall(MethodCallExpression call) {
            if (!"get".equals(call.getMethodAsString())) {
                return;
            }
            List<Expression> arguments = argumentsOf(call);
            if (arguments.size() != 1) {
                return;
            }
            String name = literalOf(arguments.get(0));
            if (name == null) {
                return;
            }
            String source = mapAccessSource(call.getObjectExpression());
            if ("properties".equals(source)) {
                readProperties.add(new Object[] { name, call });
            } else if ("headers".equals(source)) {
                readHeaders.add(new Object[] { name, call });
            }
        }

        /** Liefert "properties"/"headers", wenn der Ausdruck message.getProperties()/getHeaders() ist. */
        private String mapAccessSource(Expression expression) {
            if (expression instanceof MethodCallExpression) {
                MethodCallExpression call = (MethodCallExpression) expression;
                if (isMessageVar(call.getObjectExpression())) {
                    if ("getProperties".equals(call.getMethodAsString())) {
                        return "properties";
                    }
                    if ("getHeaders".equals(call.getMethodAsString())) {
                        return "headers";
                    }
                }
            }
            if (expression instanceof PropertyExpression) {
                PropertyExpression property = (PropertyExpression) expression;
                if (isMessageVar(property.getObjectExpression())) {
                    String name = property.getPropertyAsString();
                    if ("properties".equals(name) || "headers".equals(name)) {
                        return name;
                    }
                }
            }
            return null;
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            // message.body liest ebenfalls den ungetypten Body
            if (isMessageVar(expression.getObjectExpression())
                    && "body".equals(expression.getPropertyAsString())) {
                untypedBodyReads.add(expression);
            }
            if (expression.isSafe() && expression.getObjectExpression() instanceof VariableExpression) {
                nullCheckedVars.add(((VariableExpression) expression.getObjectExpression()).getName());
            }
            super.visitPropertyExpression(expression);
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            if (!expression.isMultipleAssignmentDeclaration()) {
                String name = expression.getVariableExpression().getName();
                noteAssignment(name, expression.getRightExpression(), expression);
            }
            super.visitDeclarationExpression(expression);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            String operation = expression.getOperation().getText();

            // Eine DeclarationExpression IST eine BinaryExpression und laeuft
            // ueber visitDeclarationExpression hier nochmals herein - sonst
            // stuende jede Zuweisung doppelt in der Liste.
            if ("=".equals(operation) && !(expression instanceof DeclarationExpression)
                    && expression.getLeftExpression() instanceof VariableExpression) {
                noteAssignment(((VariableExpression) expression.getLeftExpression()).getName(),
                        expression.getRightExpression(), expression);
            }
            if ("==".equals(operation) || "!=".equals(operation)) {
                noteNullComparison(expression.getLeftExpression(), expression.getRightExpression());
                noteNullComparison(expression.getRightExpression(), expression.getLeftExpression());
            }
            // message.getProperties()["x"]
            if ("[".equals(operation)) {
                String name = literalOf(expression.getRightExpression());
                String source = mapAccessSource(expression.getLeftExpression());
                if (name != null && "properties".equals(source)) {
                    readProperties.add(new Object[] { name, expression });
                } else if (name != null && "headers".equals(source)) {
                    readHeaders.add(new Object[] { name, expression });
                }
            }
            super.visitBinaryExpression(expression);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            ClassNode type = call.getType();
            if (type != null && "java.io.File".equals(type.getName())) {
                findings.add(new Finding("fileAccess", SEVERITY_WARNING,
                        "CPI scripts have no file system - java.io.File will not work on the tenant.")
                        .at(call));
            }
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            ClassNode owner = call.getOwnerType();
            if (owner != null) {
                if ("java.lang.System".equals(owner.getName()) && "exit".equals(call.getMethod())) {
                    findings.add(new Finding("forbiddenExit", SEVERITY_ERROR,
                            "System.exit would kill the whole runtime, not just this script.").at(call));
                }
                if ("java.lang.Thread".equals(owner.getName()) && "sleep".equals(call.getMethod())) {
                    findings.add(new Finding("forbiddenSleep", SEVERITY_WARNING,
                            "Thread.sleep blocks a CPI worker thread.").at(call));
                }
            }
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitCatchStatement(CatchStatement statement) {
            if (isEmpty(statement.getCode())) {
                findings.add(new Finding("emptyCatch", SEVERITY_WARNING,
                        "Empty catch block - the error disappears without a trace.")
                        .at(statement));
            }
            super.visitCatchStatement(statement);
        }

        // ---------- Auswertung am Ende

        private void noteAssignment(String variable, Expression value, org.codehaus.groovy.ast.ASTNode node) {
            if (isMessageLogFactoryCall(value)) {
                messageLogVars.add(variable);
            }
            String literal = literalOf(value);
            if (literal != null && !literal.isEmpty() && SECRET_NAME.matcher(variable).matches()) {
                findings.add(new Finding("hardcodedSecret", SEVERITY_WARNING,
                        "'" + variable + "' holds a hard-coded literal - use the secure store "
                                + "(SecureStoreService) instead.")
                        .with(variable)
                        .at(node));
            }
        }

        private boolean isMessageLogFactoryCall(Expression value) {
            if (!(value instanceof MethodCallExpression)) {
                return false;
            }
            MethodCallExpression call = (MethodCallExpression) value;
            return "getMessageLog".equals(call.getMethodAsString())
                    && isVariable(call.getObjectExpression(), "messageLogFactory");
        }

        private void noteNullComparison(Expression candidate, Expression other) {
            if (candidate instanceof VariableExpression && isNullLiteral(other)) {
                nullCheckedVars.add(((VariableExpression) candidate).getName());
            }
        }

        private void finishBodyRules() {
            if (untypedBodyReads.size() > 1) {
                // Erst ab dem zweiten Lesen wird es gefaehrlich - dort markieren.
                for (int i = 1; i < untypedBodyReads.size(); i++) {
                    findings.add(new Finding("bodyReadTwice", SEVERITY_WARNING,
                            "The untyped body is read more than once - in CPI it is an InputStream "
                                    + "and is empty from the second read on. Use getBody(java.lang.String).")
                            .at(untypedBodyReads.get(i)));
                }
            } else if (untypedBodyReads.size() == 1) {
                findings.add(new Finding("bodyUntyped", SEVERITY_INFO,
                        "getBody() without a type returns an InputStream in CPI, readable only once. "
                                + "getBody(java.lang.String) is the safer form.")
                        .at(untypedBodyReads.get(0)));
            }
            // Ohne Einstiegsmethode ist der Hinweis auf setBody nur Rauschen -
            // dann ist ohnehin schon das Wesentliche gemeldet.
            if (!sawSetBody && entryMethod != null) {
                Finding finding = new Finding("setBodyMissing", SEVERITY_INFO,
                        "The script never calls setBody - the message body is passed through unchanged.");
                if (entryMethod != null) {
                    finding.at(entryMethod.getLineNumber(), entryMethod.getColumnNumber(),
                            entryMethod.getLineNumber(), entryMethod.getColumnNumber() + 1);
                }
                findings.add(finding);
            }
        }

        private void finishMessageLogRules() {
            Set<String> reported = new LinkedHashSet<String>();
            for (Object[] entry : unguardedLogCalls) {
                String variable = (String) entry[0];
                if (nullCheckedVars.contains(variable) || !reported.add(variable)) {
                    continue;
                }
                findings.add(new Finding("messageLogNullCheck", SEVERITY_WARNING,
                        "'" + variable + "' can be null - messageLogFactory.getMessageLog(..) returns "
                                + "null when logging is switched off on the tenant.")
                        .with(variable)
                        .at((org.codehaus.groovy.ast.ASTNode) entry[1]));
            }
        }

        private void finishNameRules() {
            reportUnknownNames(readProperties, context.knownProperties, scriptDefinedProperties,
                    "unknownProperty", "property");
            reportUnknownNames(readHeaders, context.knownHeaders, scriptDefinedHeaders,
                    "unknownHeader", "header");
        }

        private void reportUnknownNames(List<Object[]> reads, Collection<String> known,
                                        Collection<String> definedByScript, String rule, String label) {
            Set<String> reported = new LinkedHashSet<String>();
            for (Object[] entry : reads) {
                String name = (String) entry[0];
                if (known.contains(name) || definedByScript.contains(name) || !reported.add(name)) {
                    continue;
                }
                findings.add(new Finding(rule, SEVERITY_INFO,
                        "The " + label + " '" + name + "' is read but not set in the current test case.")
                        .with(name)
                        .at((org.codehaus.groovy.ast.ASTNode) entry[1]));
            }
        }

        private void finishPrintlnRule() {
            if (firstPrintln != null) {
                findings.add(new Finding("printlnUsage", SEVERITY_INFO,
                        "println writes to the console only (" + printlnCount + "x). On the tenant "
                                + "nobody sees it - log via messageLogFactory or a property instead.")
                        .with(String.valueOf(printlnCount))
                        .at(firstPrintln));
            }
        }

        // ---------- kleine Helfer

        private boolean isMessageVar(Expression expression) {
            return isVariable(expression, messageVar);
        }

        private static boolean isVariable(Expression expression, String name) {
            return expression instanceof VariableExpression
                    && name.equals(((VariableExpression) expression).getName());
        }

        /**
         * Trifft sowohl {@code System.exit} (System als Variable geparst) als
         * auch bereits aufgeloeste Klassenreferenzen.
         */
        private static boolean isTypeReference(Expression expression, String simpleName) {
            if (expression instanceof VariableExpression) {
                return simpleName.equals(((VariableExpression) expression).getName());
            }
            if (expression instanceof org.codehaus.groovy.ast.expr.ClassExpression) {
                ClassNode type = expression.getType();
                return type != null && simpleName.equals(type.getNameWithoutPackage());
            }
            return false;
        }

        private static List<Expression> argumentsOf(MethodCallExpression call) {
            if (call.getArguments() instanceof org.codehaus.groovy.ast.expr.TupleExpression) {
                return ((org.codehaus.groovy.ast.expr.TupleExpression) call.getArguments()).getExpressions();
            }
            return Collections.emptyList();
        }

        private static String literalOf(Expression expression) {
            if (expression instanceof ConstantExpression) {
                Object value = ((ConstantExpression) expression).getValue();
                return value instanceof String ? (String) value : null;
            }
            return null;
        }

        private static boolean isNullLiteral(Expression expression) {
            return expression instanceof ConstantExpression
                    && ((ConstantExpression) expression).getValue() == null;
        }

        private static boolean isEmpty(Statement statement) {
            if (statement == null || statement instanceof org.codehaus.groovy.ast.stmt.EmptyStatement) {
                return true;
            }
            return statement instanceof BlockStatement
                    && ((BlockStatement) statement).getStatements().isEmpty();
        }
    }
}
