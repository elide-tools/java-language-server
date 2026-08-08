package org.javacs.navigation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import org.javacs.CompilerProvider;
import org.javacs.FindNameAt;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.SelectionRange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.tools.Diagnostic;

/**
 * `textDocument/selectionRange`: smart expand-selection. Walks the enclosing tree path from the
 * cursor outward, emitting a nested {@link SelectionRange} per ancestor with a real source span.
 * Pure AST — native-image safe.
 */
public class SelectionRangeProvider {
    private final CompilerProvider compiler;
    private final Path file;

    public SelectionRangeProvider(CompilerProvider compiler, Path file) {
        this.compiler = compiler;
        this.file = file;
    }

    public List<SelectionRange> find(List<Position> positions) {
        var result = new ArrayList<SelectionRange>();
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var lines = root.getLineMap();
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();
            for (var position : positions) {
                var cursor = lines.getPosition(position.line + 1, position.character + 1);
                var path = new FindNameAt(task).scan(root, cursor);
                result.add(buildChain(root, pos, lines, path));
            }
        }
        return result;
    }

    private SelectionRange buildChain(
            CompilationUnitTree root,
            com.sun.source.util.SourcePositions pos,
            com.sun.source.tree.LineMap lines,
            TreePath path) {
        var chain = new ArrayList<TreePath>();
        for (var p = path; p != null; p = p.getParentPath()) {
            chain.add(p);
        }
        // Build outermost -> innermost so each node's parent points one level out.
        SelectionRange parent = null;
        for (var i = chain.size() - 1; i >= 0; i--) {
            var range = rangeOf(root, pos, lines, chain.get(i));
            if (range == null) continue;
            var node = new SelectionRange(range);
            node.parent = parent;
            parent = node;
        }
        return parent;
    }

    private Range rangeOf(
            CompilationUnitTree root,
            com.sun.source.util.SourcePositions pos,
            com.sun.source.tree.LineMap lines,
            TreePath path) {
        var start = pos.getStartPosition(root, path.getLeaf());
        var end = pos.getEndPosition(root, path.getLeaf());
        if (start == Diagnostic.NOPOS || end == Diagnostic.NOPOS || end < start) return null;
        var startLine = (int) lines.getLineNumber(start);
        var startColumn = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endColumn = (int) lines.getColumnNumber(end);
        return new Range(
                new Position(startLine - 1, startColumn - 1),
                new Position(endLine - 1, endColumn - 1));
    }
}
