package org.javacs.navigation;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.DocumentHighlight;
import org.javacs.lsp.DocumentHighlightKind;

/**
 * `textDocument/documentHighlight`: highlight every occurrence of the symbol
 * under the cursor within the current file, classified read vs write. Reuses
 * {@link FindReferences} scoped to the single compilation unit.
 */
public class DocumentHighlightProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<DocumentHighlight> NOT_SUPPORTED = List.of();

    public DocumentHighlightProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<DocumentHighlight> find() {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            var root = task.root(file);
            var paths = new ArrayList<TreePath>();
            new FindReferences(task.task, element).scan(root, paths);
            var highlights = new ArrayList<DocumentHighlight>();
            for (var path : paths) {
                var location = FindHelper.location(task, path);
                var highlight = new DocumentHighlight();
                highlight.range = location.range;
                highlight.kind = classify(path);
                highlights.add(highlight);
            }
            return highlights;
        }
    }

    private int classify(TreePath path) {
        var parent = path.getParentPath();
        if (parent == null) return DocumentHighlightKind.Read;
        Tree leaf = path.getLeaf();
        Tree up = parent.getLeaf();
        if (up instanceof AssignmentTree && ((AssignmentTree) up).getVariable() == leaf) {
            return DocumentHighlightKind.Write;
        }
        if (up instanceof CompoundAssignmentTree && ((CompoundAssignmentTree) up).getVariable() == leaf) {
            return DocumentHighlightKind.Write;
        }
        if (up instanceof UnaryTree) {
            switch (up.getKind()) {
                case PREFIX_INCREMENT:
                case POSTFIX_INCREMENT:
                case PREFIX_DECREMENT:
                case POSTFIX_DECREMENT:
                    return DocumentHighlightKind.Write;
                default:
                    return DocumentHighlightKind.Read;
            }
        }
        return DocumentHighlightKind.Read;
    }
}
