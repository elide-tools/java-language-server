package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Extract the consecutive statements selected by [start, end) into a new private method and replace
 * them with a call.
 *
 * <p>Parameters are the local variables and parameters read inside the selection but declared
 * outside it. A single value may be returned: a variable declared inside the selection (or an outer
 * variable assigned inside) that is still read after the selection becomes the method's return
 * value. The selection is CANCELLED when it does not cover whole statements of one block, when more
 * than one value would need to be returned, or when it contains control flow that escapes the
 * selection ({@code return}, {@code yield}, or a {@code break}/{@code continue} whose target lies
 * outside the selection) — extracting those would change behavior.
 */
public class ExtractMethod implements Rewrite {
    final Path file;
    final int start, end;

    public ExtractMethod(Path file, int start, int end) {
        this.file = file;
        this.start = start;
        this.end = end;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var edits = plan(task, start, end);
            if (edits == null) return CANCELLED;
            return Map.of(file, edits);
        }
    }

    /** Whether extracting the selected statements is safe (used for lazy action detection). */
    public static boolean canExtract(CompileTask task, int start, int end) {
        return plan(task, start, end) != null;
    }

    private static final String NAME = "extracted";

    private static TextEdit[] plan(CompileTask task, int start, int end) {
        if (end <= start) return null;
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var method = enclosingMethod(root, pos, start, end);
        if (method == null || method.getBody() == null) return null;

        var selected = selectedStatements(root, pos, start, end);
        if (selected == null || selected.isEmpty()) return null;
        if (!controlFlowSafe(selected)) return null;

        var firstOffset = pos.getStartPosition(root, selected.get(0));
        var lastOffset = pos.getEndPosition(root, selected.get(selected.size() - 1));
        if (firstOffset < 0 || lastOffset < 0) return null;

        var declaredInSel = new HashSet<Element>();
        var freeVars = new LinkedHashSet<Element>();
        var assignedInSel = new HashSet<Element>();
        var usedAfter = new HashSet<Element>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree t, Void p) {
                var s = pos.getStartPosition(root, t);
                if (s >= firstOffset && s < lastOffset) {
                    var el = trees.getElement(getCurrentPath());
                    if (el != null) declaredInSel.add(el);
                }
                return super.visitVariable(t, p);
            }

            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                var el = trees.getElement(getCurrentPath());
                if (isLocalOrParam(el)) {
                    var s = pos.getStartPosition(root, t);
                    if (s >= firstOffset && s < lastOffset) {
                        if (!declaredInSel.contains(el)) freeVars.add(el);
                    } else if (s >= lastOffset) {
                        usedAfter.add(el);
                    }
                }
                return super.visitIdentifier(t, p);
            }

            @Override
            public Void visitAssignment(AssignmentTree t, Void p) {
                markWrite(t.getVariable());
                return super.visitAssignment(t, p);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree t, Void p) {
                markWrite(t.getVariable());
                return super.visitCompoundAssignment(t, p);
            }

            @Override
            public Void visitUnary(UnaryTree t, Void p) {
                switch (t.getKind()) {
                    case PREFIX_INCREMENT:
                    case PREFIX_DECREMENT:
                    case POSTFIX_INCREMENT:
                    case POSTFIX_DECREMENT:
                        markWrite(t.getExpression());
                        break;
                    default:
                        break;
                }
                return super.visitUnary(t, p);
            }

            private void markWrite(ExpressionTree lhs) {
                var s = pos.getStartPosition(root, lhs);
                if (s >= firstOffset && s < lastOffset && lhs instanceof IdentifierTree) {
                    var el = trees.getElement(trees.getPath(root, lhs));
                    if (isLocalOrParam(el)) assignedInSel.add(el);
                }
            }
        }.scan(trees.getPath(root, method), null);

        var outVars = new LinkedHashSet<Element>();
        for (var el : declaredInSel) if (usedAfter.contains(el)) outVars.add(el);
        for (var el : assignedInSel) if (!declaredInSel.contains(el) && usedAfter.contains(el)) outVars.add(el);
        if (outVars.size() > 1) return null;
        var outVar = outVars.isEmpty() ? null : outVars.iterator().next();

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var rawSelected = contents.subSequence((int) firstOffset, (int) lastOffset).toString();
        var isStatic = method.getModifiers().getFlags().contains(Modifier.STATIC);
        var lines = root.getLineMap();
        var methodStart = pos.getStartPosition(root, method);
        var methodColumn = (int) lines.getColumnNumber(methodStart);
        var methodIndent = " ".repeat(methodColumn - 1);
        var bodyIndent = methodIndent + "    ";

        var params = new StringBuilder();
        var args = new StringBuilder();
        for (var el : freeVars) {
            if (params.length() > 0) {
                params.append(", ");
                args.append(", ");
            }
            params.append(typeString(el.asType())).append(" ").append(el.getSimpleName());
            args.append(el.getSimpleName());
        }
        var returnType = outVar == null ? "void" : typeString(outVar.asType());

        var m = new StringBuilder();
        m.append("private ");
        if (isStatic) m.append("static ");
        m.append(returnType).append(" ").append(NAME).append("(").append(params).append(") {\n");
        m.append(bodyIndent).append(rawSelected);
        if (outVar != null) {
            m.append("\n").append(bodyIndent).append("return ").append(outVar.getSimpleName()).append(";");
        }
        m.append("\n").append(methodIndent).append("}");

        String call;
        if (outVar == null) {
            call = NAME + "(" + args + ");";
        } else if (declaredInSel.contains(outVar)) {
            call = typeString(outVar.asType()) + " " + outVar.getSimpleName() + " = " + NAME + "(" + args + ");";
        } else {
            call = outVar.getSimpleName() + " = " + NAME + "(" + args + ");";
        }

        var replace = new TextEdit(new Range(offset(lines, firstOffset), offset(lines, lastOffset)), call);
        var insertOffset = pos.getEndPosition(root, method);
        var insertPos = offset(lines, insertOffset);
        var insert = new TextEdit(new Range(insertPos, insertPos), "\n\n" + methodIndent + m);
        return new TextEdit[] {insert, replace};
    }

    private static Position offset(com.sun.source.tree.LineMap lines, long o) {
        return new Position((int) lines.getLineNumber(o) - 1, (int) lines.getColumnNumber(o) - 1);
    }

    private static boolean isLocalOrParam(Element el) {
        if (el == null) return false;
        switch (el.getKind()) {
            case LOCAL_VARIABLE:
            case PARAMETER:
            case EXCEPTION_PARAMETER:
            case RESOURCE_VARIABLE:
                return true;
            default:
                return false;
        }
    }

    /** The innermost method whose body span contains the selection, or null. */
    private static MethodTree enclosingMethod(CompilationUnitTree root, SourcePositions pos, int start, int end) {
        var result = new MethodTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                var body = t.getBody();
                if (body != null) {
                    var s = pos.getStartPosition(root, body);
                    var e = pos.getEndPosition(root, body);
                    if (s >= 0 && s <= start && end <= e && (e - s) < best[0]) {
                        best[0] = e - s;
                        result[0] = t;
                    }
                }
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        return result[0];
    }

    /** The whole statements of one block covered exactly by the selection, or null. */
    private static List<StatementTree> selectedStatements(
            CompilationUnitTree root, SourcePositions pos, int start, int end) {
        var chosen = new ArrayList<StatementTree>();
        var bestStart = new long[] {-1};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBlock(BlockTree b, Void p) {
                var sel = new ArrayList<StatementTree>();
                var partial = false;
                for (var st : b.getStatements()) {
                    var s = pos.getStartPosition(root, st);
                    var e = pos.getEndPosition(root, st);
                    if (s < 0 || e < 0) continue;
                    var inside = s >= start && e <= end;
                    var intersects = s < end && e > start;
                    if (inside) sel.add(st);
                    else if (intersects) partial = true;
                }
                if (!partial && !sel.isEmpty()) {
                    var bs = pos.getStartPosition(root, sel.get(0));
                    if (bs > bestStart[0]) {
                        bestStart[0] = bs;
                        chosen.clear();
                        chosen.addAll(sel);
                    }
                }
                return super.visitBlock(b, p);
            }
        }.scan(root, null);
        return chosen.isEmpty() ? null : chosen;
    }

    /**
     * Whether the selection contains no control flow that would escape the extracted method: no
     * {@code return}/{@code yield}, and every {@code break}/{@code continue} targets a loop/switch
     * that is itself inside the selection (labeled jumps are rejected conservatively).
     */
    private static boolean controlFlowSafe(List<StatementTree> selected) {
        var ok = new boolean[] {true};
        var loop = new int[] {0};
        var sw = new int[] {0};
        var scanner =
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitWhileLoop(WhileLoopTree t, Void p) {
                        loop[0]++;
                        super.visitWhileLoop(t, p);
                        loop[0]--;
                        return null;
                    }

                    @Override
                    public Void visitForLoop(ForLoopTree t, Void p) {
                        loop[0]++;
                        super.visitForLoop(t, p);
                        loop[0]--;
                        return null;
                    }

                    @Override
                    public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void p) {
                        loop[0]++;
                        super.visitEnhancedForLoop(t, p);
                        loop[0]--;
                        return null;
                    }

                    @Override
                    public Void visitDoWhileLoop(DoWhileLoopTree t, Void p) {
                        loop[0]++;
                        super.visitDoWhileLoop(t, p);
                        loop[0]--;
                        return null;
                    }

                    @Override
                    public Void visitSwitch(SwitchTree t, Void p) {
                        sw[0]++;
                        super.visitSwitch(t, p);
                        sw[0]--;
                        return null;
                    }

                    @Override
                    public Void visitReturn(ReturnTree t, Void p) {
                        ok[0] = false;
                        return super.visitReturn(t, p);
                    }

                    @Override
                    public Void visitYield(YieldTree t, Void p) {
                        ok[0] = false;
                        return super.visitYield(t, p);
                    }

                    @Override
                    public Void visitBreak(BreakTree t, Void p) {
                        if (t.getLabel() != null || (loop[0] == 0 && sw[0] == 0)) ok[0] = false;
                        return super.visitBreak(t, p);
                    }

                    @Override
                    public Void visitContinue(ContinueTree t, Void p) {
                        if (t.getLabel() != null || loop[0] == 0) ok[0] = false;
                        return super.visitContinue(t, p);
                    }
                };
        for (var st : selected) scanner.scan(st, null);
        return ok[0];
    }

    private static String typeString(TypeMirror type) {
        if (type.getKind().isPrimitive()) return type.toString();
        var s = type.toString();
        if (s.startsWith("java.lang.") && s.indexOf('.', "java.lang.".length()) < 0) {
            return s.substring("java.lang.".length());
        }
        return s;
    }
}
