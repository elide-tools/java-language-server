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
import java.util.Map;

import javax.lang.model.element.Modifier;

/**
 * Add a static {@code create(...)} factory method that delegates to the constructor the cursor is
 * on, and redirect {@code new ClassName(...)} expressions in the same file to {@code
 * ClassName.create(...)}.
 *
 * <p>Bounded for correctness and simple name resolution: the constructor's class must be a
 * top-level class with no type parameters and no existing {@code create} member, and only same-file
 * {@code new} calls are redirected (anonymous-class instantiations are left alone). The constructor
 * stays as-is, so instantiations in other files remain valid. CANCELLED otherwise.
 */
public class ReplaceConstructorWithFactoryMethod implements Rewrite {
    final Path file;
    final int position;

    public ReplaceConstructorWithFactoryMethod(Path file, int position) {
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

    /** Whether a factory can be generated for the constructor at {@code position}. */
    public static boolean canReplace(CompileTask task, int position) {
        return plan(task, position) != null;
    }

    private static final String FACTORY = "create";

    private static TextEdit[] plan(CompileTask task, int position) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var ctor = constructorAt(root, pos, position);
        if (ctor == null) return null;
        var ctorPath = trees.getPath(root, ctor);
        var target = trees.getElement(ctorPath);
        if (target == null) return null;

        // Enclosing class must be top-level, non-generic, and free of a `create` clash.
        if (!(ctorPath.getParentPath().getLeaf() instanceof ClassTree)) return null;
        var cls = (ClassTree) ctorPath.getParentPath().getLeaf();
        if (!(ctorPath.getParentPath().getParentPath().getLeaf() instanceof CompilationUnitTree))
            return null;
        if (!cls.getTypeParameters().isEmpty()) return null;
        var className = cls.getSimpleName().toString();
        for (var member : cls.getMembers()) {
            if (member instanceof MethodTree
                    && ((MethodTree) member).getName().contentEquals(FACTORY)) return null;
        }

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var lines = root.getLineMap();

        var params = new StringBuilder();
        var argNames = new StringBuilder();
        for (var param : ctor.getParameters()) {
            if (params.length() > 0) {
                params.append(", ");
                argNames.append(", ");
            }
            var s = (int) pos.getStartPosition(root, param);
            var e = (int) pos.getEndPosition(root, param);
            params.append(contents.subSequence(s, e));
            argNames.append(param.getName());
        }

        var access = accessOf(ctor.getModifiers());
        var prefix = access.isEmpty() ? "" : access + " ";
        var ctorStart = (int) pos.getStartPosition(root, ctor);
        var indent = " ".repeat((int) lines.getColumnNumber(ctorStart) - 1);
        var factory =
                "\n\n"
                        + indent
                        + prefix
                        + "static "
                        + className
                        + " "
                        + FACTORY
                        + "("
                        + params
                        + ") {\n"
                        + indent
                        + "    return new "
                        + className
                        + "("
                        + argNames
                        + ");\n"
                        + indent
                        + "}";

        var edits = new ArrayList<TextEdit>();
        var ctorEnd = pos.getEndPosition(root, ctor);
        edits.add(new TextEdit(range(lines, ctorEnd, ctorEnd), factory));

        // Redirect same-file `new ClassName(args)` (non-anonymous) to the factory.
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitNewClass(NewClassTree t, Void p) {
                if (t.getClassBody() == null && target.equals(trees.getElement(getCurrentPath()))) {
                    var s = (int) pos.getStartPosition(root, t);
                    var e = (int) pos.getEndPosition(root, t);
                    if (s >= 0 && e >= 0) {
                        var args = new StringBuilder();
                        for (var arg : t.getArguments()) {
                            if (args.length() > 0) args.append(", ");
                            var as = (int) pos.getStartPosition(root, arg);
                            var ae = (int) pos.getEndPosition(root, arg);
                            args.append(contents.subSequence(as, ae));
                        }
                        edits.add(
                                new TextEdit(
                                        range(lines, s, e),
                                        className + "." + FACTORY + "(" + args + ")"));
                    }
                }
                return super.visitNewClass(t, p);
            }
        }.scan(root, null);

        return edits.toArray(new TextEdit[0]);
    }

    private static String accessOf(ModifiersTree modifiers) {
        var flags = modifiers.getFlags();
        if (flags.contains(Modifier.PUBLIC)) return "public";
        if (flags.contains(Modifier.PROTECTED)) return "protected";
        if (flags.contains(Modifier.PRIVATE)) return "private";
        return "";
    }

    /** The innermost constructor whose signature contains {@code position}, or null. */
    private static MethodTree constructorAt(
            CompilationUnitTree root, SourcePositions pos, int position) {
        var result = new MethodTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                if (t.getName().contentEquals("<init>")) {
                    var s = pos.getStartPosition(root, t);
                    var e = pos.getEndPosition(root, t);
                    var body = t.getBody();
                    var headerEnd = body != null ? pos.getStartPosition(root, body) : e;
                    if (s >= 0 && s <= position && position < headerEnd && (e - s) < best[0]) {
                        best[0] = e - s;
                        result[0] = t;
                    }
                }
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        return result[0];
    }

    private static Range range(com.sun.source.tree.LineMap lines, long start, long end) {
        return new Range(
                new Position(
                        (int) lines.getLineNumber(start) - 1,
                        (int) lines.getColumnNumber(start) - 1),
                new Position(
                        (int) lines.getLineNumber(end) - 1, (int) lines.getColumnNumber(end) - 1));
    }
}
