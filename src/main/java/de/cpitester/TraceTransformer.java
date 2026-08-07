package de.cpitester;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.CaseStatement;
import org.codehaus.groovy.ast.stmt.CatchStatement;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.IfStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.SwitchStatement;
import org.codehaus.groovy.ast.stmt.SynchronizedStatement;
import org.codehaus.groovy.ast.stmt.ThrowStatement;
import org.codehaus.groovy.ast.stmt.TryCatchStatement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

/**
 * Haengt vor jedes Statement einen Aufruf von
 * {@link TraceRecorder#step(int, java.util.Map)} und gibt dabei die an dieser
 * Stelle sichtbaren lokalen Variablen mit.
 *
 * Warum in Phase CONVERSION: dort steht der AST fertig geparst, aber noch
 * bevor Groovy die Variablen aufloest. Der eingefuegte Code durchlaeuft
 * anschliessend dieselben Phasen wie handgeschriebener - Aufloesung,
 * Typpruefung, Bytecode - und braucht deshalb keine Sonderbehandlung. Der
 * Preis: die Variablenscopes existieren noch nicht, also fuehren wir selbst
 * Buch darueber, was an einer Stelle bereits deklariert ist.
 *
 * Das ist entscheidend fuer die Korrektheit: eine Variable, die erst weiter
 * unten deklariert wird, darf im Hook nicht auftauchen. Der erzeugte Code
 * wuerde sie sonst als Property des Scripts lesen und mit einer
 * MissingPropertyException scheitern.
 *
 * Eingefuegt wird immer VOR dem Statement, nie danach - sonst waere das letzte
 * Statement einer Methode nicht mehr das letzte, und Groovys impliziter
 * Rueckgabewert waere ein anderer.
 */
public class TraceTransformer extends CompilationCustomizer {

    private static final ClassNode RECORDER = ClassHelper.make(TraceRecorder.class);

