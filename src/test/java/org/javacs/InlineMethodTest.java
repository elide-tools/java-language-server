package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class InlineMethodTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void inlinesCallWithArgumentSubstitution() {
        var uri = FindResource.uri("/org/javacs/example/InlineMethodExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on the `twice(n + 1)` call in `return twice(n + 1);`
        var cursor = new Position(8, 15);
        params.range = new Range(cursor, cursor);
        params.context.only = List.of(CodeActionKind.RefactorInline);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("method")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorInline));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // the call is replaced by the body with the argument substituted for the parameter
        var replaced = false;
        for (var e : edits) {
            if (e.newText.equals("((n + 1) * 2)")) replaced = true;
        }
        assertThat("substitutes argument into body", replaced, equalTo(true));
    }
}
