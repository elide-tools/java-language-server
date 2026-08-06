package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.HashSet;
import org.javacs.lsp.*;
import org.junit.Test;

public class SemanticTokensTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private int[] tokens(String file) {
        var uri = FindResource.uri(file);
        var params = new SemanticTokensParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        var result = server.semanticTokensFull(params);
        assertThat(result.isPresent(), equalTo(true));
        return result.get().data;
    }

    @Test
    public void classifiesTokensByElementKind() {
        var data = tokens("/org/javacs/example/SemanticTokensExample.java");
        assertThat(data, notNullValue());
        assertThat(data.length, greaterThan(0));
        assertThat(data.length % 5, equalTo(0));
        var types = new HashSet<Integer>();
        for (var i = 0; i < data.length; i += 5) {
            types.add(data[i + 3]);
        }
        // fixture exercises a field (property=4), a parameter (2) and a method call (6)
        assertThat(types, hasItems(4, 2, 6));
    }

    @Test
    public void rangeReturnsAStrictSubset() {
        var file = "/org/javacs/example/SemanticTokensExample.java";
        var full = tokens(file);
        var uri = FindResource.uri(file);
        var params = new SemanticTokensRangeParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // first few lines only (through the field declaration), excluding the method bodies
        params.range = new Range(new Position(0, 0), new Position(5, 0));
        var result = server.semanticTokensRange(params);
        assertThat(result.isPresent(), equalTo(true));
        var data = result.get().data;
        assertThat(data.length % 5, equalTo(0));
        assertThat("range has tokens", data.length, greaterThan(0));
        assertThat("range is a subset of full", data.length, lessThan(full.length));
    }

    @Test
    public void fullDeltaFallsBackToFullTokens() {
        var file = "/org/javacs/example/SemanticTokensExample.java";
        var full = tokens(file);
        var uri = FindResource.uri(file);
        var params = new SemanticTokensDeltaParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.previousResultId = "stale";
        var result = server.semanticTokensFullDelta(params);
        assertThat(result.isPresent(), equalTo(true));
        // we don't diff: a full token set is returned, tagged with a resultId
        assertThat(result.get().data.length, equalTo(full.length));
        assertThat(result.get().resultId, notNullValue());
    }
}
