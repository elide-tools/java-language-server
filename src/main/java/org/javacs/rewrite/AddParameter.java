package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeMirror;
import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Promote an extra call argument into a real method parameter: at a call that passes one more
 * argument than the method declares, add a parameter whose type is inferred from that argument.
 *
 * <p>Bounded for correctness: the callee is a same-file private method with a single unambiguous
 * declaration (matched by name and arity) and exactly one call site in the file — the one being
 * fixed. Because that call already supplies the argument and no other call exists, adding the
 * parameter makes the file compile without changing any other site. CANCELLED otherwise.
 */
public class AddParameter implements Rewrite {
    final Path file;
    final int position;

    public AddParameter(Path file, int position) {
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

    /** Whether a parameter can be added for the extra argument at {@code position}. */
    public static boolean canAdd(CompileTask task, int position) {
        return plan(task, position) != null;
    }

    private static TextEdit[] plan(CompileTask task, int position) {
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();

        var call = callAt(root, pos, position);
        if (call == null) return null;
        var name = calledName(call);
        if (name == null) return null;
        var nArgs = call.getArguments().size();
        if (nArgs < 1) return null;

        // Exactly one call to this name in the file: the one we are fixing.
        var callCount = new int[] {0};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree t, Void p) {
                if (name.equals(calledName(t))) callCount[0]++;
                return super.visitMethodInvocation(t, p);
            }
        }.scan(root, null);
        if (callCount[0] != 1) return null;

        // Exactly one same-file private method with this name and (nArgs - 1) parameters.
        var candidates = new ArrayList<MethodTree>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree t, Void p) {
                if (t.getName().contentEquals(name)
                        && t.getParameters().size() == nArgs - 1
                        && t.getModifiers().getFlags().contains(Modifier.PRIVATE)) {
                    candidates.add(t);
                }
                return super.visitMethod(t, p);
            }
        }.scan(root, null);
        if (candidates.size() != 1) return null;
        var decl = candidates.get(0);

        var extra = call.getArguments().get(nArgs - 1);
        var type = trees.getTypeMirror(trees.getPath(root, extra));
        if (type == null) return null;
        switch (type.getKind()) {
            case ERROR:
            case NONE:
            case VOID:
            case NULL:
            case EXECUTABLE:
            case PACKAGE:
            case OTHER:
                return null;
            default:
                break;
        }

        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var typeStr = typeString(type);
        var paramName = paramName(decl, extra, nArgs);
        var lines = root.getLineMap();
        var params = decl.getParameters();
        if (!params.isEmpty()) {
            var at = (int) pos.getEndPosition(root, params.get(params.size() - 1));
            return new TextEdit[] {new TextEdit(range(lines, at, at), ", " + typeStr + " " + paramName)};
        }
        // No existing parameters: insert just after the '(' of the parameter list.
        var declStart = (int) pos.getStartPosition(root, decl);
        var body = decl.getBody();
        var limit = body != null ? (int) pos.getStartPosition(root, body) : (int) pos.getEndPosition(root, decl);
        var open = -1;
        for (var i = declStart; i < limit; i++) {
            if (contents.charAt(i) == '(') {
                open = i;
                break;
            }
        }
        if (open < 0) return null;
        var at = open + 1;
        return new TextEdit[] {new TextEdit(range(lines, at, at), typeStr + " " + paramName)};
    }

    private static String paramName(MethodTree decl, ExpressionTree extra, int nArgs) {
        String candidate = extra instanceof IdentifierTree ? ((IdentifierTree) extra).getName().toString() : null;
        if (candidate != null) {
            for (var param : decl.getParameters()) {
                if (param.getName().contentEquals(candidate)) {
                    candidate = null;
                    break;
                }
            }
        }
        return candidate != null ? candidate : "param" + nArgs;
    }

    private static String calledName(MethodInvocationTree call) {
        var select = call.getMethodSelect();
        if (select instanceof IdentifierTree) return ((IdentifierTree) select).getName().toString();
        if (select instanceof MemberSelectTree) return ((MemberSelectTree) select).getIdentifier().toString();
        return null;
    }

    /** The innermost method invocation whose source range contains {@code position}, or null. */
    private static MethodInvocationTree callAt(CompilationUnitTree root, SourcePositions pos, int position) {
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

    private static String typeString(TypeMirror type) {
        if (type.getKind().isPrimitive()) return type.toString();
        var s = type.toString();
        if (s.startsWith("java.lang.") && s.indexOf('.', "java.lang.".length()) < 0) {
            return s.substring("java.lang.".length());
        }
        return s;
    }

    private static Range range(com.sun.source.tree.LineMap lines, long start, long end) {
        return new Range(
                new Position((int) lines.getLineNumber(start) - 1, (int) lines.getColumnNumber(start) - 1),
                new Position((int) lines.getLineNumber(end) - 1, (int) lines.getColumnNumber(end) - 1));
    }
}
