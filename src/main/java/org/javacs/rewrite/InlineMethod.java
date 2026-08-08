package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.lang.model.element.Element;

/**
 * Inline a call to a method whose body is a single {@code return <expr>;}, replacing the call
 * expression with that expression and substituting each parameter by the corresponding argument.
 *
 * <p>Bounded to the cases where this is provably behavior-preserving: the callee is declared in the
 * same file, is not varargs, and its returned expression references only its own parameters (no
 * fields, {@code this}, other methods, or types that might not resolve at the call site). Every
 * argument must be side-effect-free, so substituting it any number of times — including zero, for
 * an unused parameter — preserves behavior. Each substituted argument and the whole result are
 * parenthesized to preserve evaluation order. Otherwise the rewrite is CANCELLED.
 */
public class InlineMethod implements Rewrite {
    final Path file;
    final int position;

    public InlineMethod(Path file, int position) {
        this.file = file;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var edits = plan(task, position);
            if (edits == null) return CANCELLED;
            return Map.of(file, edits);
        }
    }

    /** Whether inlining the call at {@code position} is safe (used for lazy action detection). */
    public static boolean canInline(CompileTask task, int position) {
        return plan(task, position) != null;
    }

    private static TextEdit[] plan(CompileTask task, int position) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();

        var call = callAt(root, pos, position);
        if (call == null) return null;
        var callPath = trees.getPath(root, call);
        if (callPath.getParentPath() != null
                && callPath.getParentPath().getLeaf() instanceof ExpressionStatementTree) {
            return null; // value discarded as a statement; replacing yields invalid syntax
        }
        var callee = trees.getElement(callPath);
        if (!(callee instanceof javax.lang.model.element.ExecutableElement)) return null;
        var target = (javax.lang.model.element.ExecutableElement) callee;
        if (target.isVarArgs()) return null;

        var decl = findMethod(root, trees, target);
        if (decl == null || decl.getBody() == null) return null;
        var body = decl.getBody().getStatements();
        if (body.size() != 1 || !(body.get(0) instanceof ReturnTree)) return null;
        var returned = ((ReturnTree) body.get(0)).getExpression();
        if (returned == null) return null;

        var params = new LinkedHashMap<Element, Integer>();
        for (var i = 0; i < decl.getParameters().size(); i++) {
            var pe = trees.getElement(trees.getPath(root, decl.getParameters().get(i)));
            if (pe == null) return null;
            params.put(pe, i);
        }
        var args = call.getArguments();
        if (args.size() != params.size()) return null;
        for (var arg : args) if (!isPure(arg)) return null;

        // The body expression may reference only the method's own parameters.
        var returnedPath = trees.getPath(root, returned);
        if (returnedPath == null || !referencesOnlyParams(trees, returnedPath, params.keySet()))
            return null;

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var bodyStart = (int) pos.getStartPosition(root, returned);
        var bodyEnd = (int) pos.getEndPosition(root, returned);
        if (bodyStart < 0 || bodyEnd < 0) return null;

        // Argument source text (parenthesized) keyed by parameter index.
        var argText = new String[args.size()];
        for (var i = 0; i < args.size(); i++) {
            var a = args.get(i);
            var s = (int) pos.getStartPosition(root, a);
            var e = (int) pos.getEndPosition(root, a);
            argText[i] = "(" + contents.subSequence(s, e) + ")";
        }

        // Collect parameter identifier occurrences in the body expression (file offsets).
        var occurrences = new ArrayList<int[]>(); // {start, end, paramIndex}
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                var el = trees.getElement(getCurrentPath());
                var idx = params.get(el);
                if (idx != null) {
                    var s = (int) pos.getStartPosition(root, t);
                    var e = (int) pos.getEndPosition(root, t);
                    if (s >= 0 && e >= 0) occurrences.add(new int[] {s, e, idx});
                }
                return super.visitIdentifier(t, p);
            }
        }.scan(returnedPath, null);

        var out = new StringBuilder(contents.subSequence(bodyStart, bodyEnd));
        occurrences.sort((x, y) -> Integer.compare(y[0], x[0])); // rightmost first
        for (var occ : occurrences) {
            out.replace(occ[0] - bodyStart, occ[1] - bodyStart, argText[occ[2]]);
        }
        var replacement = "(" + out + ")";

        var lines = root.getLineMap();
        var callStart = (int) pos.getStartPosition(root, call);
        var callEnd = (int) pos.getEndPosition(root, call);
        var range =
                new Range(
                        new Position(
                                (int) lines.getLineNumber(callStart) - 1,
                                (int) lines.getColumnNumber(callStart) - 1),
                        new Position(
                                (int) lines.getLineNumber(callEnd) - 1,
                                (int) lines.getColumnNumber(callEnd) - 1));
        return new TextEdit[] {new TextEdit(range, replacement)};
    }

    /** The innermost method invocation whose source range contains {@code position}, or null. */
    private static MethodInvocationTree callAt(
            CompilationUnitTree root, SourcePositions pos, int position) {
        var result = new MethodInvocationTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                var s = pos.getStartPosition(root, t);
                var e = pos.getEndPosition(root, t);
                if (s >= 0 && s <= position && position <= e && (e - s) < best[0]) {
                    best[0] = e - s;
                    result[0] = t;
                }
                return super.visitMethodInvocation(t, p);
            }
        }.scan(root, null);
        return result[0];
    }

    /** The declaration of {@code target} within the same compilation unit, or null. */
    private static MethodTree findMethod(
            CompilationUnitTree root,
            Trees trees,
            javax.lang.model.element.ExecutableElement target) {
        var result = new MethodTree[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                if (target.equals(trees.getElement(getCurrentPath()))) result[0] = t;
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        return result[0];
    }

    /** Whether every name in {@code expr} is one of {@code params}, with no calls/allocations. */
    private static boolean referencesOnlyParams(
            Trees trees, TreePath exprPath, java.util.Set<Element> params) {
        var ok = new boolean[] {true};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                ok[0] = false;
                return null;
            }

            @Override
            public Void visitNewClass(NewClassTree t, Void p) {
                ok[0] = false;
                return null;
            }

            @Override
            public Void visitNewArray(NewArrayTree t, Void p) {
                ok[0] = false;
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void p) {
                ok[0] = false;
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                if (!params.contains(trees.getElement(getCurrentPath()))) ok[0] = false;
                return super.visitIdentifier(t, p);
            }
        }.scan(exprPath, null);
        return ok[0];
    }

    private static boolean isPure(Tree expr) {
        var impure = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                impure[0] = true;
                return super.visitMethodInvocation(t, p);
            }

            @Override
            public Void visitNewClass(NewClassTree t, Void p) {
                impure[0] = true;
                return super.visitNewClass(t, p);
            }

            @Override
            public Void visitNewArray(NewArrayTree t, Void p) {
                impure[0] = true;
                return super.visitNewArray(t, p);
            }

            @Override
            public Void visitAssignment(AssignmentTree t, Void p) {
                impure[0] = true;
                return super.visitAssignment(t, p);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree t, Void p) {
                impure[0] = true;
                return super.visitCompoundAssignment(t, p);
            }

            @Override
            public Void visitUnary(UnaryTree t, Void p) {
                switch (t.getKind()) {
                    case PREFIX_INCREMENT:
                    case PREFIX_DECREMENT:
                    case POSTFIX_INCREMENT:
                    case POSTFIX_DECREMENT:
                        impure[0] = true;
                        break;
                    default:
                }
                return super.visitUnary(t, p);
            }
        }.scan(expr, null);
        return !impure[0];
    }
}
