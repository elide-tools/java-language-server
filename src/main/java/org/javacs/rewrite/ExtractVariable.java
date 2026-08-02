package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

/**
 * Introduce a local variable for the expression selected by [start, end). Inserts {@code var
 * extracted = <expr>;} before the enclosing block statement and replaces the selection with the new
 * name. The selection must span an expression exactly; otherwise the rewrite is CANCELLED.
 */
public class ExtractVariable implements Rewrite {
    final Path file;
    final int start, end;

    public ExtractVariable(Path file, int start, int end) {
        this.file = file;
        this.start = start;
        this.end = end;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();
            var root = task.root();
            var expr = findExpression(root, pos);
            if (expr == null) return CANCELLED;
            var statement = enclosingBlockStatement(trees.getPath(root, expr));
            if (statement == null) return CANCELLED;
            CharSequence contents;
            try {
                contents = root.getSourceFile().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var exprText = contents.subSequence(start, end).toString();
            var lines = root.getLineMap();
            var stmtStart = pos.getStartPosition(root, statement);
            var stmtLine = (int) lines.getLineNumber(stmtStart);
            var stmtColumn = (int) lines.getColumnNumber(stmtStart);
            var indent = " ".repeat(stmtColumn - 1);
            var name = "extracted";
            var declaration = "var " + name + " = " + exprText + ";\n" + indent;
            var insertPos = new Position(stmtLine - 1, stmtColumn - 1);
            var insert = new TextEdit(new Range(insertPos, insertPos), declaration);
            var startPos = new Position((int) lines.getLineNumber(start) - 1, (int) lines.getColumnNumber(start) - 1);
            var endPos = new Position((int) lines.getLineNumber(end) - 1, (int) lines.getColumnNumber(end) - 1);
            var replace = new TextEdit(new Range(startPos, endPos), name);
            TextEdit[] edits = {insert, replace};
            return Map.of(file, edits);
        }
    }

    /** The expression whose source range is exactly the selection, or null. */
    private ExpressionTree findExpression(CompilationUnitTree root, SourcePositions pos) {
        var result = new ExpressionTree[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (result[0] == null && tree instanceof ExpressionTree) {
                    var s = pos.getStartPosition(root, tree);
                    var e = pos.getEndPosition(root, tree);
                    if (s == start && e == end) {
                        result[0] = (ExpressionTree) tree;
                    }
                }
                return super.scan(tree, p);
            }
        }.scan(root, null);
        return result[0];
    }

    /** The statement that directly encloses the expression within a block (a valid insertion point). */
    private Tree enclosingBlockStatement(TreePath path) {
        for (var p = path; p != null && p.getParentPath() != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof StatementTree && p.getParentPath().getLeaf() instanceof BlockTree) {
                return p.getLeaf();
            }
        }
        return null;
    }
}