    public TraceTransformer() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        for (MethodNode method : new ArrayList<MethodNode>(classNode.getMethods())) {
            if (method.isAbstract() || method.isSynthetic() || method.getCode() == null) {
                continue;
            }
            Instrumenter instrumenter = new Instrumenter();
            instrumenter.pushScope();
            for (Parameter parameter : method.getParameters()) {
                instrumenter.declare(parameter.getName());
            }
            instrumenter.instrument(method.getCode());
            instrumenter.popScope();
        }
    }

    /**
     * Baut den Ablauf um. Arbeitet direkt auf den vorhandenen Knoten statt
     * neue Bloecke zu erzeugen, damit alles, was Groovy spaeter an einem Block
     * haengen hat (etwa der Variablenscope), erhalten bleibt.
     */
    private static final class Instrumenter {

        /** Innerster Scope zuerst - die Iterationsreihenfolge von ArrayDeque.push. */
        private final Deque<Set<String>> scopes = new ArrayDeque<Set<String>>();

        void pushScope() {
            scopes.push(new LinkedHashSet<String>());
        }

        void popScope() {
            scopes.pop();
        }

        void declare(String name) {
            if (name != null && !scopes.isEmpty()) {
                scopes.peek().add(name);
            }
        }

        void instrument(Statement statement) {
            if (statement == null) {
                return;
            }
            if (statement instanceof BlockStatement) {
                instrumentBlock((BlockStatement) statement);
                return;
            }
            // Ein einzelnes Statement als Rumpf (etwa `if (x) doSomething()`)
            // bekommt keinen eigenen Hook, aber sein Innenleben schon.
            instrumentInner(statement);
        }

        private void instrumentBlock(BlockStatement block) {
            List<Statement> original = new ArrayList<Statement>(block.getStatements());
            block.getStatements().clear();
            pushScope();
            for (Statement statement : original) {
                Statement hook = hookFor(statement);
                if (hook != null) {
                    block.addStatement(hook);
                }
                block.addStatement(statement);
                instrumentInner(statement);
                declareFrom(statement);
            }
            popScope();
        }

        /** Steigt in Rumpfstatements und Closures ab, ohne selbst einen Hook zu setzen. */
        private void instrumentInner(Statement statement) {
            if (statement instanceof BlockStatement) {
                instrumentBlock((BlockStatement) statement);

            } else if (statement instanceof IfStatement) {
                IfStatement ifStatement = (IfStatement) statement;
                instrumentClosures(ifStatement.getBooleanExpression());
                instrument(ifStatement.getIfBlock());
                instrument(ifStatement.getElseBlock());

            } else if (statement instanceof ForStatement) {
                ForStatement forStatement = (ForStatement) statement;
                instrumentClosures(forStatement.getCollectionExpression());
                pushScope();
                if (forStatement.getVariable() != null) {
                    declare(forStatement.getVariable().getName());
                }
                instrument(forStatement.getLoopBlock());
                popScope();

            } else if (statement instanceof DoWhileStatement) {
                DoWhileStatement loop = (DoWhileStatement) statement;
                instrument(loop.getLoopBlock());
                instrumentClosures(loop.getBooleanExpression());

            } else if (statement instanceof org.codehaus.groovy.ast.stmt.WhileStatement) {
                org.codehaus.groovy.ast.stmt.WhileStatement loop =
                        (org.codehaus.groovy.ast.stmt.WhileStatement) statement;
                instrumentClosures(loop.getBooleanExpression());
                instrument(loop.getLoopBlock());

            } else if (statement instanceof TryCatchStatement) {
                TryCatchStatement tryCatch = (TryCatchStatement) statement;
                instrument(tryCatch.getTryStatement());
                for (CatchStatement catchStatement : tryCatch.getCatchStatements()) {
                    pushScope();
                    if (catchStatement.getVariable() != null) {
                        declare(catchStatement.getVariable().getName());
                    }
                    instrument(catchStatement.getCode());
                    popScope();
                }
                instrument(tryCatch.getFinallyStatement());

            } else if (statement instanceof SwitchStatement) {
                SwitchStatement switchStatement = (SwitchStatement) statement;
                instrumentClosures(switchStatement.getExpression());
                for (CaseStatement caseStatement : switchStatement.getCaseStatements()) {
                    instrument(caseStatement.getCode());
                }
                instrument(switchStatement.getDefaultStatement());

            } else if (statement instanceof SynchronizedStatement) {
                instrument(((SynchronizedStatement) statement).getCode());

            } else if (statement instanceof ExpressionStatement) {
                instrumentClosures(((ExpressionStatement) statement).getExpression());

            } else if (statement instanceof ReturnStatement) {
                instrumentClosures(((ReturnStatement) statement).getExpression());

            } else if (statement instanceof ThrowStatement) {
                instrumentClosures(((ThrowStatement) statement).getExpression());
            }
        }

        /**
         * Closures sind eigene Codebloecke und werden mitprotokolliert. Sie
         * sehen die Variablen der Umgebung, deshalb geschieht das hier - an
         * dieser Stelle stimmt der Scope-Stapel noch.
         */
        private void instrumentClosures(Expression expression) {
            if (expression == null) {
                return;
            }
            expression.visit(new CodeVisitorSupport() {
                @Override
                public void visitClosureExpression(ClosureExpression closure) {
                    pushScope();
                    Parameter[] parameters = closure.getParameters();
                    // Groovys Konvention ist hier kontraintuitiv, nachgemessen
                    // an Groovy 4:
                    //   { it * 2 }   -> leeres Array  (impliziter Parameter `it`)
                    //   { x -> .. }  -> Array mit x
                    //   { -> 42 }    -> null          (ausdruecklich ohne Parameter)
                    // In den null-Fall darf `it` nicht hinein - der Zugriff
                    // waere dort ein Fehler.
                    if (parameters != null) {
                        if (parameters.length == 0) {
                            declare("it");
                        } else {
                            for (Parameter parameter : parameters) {
                                declare(parameter.getName());
                            }
                        }
                    }
                    instrument(closure.getCode());
                    popScope();
                    // Kein super-Aufruf: verschachtelte Closures hat
                    // instrument(..) bereits mitgenommen.
                }
            });
        }

        /** Registriert Variablen, die dieses Statement fuer die folgenden einfuehrt. */
        private void declareFrom(Statement statement) {
            if (!(statement instanceof ExpressionStatement)) {
                return;
            }
            Expression expression = ((ExpressionStatement) statement).getExpression();
            if (!(expression instanceof DeclarationExpression)) {
                return;
            }
            DeclarationExpression declaration = (DeclarationExpression) expression;
            if (declaration.isMultipleAssignmentDeclaration()) {
                for (Expression part : declaration.getTupleExpression().getExpressions()) {
                    if (part instanceof VariableExpression) {
                        declare(((VariableExpression) part).getName());
                    }
                }
            } else {
                declare(declaration.getVariableExpression().getName());
            }
        }

        /**
         * {@code TraceRecorder.step(<zeile>, [name: name, ...])}. Ohne
         * Zeilennummer - etwa bei vom Compiler erzeugten Statements - wird
         * nichts eingefuegt; ein Schritt ohne Ort waere in der Anzeige wertlos.
         */
        private Statement hookFor(Statement statement) {
            int line = statement.getLineNumber();
            if (line < 1) {
                return null;
            }
            MapExpression variables = new MapExpression();
            Set<String> alreadyAdded = new LinkedHashSet<String>();
            for (Set<String> scope : scopes) {          // innerster Scope zuerst
                for (String name : scope) {
                    if (alreadyAdded.add(name)) {       // Verdeckte Variablen nur einmal
                        variables.addMapEntryExpression(
                                new ConstantExpression(name), new VariableExpression(name));
                    }
                }
            }
            StaticMethodCallExpression call = new StaticMethodCallExpression(
                    RECORDER, "step",
                    new ArgumentListExpression(new Expression[] {
                            new ConstantExpression(Integer.valueOf(line)), variables }));
            ExpressionStatement hook = new ExpressionStatement(call);
            // Gleiche Quellposition wie das echte Statement, damit Stacktraces
            // aus dem Script weiterhin auf die richtige Zeile zeigen.
            hook.setSourcePosition(statement);
            return hook;
        }
    }
}
