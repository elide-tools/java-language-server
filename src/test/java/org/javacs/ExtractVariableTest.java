package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class ExtractVariableTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void extractsSelectedExpression() {
        var uri = FindResource.uri("/org/javacs/example/ExtractExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // select `3 * 4` in `return 2 + 3 * 4;`
        params.range = new Range(new Position(4, 19), new Position(4, 24));
        params.context.only = List.of(CodeActionKind.RefactorExtract);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (CodeActionKind.RefactorExtract.equals(a.kind)) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.title.toLowerCase(), containsString("extract"));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // one edit introduces the local, the other replaces the selection with its name
        var declares = false;
        var replaces = false;
        for (var e : edits) {
            if (e.newText.contains("var extracted = 3 * 4")) declares = true;
            if (e.newText.equals("extracted")) replaces = true;
        }
        assertThat("introduces local", declares, equalTo(true));
        assertThat("replaces selection", replaces, equalTo(true));
    }
}
