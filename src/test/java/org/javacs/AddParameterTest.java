package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class AddParameterTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void addsParameterInferredFromExtraArgument() {
        var uri = FindResource.uri("/org/javacs/example/AddParameterExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on the `scale(10, 2)` call, which passes one extra argument
        var cursor = new Position(8, 15);
        params.range = new Range(cursor, cursor);
        params.context.only = List.of(CodeActionKind.RefactorRewrite);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("add parameter")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorRewrite));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        var added = false;
        for (var e : edits) {
            if (e.newText.equals(", int param2")) added = true;
        }
        assertThat("adds inferred parameter", added, equalTo(true));
    }
}
