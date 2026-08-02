package org.javacs.markup;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Name;
import org.javacs.CompilerProvider;
import org.javacs.FileStore;

/**
 * `textDocument/semanticTokens/full`: classify every identifier/member-select/
 * declaration name by its resolved element kind and emit LSP delta-encoded
 * semantic tokens. Pure AST — native-image safe. Richer than the legacy
 * `java/colors` notification (which only reported static + field references).
 */
public class SemanticTokensProvider {
    /** Legend, in index order; mirrored into the server capability. */
    public static final List<String> TOKEN_TYPES =
            List.of("type", "typeParameter", "parameter", "variable", "property", "enumMember", "method");

    private static final int TYPE = 0, TYPE_PARAMETER = 1, PARAMETER = 2, VARIABLE = 3, PROPERTY = 4, ENUM_MEMBER = 5,
            METHOD = 6;

    private final CompilerProvider compiler;

    public SemanticTokensProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public int[] tokens(Path file) {
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var raw = new ArrayList<int[]>(); // {line, startChar, length, tokenType}
            new Tokenizer(task.task, file).scan(root, raw);
            raw.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            var data = new int[raw.size() * 5];
            var i = 0;
            var prevLine = 0;
            var prevChar = 0;
            for (var t : raw) {
                var deltaLine = t[0] - prevLine;
                var deltaChar = deltaLine == 0 ? t[1] - prevChar : t[1];
                data[i++] = deltaLine;
                data[i++] = deltaChar;
                data[i++] = t[2];
                data[i++] = t[3];
                data[i++] = 0; // no modifiers
                prevLine = t[0];
                prevChar = t[1];
            }
            return data;
        }
    }

    private static int tokenType(ElementKind kind) {
        switch (kind) {
            case CLASS:
            case RECORD:
            case INTERFACE:
            case ENUM:
            case ANNOTATION_TYPE:
                return TYPE;
            case TYPE_PARAMETER:
                return TYPE_PARAMETER;
            case PARAMETER:
                return PARAMETER;
            case LOCAL_VARIABLE:
            case EXCEPTION_PARAMETER:
            case RESOURCE_VARIABLE:
            case BINDING_VARIABLE:
                return VARIABLE;
            case FIELD:
                return PROPERTY;
            case ENUM_CONSTANT:
                return ENUM_MEMBER;
            case METHOD:
            case CONSTRUCTOR:
                return METHOD;
            default:
                return -1;
        }
    }

    private static class Tokenizer extends TreePathScanner<Void, List<int[]>> {
        private final Trees trees;
        private final String contents;

        Tokenizer(JavacTask task, Path file) {
            this.trees = Trees.instance(task);
            this.contents = FileStore.contents(file);
        }

        @Override
        public Void visitIdentifier(IdentifierTree t, List<int[]> acc) {
            emit(t.getName(), acc);
            return super.visitIdentifier(t, acc);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree t, List<int[]> acc) {
            emit(t.getIdentifier(), acc);
            return super.visitMemberSelect(t, acc);
        }

        @Override
        public Void visitVariable(VariableTree t, List<int[]> acc) {
            emit(t.getName(), acc);
            return super.visitVariable(t, acc);
        }

        private void emit(Name name, List<int[]> acc) {
            if (name.contentEquals("this") || name.contentEquals("super") || name.contentEquals("class")) return;
            var path = getCurrentPath();
            var element = trees.getElement(path);
            if (element == null) return;
            var type = tokenType(element.getKind());
            if (type < 0) return;

            var pos = trees.getSourcePositions();
            var root = path.getCompilationUnit();
            var leaf = path.getLeaf();
            var start = (int) pos.getStartPosition(root, leaf);
            var end = (int) pos.getEndPosition(root, leaf);
            if (leaf instanceof MemberSelectTree) {
                start = (int) pos.getEndPosition(root, ((MemberSelectTree) leaf).getExpression());
            } else if (leaf instanceof VariableTree) {
                start = (int) pos.getEndPosition(root, ((VariableTree) leaf).getType());
            }
            if (start < 0 || end < 0 || end > contents.length()) return;
            var region = contents.substring(start, end);
            var offset = region.indexOf(name.toString());
            if (offset < 0) return;
            var nameStart = start + offset;
            var lines = root.getLineMap();
            var line = (int) lines.getLineNumber(nameStart) - 1;
            var character = (int) lines.getColumnNumber(nameStart) - 1;
            acc.add(new int[] {line, character, name.length(), type});
        }
    }
}
