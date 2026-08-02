package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class SelectionRangeTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<SelectionRange> ranges(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new SelectionRangeParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.positions = List.of(new Position(row - 1, column - 1));
        return server.selectionRange(params);
    }

    @Test
    public void expandsSelectionFromIdentifier() {
        var rs = ranges("/org/javacs/example/SelectionRangeExample.java", 7, 16);
        assertThat(rs, hasSize(1));
        var innermost = rs.get(0);
        assertThat(innermost.range, notNullValue());
        // identifier -> at least one enclosing node (expression/statement/...)
        assertThat(innermost.parent, notNullValue());
    }
}
