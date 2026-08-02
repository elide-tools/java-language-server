package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class DeclarationTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<String> items(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(row - 1, column - 1));
        var locations = server.declaration(params).orElse(List.of());
        var out = new ArrayList<String>();
        for (var l : locations) {
            out.add(String.format("%s(%d)", StringSearch.fileName(l.uri), l.range.start.line + 1));
        }
        return out;
    }

    @Test
    public void resolvesFieldDeclaration() {
        var items = items("/org/javacs/example/DeclarationExample.java", 7, 16);
        assertThat(items, hasItem("DeclarationExample.java(4)"));
    }
}
