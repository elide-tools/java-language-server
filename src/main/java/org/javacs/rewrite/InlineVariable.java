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
import java.util.HashSet;
import java.util.Map;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

/**
 * Inline a local variable: replace every reference with the initializer and delete the declaration.
 *
 * <p>Only performed when it is provably safe: the variable is never reassigned, the initializer is
 * side-effect-free (no calls, object/array creation, or assignments), and every value it reads is
 * effectively-final (an unreassigned local/parameter, or a {@code final} field). Under those
 * conditions the initializer denotes the same value at the declaration and at every use, so
 * substituting it any number of times preserves behavior. Otherwise the rewrite is CANCELLED.
 */
public class InlineVariable implements Rewrite {
    final Path file;
    final int position;

    public InlineVariable(Path file, int position) {
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

    /** Whether inlining the local at {@code position} is safe (used for lazy action detection). */
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
        if (target == null || target.getKind() != ElementKind.LOCAL_VARIABLE) return null;
        if (!isPure(initializer)) return null;

        var refs = new ArrayList<Tree>();
        var reassigned = new HashSet<Element>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                if (target.equals(trees.getElement(getCurrentPath()))) refs.add(t);
                return super.visitIdentifier(t, p);
            }

            @Override
            public Void visitAssignment(AssignmentTree t, Void p) {
                record(t.getVariable());
                return super.visitAssignment(t, p);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree t, Void p) {
                record(t.getVariable());
                return super.visitCompoundAssignment(t, p);
            }

            @Override
            public Void visitUnary(UnaryTree t, Void p) {
                switch (t.getKind()) {
                    case PREFIX_INCREMENT:
                    case PREFIX_DECREMENT:
                    case POSTFIX_INCREMENT:
                    case POSTFIX_DECREMENT:
                        record(t.getExpression());
                        break;
                    default:
                }
                return super.visitUnary(t, p);
            }

            void record(ExpressionTree lhs) {
                if (lhs instanceof IdentifierTree || lhs instanceof MemberSelectTree) {
                    var el = trees.getElement(new TreePath(getCurrentPath(), lhs));
                    if (el != null) reassigned.add(el);
                }
            }
        }.scan(root, null);

        if (reassigned.contains(target)) return null;

        // Every value the initializer reads must be stable across the variable's lifetime.
        var inputs = new ArrayList<Tree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree t, Void p) {
                inputs.add(t);
                return super.visitIdentifier(t, p);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void p) {
                inputs.add(t);
                return super.visitMemberSelect(t, p);
            }
        }.scan(initializer, null);
        for (var node : inputs) {
            var el = trees.getElement(trees.getPath(root, node));
            if (el == null) continue;
            switch (el.getKind()) {
                case LOCAL_VARIABLE:
                case PARAMETER:
                case EXCEPTION_PARAMETER:
                case RESOURCE_VARIABLE:
                case BINDING_VARIABLE:
                    if (reassigned.contains(el)) return null;
                    break;
                case FIELD:
                case ENUM_CONSTANT:
                    if (!el.getModifiers().contains(Modifier.FINAL)) return null;
                    break;
                default:
                    // types, packages: stable
            }
        }

        var lines = root.getLineMap();
        var declStart = (int) pos.getStartPosition(root, decl);
        var declEnd = (int) pos.getEndPosition(root, decl);
        var declStartLine = (int) lines.getLineNumber(declStart);
        var declEndLine = (int) lines.getLineNumber(declEnd);
        // A use sharing a line with the declaration would collide with the whole-line deletion.
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
                            new Position(
                                    (int) lines.getLineNumber(s) - 1,
                                    (int) lines.getColumnNumber(s) - 1),
                            new Position(
                                    (int) lines.getLineNumber(e) - 1,
                                    (int) lines.getColumnNumber(e) - 1));
            edits.add(new TextEdit(range, replacement));
        }
        // delete the declaration line(s) entirely
        var delete = new Range(new Position(declStartLine - 1, 0), new Position(declEndLine, 0));
        edits.add(new TextEdit(delete, ""));
        return edits.toArray(new TextEdit[0]);
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
}
