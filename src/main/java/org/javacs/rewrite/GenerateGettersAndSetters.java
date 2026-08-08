package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;

import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Modifier;

/** Generate a getter and setter for every non-static field of a class. */
public class GenerateGettersAndSetters implements Rewrite {
    final Path file;
    final String simpleName;

    public GenerateGettersAndSetters(Path file, String simpleName) {
        this.file = file;
        this.simpleName = simpleName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var typeTree = GenerateConstructor.findClass(task, simpleName);
            if (typeTree == null) return CANCELLED;
            var fields = fields(typeTree);
            if (fields.isEmpty()) return CANCELLED;
            var buf = new StringBuilder();
            for (var field : fields) {
                var type = extract(task, field.getType());
                var name = field.getName().toString();
                var cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                buf.append("\n");
                buf.append("public ").append(type).append(" get").append(cap).append("() {\n");
                buf.append("    return ").append(name).append(";\n");
                buf.append("}\n");
                buf.append("\n");
                buf.append("public void set")
                        .append(cap)
                        .append("(")
                        .append(type)
                        .append(" ")
                        .append(name)
                        .append(") {\n");
                buf.append("    this.").append(name).append(" = ").append(name).append(";\n");
                buf.append("}\n");
            }
            var indent = EditHelper.indent(task.task, task.root(), typeTree) + 4;
            var string =
                    buf.toString().replaceAll("\n", "\n" + " ".repeat(indent)).stripTrailing()
                            + "\n";
            var insert = EditHelper.insertAtEndOfClass(task.task, task.root(), typeTree);
            TextEdit[] edits = {new TextEdit(new Range(insert, insert), string)};
            return Map.of(file, edits);
        }
    }

    private List<VariableTree> fields(ClassTree typeTree) {
        var fields = new ArrayList<VariableTree>();
        for (var member : typeTree.getMembers()) {
            if (!(member instanceof VariableTree)) continue;
            var field = (VariableTree) member;
            if (field.getModifiers().getFlags().contains(Modifier.STATIC)) continue;
            fields.add(field);
        }
        return fields;
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
}
