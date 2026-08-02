package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import javax.lang.model.element.Modifier;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Change the access modifier of the method whose signature the cursor is on. {@code access} is one
 * of {@code "public"}, {@code "protected"}, {@code "private"}, or {@code ""} (package-private): an
 * existing access keyword is replaced (or removed for package-private), and a missing one is inserted
 * before the return type. CANCELLED when the cursor is not on a method signature or the method
 * already has the requested access.
 */
public class ChangeMethodAccess implements Rewrite {
    final Path file;
    final int position;
    final String access;

    public ChangeMethodAccess(Path file, int position, String access) {
        this.file = file;
        this.position = position;
        this.access = access;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var edits = plan(task, position, access);
            if (edits == null) return CANCELLED;
            return Map.of(file, edits);
        }
    }

    /** The method's current access ("public"/"protected"/"private"/""), or null if not on a signature. */
    public static String currentAccess(CompileTask task, int position) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var method = methodSignatureAt(root, pos, position);
        if (method == null) return null;
        return accessOf(method.getModifiers());
    }

    private static TextEdit[] plan(CompileTask task, int position, String access) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var method = methodSignatureAt(root, pos, position);
        if (method == null) return null;
        var modifiers = method.getModifiers();
        var current = accessOf(modifiers);
        if (current.equals(access)) return null;

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var lines = root.getLineMap();

        if (!current.isEmpty()) {
            var modStart = (int) pos.getStartPosition(root, modifiers);
            var modEnd = (int) pos.getEndPosition(root, modifiers);
            if (modStart < 0 || modEnd < 0) return null;
            var region = contents.subSequence(modStart, modEnd).toString();
            var idx = region.indexOf(current);
            if (idx < 0) return null;
            var kwStart = modStart + idx;
            var kwEnd = kwStart + current.length();
            if (access.isEmpty()) {
                while (kwEnd < contents.length() && contents.charAt(kwEnd) == ' ') kwEnd++;
                return new TextEdit[] {new TextEdit(range(lines, kwStart, kwEnd), "")};
            }
            return new TextEdit[] {new TextEdit(range(lines, kwStart, kwEnd), access)};
        }

        // package-private -> add a keyword before the return type (or the name, for a constructor)
        var ret = method.getReturnType();
        var anchor = ret != null ? (int) pos.getStartPosition(root, ret) : (int) pos.getStartPosition(root, method);
        if (anchor < 0) return null;
        return new TextEdit[] {new TextEdit(range(lines, anchor, anchor), access + " ")};
    }

    private static String accessOf(ModifiersTree modifiers) {
        var flags = modifiers.getFlags();
        if (flags.contains(Modifier.PUBLIC)) return "public";
        if (flags.contains(Modifier.PROTECTED)) return "protected";
        if (flags.contains(Modifier.PRIVATE)) return "private";
        return "";
    }

    /** The innermost method whose signature (declaration before its body) contains {@code position}. */
    private static MethodTree methodSignatureAt(CompilationUnitTree root, SourcePositions pos, int position) {
        var result = new MethodTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                var s = pos.getStartPosition(root, t);
                var e = pos.getEndPosition(root, t);
                var body = t.getBody();
                var headerEnd = body != null ? pos.getStartPosition(root, body) : e;
                if (s >= 0 && s <= position && position < headerEnd && (e - s) < best[0]) {
                    best[0] = e - s;
                    result[0] = t;
                }
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        return result[0];
    }

    private static Range range(com.sun.source.tree.LineMap lines, long start, long end) {
        return new Range(
                new Position((int) lines.getLineNumber(start) - 1, (int) lines.getColumnNumber(start) - 1),
                new Position((int) lines.getLineNumber(end) - 1, (int) lines.getColumnNumber(end) - 1));
    }
}
