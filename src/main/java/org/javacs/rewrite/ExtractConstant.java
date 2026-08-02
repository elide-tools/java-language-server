package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Extract the expression selected by [start, end) into a {@code private static final} field of the
 * enclosing class and replace the selection with the field name.
 *
 * <p>Only performed when the expression is legal in a static initializer: it references no local
 * variables, parameters, {@code this}/{@code super}, or non-static members. Under that condition the
 * expression denotes the same value in a static field initializer as at its original site, so the
 * extraction preserves behavior. Otherwise the rewrite is CANCELLED.
 */
public class ExtractConstant implements Rewrite {
    final Path file;
    final int start, end;

    public ExtractConstant(Path file, int start, int end) {
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

    /** Whether extracting the selection into a constant is safe (used for lazy action detection). */
    public static boolean canExtract(CompileTask task, int start, int end) {
        return plan(task, start, end) != null;
    }

    private static final String NAME = "EXTRACTED_CONSTANT";

    private static TextEdit[] plan(CompileTask task, int start, int end) {
        if (end <= start) return null;
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var expr = findExpression(root, pos, start, end);
        if (expr == null) return null;
        var exprPath = trees.getPath(root, expr);
        if (exprPath == null) return null;
        // Must be a value-producing expression.
        var type = trees.getTypeMirror(exprPath);
        if (type == null) return null;
        switch (type.getKind()) {
            case VOID:
            case NONE:
            case ERROR:
            case PACKAGE:
            case EXECUTABLE:
            case OTHER:
                return null;
            default:
                break;
        }
        // Must sit inside a class and be legal in a static initializer.
        var enclosing = enclosingClass(exprPath);
        if (enclosing == null) return null;
        if (!isStaticSafe(trees, root, exprPath, start, end)) return null;
        var firstMember = firstRealMember(root, pos, enclosing);
        if (firstMember == null) return null;

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var exprText = contents.subSequence(start, end).toString();
        var lines = root.getLineMap();
        var memberStart = pos.getStartPosition(root, firstMember);
        var memberLine = (int) lines.getLineNumber(memberStart);
        var memberColumn = (int) lines.getColumnNumber(memberStart);
        var indent = " ".repeat(memberColumn - 1);
        var declaration =
                "private static final " + typeString(type) + " " + NAME + " = " + exprText + ";\n" + indent;
        var insertPos = new Position(memberLine - 1, memberColumn - 1);
        var insert = new TextEdit(new Range(insertPos, insertPos), declaration);
        var startPos = new Position((int) lines.getLineNumber(start) - 1, (int) lines.getColumnNumber(start) - 1);
        var endPos = new Position((int) lines.getLineNumber(end) - 1, (int) lines.getColumnNumber(end) - 1);
        var replace = new TextEdit(new Range(startPos, endPos), NAME);
        return new TextEdit[] {insert, replace};
    }

    /** The expression whose source range is exactly the selection, or null. */
    private static ExpressionTree findExpression(CompilationUnitTree root, SourcePositions pos, int start, int end) {
        var result = new ExpressionTree[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (result[0] == null && tree instanceof ExpressionTree) {
                    var s = pos.getStartPosition(root, tree);
                    var e = pos.getEndPosition(root, tree);
                    if (s == start && e == end) {
                        result[0] = (ExpressionTree) tree;
                    }
                }
                return super.scan(tree, p);
            }
        }.scan(root, null);
        return result[0];
    }

    /** The innermost class enclosing the expression, or null. */
    private static ClassTree enclosingClass(TreePath path) {
        for (var p = path; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof ClassTree) return (ClassTree) p.getLeaf();
        }
        return null;
    }

    /** The first non-synthetic member of the class (a valid field-insertion anchor), or null. */
    private static Tree firstRealMember(CompilationUnitTree root, SourcePositions pos, ClassTree cls) {
        for (var member : cls.getMembers()) {
            if (pos.getStartPosition(root, member) >= 0) return member;
        }
        return null;
    }

    /**
     * Whether every name referenced inside the selection is legal in a static initializer: no local
     * variables, parameters, {@code this}/{@code super}, or non-static fields/methods.
     */
    private static boolean isStaticSafe(Trees trees, CompilationUnitTree root, TreePath exprPath, int start, int end) {
        var pos = trees.getSourcePositions();
        var safe = new boolean[] {true};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                if (within(t)) check(t.getName().toString(), getCurrentPath());
                return super.visitIdentifier(t, p);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void p) {
                if (within(t)) {
                    var id = t.getIdentifier().toString();
                    if (id.equals("this") || id.equals("super")) safe[0] = false;
                }
                return super.visitMemberSelect(t, p);
            }

            private boolean within(Tree t) {
                var s = pos.getStartPosition(root, t);
                return s >= start && s < end;
            }

            private void check(String name, TreePath path) {
                if (name.equals("this") || name.equals("super")) {
                    safe[0] = false;
                    return;
                }
                var el = trees.getElement(path);
                if (el == null) return; // unresolved type name; leave to the compiler
                switch (el.getKind()) {
                    case LOCAL_VARIABLE:
                    case PARAMETER:
                    case EXCEPTION_PARAMETER:
                    case RESOURCE_VARIABLE:
                        safe[0] = false;
                        break;
                    case FIELD:
                    case METHOD:
                        if (!el.getModifiers().contains(Modifier.STATIC)) safe[0] = false;
                        break;
                    default:
                        break;
                }
            }
        }.scan(exprPath, null);
        return safe[0];
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
