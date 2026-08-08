package org.javacs.rewrite;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import org.javacs.CompilerProvider;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Surround the statement at {@code position} with a try/catch for {@code exceptionType}. Used as
 * the "catch" alternative to the "declare throws" quick fix for an unreported checked exception.
 *
 * <p>The enclosing block statement is wrapped; the generated handler rethrows wrapped in a {@code
 * RuntimeException} so the checked exception is handled without silently swallowing it. CANCELLED
 * when the position is not inside a block statement (e.g. a braceless {@code if} body).
 */
public class CatchException implements Rewrite {
    final Path file;
    final int position;
    final String exceptionType;

    public CatchException(Path file, int position, String exceptionType) {
        this.file = file;
        this.position = position;
        this.exceptionType = exceptionType;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        try (var task = compiler.compile(file)) {
            var trees = Trees.instance(task.task);
            var pos = trees.getSourcePositions();
            var root = task.root();
            var stmt = blockStatementAt(root, pos, position);
            if (stmt == null) return CANCELLED;
            var start = (int) pos.getStartPosition(root, stmt);
            var end = (int) pos.getEndPosition(root, stmt);
            if (start < 0 || end < 0) return CANCELLED;
            CharSequence contents;
            try {
                contents = root.getSourceFile().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var lines = root.getLineMap();
            var indent = " ".repeat((int) lines.getColumnNumber(start) - 1);
            var stmtText = contents.subSequence(start, end).toString();
            var replacement =
                    "try {\n"
                            + indent
                            + "    "
                            + stmtText
                            + "\n"
                            + indent
                            + "} catch ("
                            + exceptionType
                            + " e) {\n"
                            + indent
                            + "    throw new RuntimeException(e);\n"
                            + indent
                            + "}";
            var range =
                    new Range(
                            new Position(
                                    (int) lines.getLineNumber(start) - 1,
                                    (int) lines.getColumnNumber(start) - 1),
                            new Position(
                                    (int) lines.getLineNumber(end) - 1,
                                    (int) lines.getColumnNumber(end) - 1));
            return Map.of(file, new TextEdit[] {new TextEdit(range, replacement)});
        }
    }

    /** The innermost statement that is a direct child of a block and contains {@code position}. */
    private static StatementTree blockStatementAt(
            CompilationUnitTree root, SourcePositions pos, int position) {
        var result = new StatementTree[1];
        var best = new long[] {Long.MAX_VALUE};
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitBlock(BlockTree b, Void p) {
                for (var st : b.getStatements()) {
                    var s = pos.getStartPosition(root, st);
                    var e = pos.getEndPosition(root, st);
                    if (s >= 0 && s <= position && position < e && (e - s) < best[0]) {
                        best[0] = e - s;
                        result[0] = st;
                    }
                }
                return super.visitBlock(b, p);
            }
        }.scan(root, null);
        return result[0];
    }
}
