package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Remove the unused parameter the cursor is on from a private method, dropping the corresponding
 * argument at every same-file call site.
 *
 * <p>Bounded for correctness: the method is private (so all calls are in this file) and not
 * overloaded (removing a parameter cannot silently collide with another signature), and the
 * parameter is never referenced in the body (so dropping it changes no behavior). CANCELLED
 * otherwise.
 */
public class RemoveParameter implements Rewrite {
    final Path file;
    final int position;

    public RemoveParameter(Path file, int position) {
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

    /** Whether the unused parameter at {@code position} can be removed (used for lazy detection). */
    public static boolean canRemove(CompileTask task, int position) {
        return plan(task, position) != null;
    }

    private static TextEdit[] plan(CompileTask task, int position) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();

        var method = methodWithParamAt(root, pos, position);
        if (method == null) return null;
        if (method.getName().contentEquals("<init>")) return null;
        if (!method.getModifiers().getFlags().contains(Modifier.PRIVATE)) return null;

        var params = method.getParameters();
        var index = -1;
        for (var i = 0; i < params.size(); i++) {
            var s = pos.getStartPosition(root, params.get(i));
            var e = pos.getEndPosition(root, params.get(i));
            if (s <= position && position <= e) {
                index = i;
                break;
            }
        }
        if (index < 0) return null;

        // No overload with the same name (removing a parameter must not collide).
        var name = method.getName().toString();
        var sameName = new int[] {0};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                if (t.getName().contentEquals(name)) sameName[0]++;
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        if (sameName[0] != 1) return null;

        // The parameter must be unused in the body.
        var paramEl = trees.getElement(trees.getPath(root, params.get(index)));
        if (paramEl == null) return null;
        var used = new boolean[] {false};
        if (method.getBody() != null) {
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitIdentifier(IdentifierTree t, Void p) {
                    if (paramEl.equals(trees.getElement(getCurrentPath()))) used[0] = true;
                    return super.visitIdentifier(t, p);
                }
            }.scan(trees.getPath(root, method.getBody()), null);
        }
        if (used[0]) return null;

        var methodEl = trees.getElement(trees.getPath(root, method));
        if (methodEl == null) return null;

        var lines = root.getLineMap();
        var edits = new ArrayList<TextEdit>();
        edits.add(removal(lines, pos, root, params, index));

        // Drop the argument at every same-file call site.
        var fi = index;
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                if (methodEl.equals(trees.getElement(getCurrentPath())) && t.getArguments().size() == params.size()) {
                    edits.add(removal(lines, pos, root, t.getArguments(), fi));
                }
                return super.visitMethodInvocation(t, p);
            }
        }.scan(root, null);

        return edits.toArray(new TextEdit[0]);
    }

    /** A deletion of item {@code index} from a comma-separated list, taking an adjacent comma with it. */
    private static TextEdit removal(
            com.sun.source.tree.LineMap lines,
            SourcePositions pos,
            CompilationUnitTree root,
            java.util.List<? extends Tree> items,
            int index) {
        long delStart;
        long delEnd;
        if (items.size() == 1) {
            delStart = pos.getStartPosition(root, items.get(0));
            delEnd = pos.getEndPosition(root, items.get(0));
        } else if (index == 0) {
            delStart = pos.getStartPosition(root, items.get(0));
            delEnd = pos.getStartPosition(root, items.get(1));
        } else {
            delStart = pos.getEndPosition(root, items.get(index - 1));
            delEnd = pos.getEndPosition(root, items.get(index));
        }
        return new TextEdit(
                new Range(
                        new Position((int) lines.getLineNumber(delStart) - 1, (int) lines.getColumnNumber(delStart) - 1),
                        new Position((int) lines.getLineNumber(delEnd) - 1, (int) lines.getColumnNumber(delEnd) - 1)),
                "");
    }

    /** The innermost method that declares a parameter whose span contains {@code position}. */
    private static MethodTree methodWithParamAt(CompilationUnitTree root, SourcePositions pos, int position) {
        var result = new MethodTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                for (var param : t.getParameters()) {
                    var s = pos.getStartPosition(root, param);
                    var e = pos.getEndPosition(root, param);
                    if (s >= 0 && s <= position && position <= e) {
                        var ms = pos.getStartPosition(root, t);
                        var me = pos.getEndPosition(root, t);
                        if ((me - ms) < best[0]) {
                            best[0] = me - ms;
                            result[0] = t;
                        }
                    }
                }
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        return result[0];
    }
}
