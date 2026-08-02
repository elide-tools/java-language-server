package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class InlineFieldTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void inlinesFieldAndDeletesDeclaration() {
        var uri = FindResource.uri("/org/javacs/example/InlineFieldExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on the `FACTOR` field declaration
        var cursor = new Position(3, 29);
        params.range = new Range(cursor, cursor);
        params.context.only = List.of(CodeActionKind.RefactorInline);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("field")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorInline));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // one edit replaces the use with the initializer, another deletes the declaration line
        var replaced = false;
        var deleted = false;
        for (var e : edits) {
            if (e.newText.equals("3")) replaced = true;
            if (e.newText.isEmpty()) deleted = true;
        }
        assertThat("replaces use with initializer", replaced, equalTo(true));
        assertThat("deletes declaration", deleted, equalTo(true));
    }
}
