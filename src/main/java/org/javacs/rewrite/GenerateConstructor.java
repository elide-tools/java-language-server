package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import javax.lang.model.element.Modifier;

/**
 * Generate a constructor that initializes every uninitialized, non-static field of a class.
 * Companion to {@link GenerateRecordConstructor} (which only handles final fields). The class is
 * located by tree scan so unnamed-package types work.
 */
public class GenerateConstructor implements Rewrite {
    final Path file;
    final String simpleName;

    public GenerateConstructor(Path file, String simpleName) {
        this.file = file;
        this.simpleName = simpleName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var typeTree = findClass(task, simpleName);
            if (typeTree == null) return CANCELLED;
            var fields = fields(typeTree);
            if (fields.isEmpty()) return CANCELLED;
            var buf = new StringBuilder();
            buf.append("\n");
            if (typeTree.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                buf.append("public ");
            }
            buf.append(simpleName)
                    .append("(")
                    .append(parameters(task, fields))
                    .append(") {\n    ")
                    .append(initializers(fields))
                    .append("\n}");
            var indent = EditHelper.indent(task.task, task.root(), typeTree) + 4;
            var string = buf.toString().replaceAll("\n", "\n" + " ".repeat(indent)) + "\n\n";
            var insert = insertPoint(task, typeTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), string)};
            return Map.of(file, edits);
        }
    }

    static ClassTree findClass(CompileTask task, String simpleName) {
        var classes = new ArrayList<ClassTree>();
        new TreeScanner<Void, List<ClassTree>>() {
            @Override
            public Void visitClass(ClassTree t, List<ClassTree> acc) {
                acc.add(t);
                return super.visitClass(t, acc);
            }
        }.scan(task.root(), classes);
        for (var c : classes) {
            if (c.getSimpleName().contentEquals(simpleName)) return c;
        }
        return null;
    }

    private List<VariableTree> fields(ClassTree typeTree) {
        var fields = new ArrayList<VariableTree>();
        for (var member : typeTree.getMembers()) {
            if (!(member instanceof VariableTree)) continue;
            var field = (VariableTree) member;
            if (field.getInitializer() != null) continue;
            if (field.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
            fields.add(field);
        }
        return fields;
    }

    private String parameters(CompileTask task, List<VariableTree> fields) {
        var join = new StringJoiner(", ");
        for (var f : fields) {
            join.add(extract(task, f.getType()) + " " + f.getName());
        }
        return join.toString();
    }

    private String initializers(List<VariableTree> fields) {
        var join = new StringJoiner("\n    ");
        for (var f : fields) {
            join.add("this." + f.getName() + " = " + f.getName() + ";");
        }
        return join.toString();
    }

    private CharSequence extract(CompileTask task, Tree typeTree) {
        try {
            var contents = task.root().getSourceFile().getCharContent(true);
            var pos = Trees.instance(task.task).getSourcePositions();
            var start = (int) pos.getStartPosition(task.root(), typeTree);
            var end = (int) pos.getEndPosition(task.root(), typeTree);
            return contents.subSequence(start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Position insertPoint(CompileTask task, ClassTree typeTree) {
        for (var member : typeTree.getMembers()) {
            if (member.getKind() == Tree.Kind.METHOD) {
                var method = (MethodTree) member;
                if (method.getReturnType() == null) continue;
                return EditHelper.insertBefore(task.task, task.root(), method);
            }
        }
        return EditHelper.insertAtEndOfClass(task.task, task.root(), typeTree);
    }
}
