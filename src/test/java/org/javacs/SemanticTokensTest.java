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
}
