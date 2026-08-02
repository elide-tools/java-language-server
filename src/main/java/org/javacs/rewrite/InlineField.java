package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Inline a field: replace every reference with its initializer and delete the declaration.
 *
 * <p>Bounded to the provably-safe case: the field is {@code private static final} (so every use is
 * in this file and it is never reassigned), its initializer is side-effect-free, and every value the
 * initializer reads is itself a {@code static final} constant (or a type/literal). Under those
 * conditions the initializer denotes the same value at the declaration and at every use, so
 * substituting it preserves behavior. Otherwise the rewrite is CANCELLED.
 */
public class InlineField implements Rewrite {
    final Path file;
    final int position;

    public InlineField(Path file, int position) {
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

    /** Whether inlining the field at {@code position} is safe (used for lazy action detection). */
    public static boolean canInline(CompileTask task, int position) {
        return plan(task, position) != null;
    }

    private static TextEdit[] plan(CompileTask task, int position) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var decl = new FindVariableAt(task.task).scan(root, position);
        if (decl == null) return null;
        var initializer = decl.getInitializer();
        if (initializer == null) return null;
        var declPath = trees.getPath(root, decl);
        if (declPath == null) return null;
        var target = trees.getElement(declPath);
        if (target == null || target.getKind() != ElementKind.FIELD) return null;
        var mods = target.getModifiers();
        if (!(mods.contains(Modifier.PRIVATE) && mods.contains(Modifier.STATIC) && mods.contains(Modifier.FINAL))) {
            return null;
        }
        if (!isPure(initializer)) return null;

        // Every value the initializer reads must be a stable constant.
        var stable = new boolean[] {true};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                checkInput(t.getName().toString(), trees.getPath(root, t));
                return super.visitIdentifier(t, p);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void p) {
                var id = t.getIdentifier().toString();
                if (id.equals("this") || id.equals("super")) stable[0] = false;
                return super.visitMemberSelect(t, p);
            }

            private void checkInput(String name, TreePath path) {
                if (name.equals("this") || name.equals("super")) {
                    stable[0] = false;
                    return;
                }
                var el = trees.getElement(path);
                if (el == null) return;
                switch (el.getKind()) {
                    case FIELD:
                        if (!(el.getModifiers().contains(Modifier.STATIC)
                                && el.getModifiers().contains(Modifier.FINAL))) {
                            stable[0] = false;
                        }
                        break;
                    case ENUM_CONSTANT:
                        break;
                    case LOCAL_VARIABLE:
                    case PARAMETER:
                    case EXCEPTION_PARAMETER:
                    case RESOURCE_VARIABLE:
                        stable[0] = false;
                        break;
                    default:
                        // types, packages: stable
                }
            }
        }.scan(initializer, null);
        if (!stable[0]) return null;

        var refs = new ArrayList<Tree>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                if (target.equals(trees.getElement(getCurrentPath()))) refs.add(t);
                return super.visitIdentifier(t, p);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void p) {
                if (target.equals(trees.getElement(getCurrentPath()))) {
                    refs.add(t);
                    return null; // replace the whole qualified reference, not its receiver
                }
                return super.visitMemberSelect(t, p);
            }
        }.scan(root, null);

        var lines = root.getLineMap();
        var declStart = (int) pos.getStartPosition(root, decl);
        var declEnd = (int) pos.getEndPosition(root, decl);
        var declStartLine = (int) lines.getLineNumber(declStart);
        var declEndLine = (int) lines.getLineNumber(declEnd);
        for (var ref : refs) {
            var refLine = (int) lines.getLineNumber(pos.getStartPosition(root, ref));
            if (refLine >= declStartLine && refLine <= declEndLine) return null;
        }

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var initStart = (int) pos.getStartPosition(root, initializer);
        var initEnd = (int) pos.getEndPosition(root, initializer);
        var initText = contents.subSequence(initStart, initEnd).toString();
        var replacement = needsParens(initializer) ? "(" + initText + ")" : initText;

        var edits = new ArrayList<TextEdit>();
        for (var ref : refs) {
            var s = pos.getStartPosition(root, ref);
            var e = pos.getEndPosition(root, ref);
            var range =
                    new Range(
                            new Position((int) lines.getLineNumber(s) - 1, (int) lines.getColumnNumber(s) - 1),
                            new Position((int) lines.getLineNumber(e) - 1, (int) lines.getColumnNumber(e) - 1));
            edits.add(new TextEdit(range, replacement));
        }
        var delete = new Range(new Position(declStartLine - 1, 0), new Position(declEndLine, 0));
        edits.add(new TextEdit(delete, ""));
        return edits.toArray(new TextEdit[0]);
    }

    private static boolean needsParens(ExpressionTree e) {
        switch (e.getKind()) {
            case IDENTIFIER:
            case MEMBER_SELECT:
            case PARENTHESIZED:
            case ARRAY_ACCESS:
            case INT_LITERAL:
            case LONG_LITERAL:
            case FLOAT_LITERAL:
            case DOUBLE_LITERAL:
            case BOOLEAN_LITERAL:
            case CHAR_LITERAL:
            case STRING_LITERAL:
            case NULL_LITERAL:
                return false;
            default:
                return true;
        }
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
