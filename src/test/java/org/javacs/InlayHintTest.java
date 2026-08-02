package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import org.javacs.lsp.*;
import org.junit.Test;

public class InlayHintTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private java.util.List<InlayHint> hints(String file) {
        var uri = FindResource.uri(file);
        var params = new InlayHintParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.range = new Range(new Position(0, 0), new Position(1000, 0));
        return server.inlayHint(params);
    }

    @Test
    public void parameterAndTypeHints() {
        var hs = hints("/org/javacs/example/InlayHintExample.java");
        var labels = new ArrayList<String>();
        var kinds = new ArrayList<Integer>();
        for (var h : hs) {
            labels.add(h.label);
            kinds.add(h.kind);
        }
        // parameter-name hints at the add(1, 2) call site
        assertThat(labels, hasItems("first:", "second:"));
        // inferred-type hint for `var total`
        assertThat(kinds, hasItem(InlayHint.TYPE));
    }
}
