package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class DocumentHighlightTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<DocumentHighlight> highlights(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(row - 1, column - 1));
        return server.documentHighlight(params);
    }

    @Test
    public void highlightsLocalVariable() {
        var hs = highlights("/org/javacs/example/DocumentHighlightExample.java", 5, 13);
        assertThat(hs.size(), greaterThanOrEqualTo(3));
        var kinds = new ArrayList<Integer>();
        for (var h : hs) {
            kinds.add(h.kind);
        }
        // `total = ...` assignments are Write occurrences.
        assertThat(kinds, hasItem(DocumentHighlightKind.Write));
    }
}
