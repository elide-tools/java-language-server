package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class InlineVariableTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void inlinesLocalIntoItsUse() {
        var uri = FindResource.uri("/org/javacs/example/InlineExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on `sum` in `int sum = a + b;`
        params.range = new Range(new Position(4, 12), new Position(4, 12));
        params.context.only = List.of(CodeActionKind.RefactorInline);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (CodeActionKind.RefactorInline.equals(a.kind)) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.title.toLowerCase(), containsString("inline"));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // the use of `sum` becomes the (parenthesized) initializer; the declaration line is removed
        var replaced = false;
        var deleted = false;
        for (var e : edits) {
            if (e.newText.equals("(a + b)")) replaced = true;
            if (e.newText.isEmpty()) deleted = true;
        }
        assertThat("replaces use with initializer", replaced, equalTo(true));
        assertThat("removes declaration", deleted, equalTo(true));
    }
}
