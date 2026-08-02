package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class ImplementationTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<String> items(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(row - 1, column - 1));
        var locations = server.findImplementations(params).orElse(List.of());
        var out = new ArrayList<String>();
        for (var l : locations) {
            out.add(String.format("%s(%d)", StringSearch.fileName(l.uri), l.range.start.line + 1));
        }
        return out;
    }

    @Test
    public void findsInterfaceMethodImplementations() {
        var items = items("/org/javacs/example/ImplementationExample.java", 4, 10);
        assertThat(items, hasItems("ImplementationExample.java(8)", "ImplementationExample.java(12)"));
    }

    @Test
    public void findsInterfaceTypeImplementations() {
        var items = items("/org/javacs/example/ImplementationExample.java", 3, 11);
        assertThat(items, hasItems("ImplementationExample.java(7)", "ImplementationExample.java(11)"));
    }
}
