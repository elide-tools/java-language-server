package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Create a field for an undefined name that is assigned to. Used as a quick fix for "cannot find
 * symbol" when the missing name is the left-hand side of an assignment ({@code name = expr} or
 * {@code this.name = expr}); the field's type is inferred from the assigned expression.
 *
 * <p>The field is inserted as the first member of the enclosing class, {@code static} when the
 * assignment is in a static context. CANCELLED when the position is not such an assignment target or
 * the assigned type cannot be inferred.
 */
public class CreateMissingField implements Rewrite {
    final Path file;
    final int position;

    public CreateMissingField(Path file, int position) {
        this.file = file;
        this.position = position;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();
            var root = task.root();

            var assign = assignmentTargetAt(root, pos, position);
            if (assign == null) return CANCELLED;
            var lhs = assign.getVariable();
            String name = fieldName(lhs);
            if (name == null) return CANCELLED;
            // Must be an undefined name, not an existing field/variable.
            var lhsEl = trees.getElement(trees.getPath(root, lhs));
            if (lhsEl != null
                    && (lhsEl.getKind() == ElementKind.FIELD
                            || lhsEl.getKind() == ElementKind.LOCAL_VARIABLE
                            || lhsEl.getKind() == ElementKind.PARAMETER)) {
                return CANCELLED;
            }
            var type = trees.getTypeMirror(trees.getPath(root, assign.getExpression()));
            if (type == null) return CANCELLED;
            switch (type.getKind()) {
                case ERROR:
                case NONE:
                case VOID:
                case NULL:
                case EXECUTABLE:
                case PACKAGE:
                case OTHER:
                    return CANCELLED;
                default:
                    break;
            }

            var assignPath = trees.getPath(root, assign);
            ClassTree cls = null;
            var isStatic = false;
            for (var p = assignPath; p != null; p = p.getParentPath()) {
                var leaf = p.getLeaf();
                if (leaf instanceof MethodTree && ((MethodTree) leaf).getModifiers().getFlags().contains(Modifier.STATIC)) {
                    isStatic = true;
                }
                if (leaf instanceof ClassTree) {
                    cls = (ClassTree) leaf;
                    break;
                }
            }
            if (cls == null) return CANCELLED;
            var anchor = firstRealMember(root, pos, cls);
            if (anchor == null) return CANCELLED;

            var lines = root.getLineMap();
            var memberStart = pos.getStartPosition(root, anchor);
            var memberLine = (int) lines.getLineNumber(memberStart);
            var memberColumn = (int) lines.getColumnNumber(memberStart);
            var indent = " ".repeat(memberColumn - 1);
            var declaration =
                    "private " + (isStatic ? "static " : "") + typeString(type) + " " + name + ";\n" + indent;
            var insertPos = new Position(memberLine - 1, memberColumn - 1);
            return Map.of(file, new TextEdit[] {new TextEdit(new Range(insertPos, insertPos), declaration)});
        }
    }

    private static String fieldName(ExpressionTree lhs) {
        if (lhs instanceof IdentifierTree) return ((IdentifierTree) lhs).getName().toString();
        if (lhs instanceof MemberSelectTree) {
            var ms = (MemberSelectTree) lhs;
            if (ms.getExpression() instanceof IdentifierTree
                    && ((IdentifierTree) ms.getExpression()).getName().contentEquals("this")) {
                return ms.getIdentifier().toString();
            }
        }
        return null;
    }

    /** The innermost assignment whose left-hand side contains {@code position}. */
    private static AssignmentTree assignmentTargetAt(CompilationUnitTree root, SourcePositions pos, int position) {
        var result = new AssignmentTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitAssignment(AssignmentTree t, Void p) {
                var v = t.getVariable();
                var s = pos.getStartPosition(root, v);
                var e = pos.getEndPosition(root, v);
                if (s >= 0 && s <= position && position <= e && (e - s) < best[0]) {
                    best[0] = e - s;
                    result[0] = t;
                }
                return super.visitAssignment(t, p);
            }
        }.scan(root, null);
        return result[0];
    }

    private static Tree firstRealMember(CompilationUnitTree root, SourcePositions pos, ClassTree cls) {
        for (var member : cls.getMembers()) {
            if (pos.getStartPosition(root, member) >= 0) return member;
        }
        return null;
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
