package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class ChangeMethodAccessTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void changesPrivateMethodToPublic() {
        var uri = FindResource.uri("/org/javacs/example/ChangeMethodAccessExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on `secret` in `private int secret()`
        var cursor = new Position(3, 16);
        params.range = new Range(cursor, cursor);
        params.context.only = List.of(CodeActionKind.RefactorRewrite);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("public")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorRewrite));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        var changed = false;
        for (var e : edits) {
            if (e.newText.equals("public")) changed = true;
        }
        assertThat("replaces the access keyword", changed, equalTo(true));
    }
}
