package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.HashSet;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class RemoveParameterTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void removesUnusedParameterAndArgument() {
        var uri = FindResource.uri("/org/javacs/example/RemoveParameterExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on the `unused` parameter
        var cursor = new Position(3, 35);
        params.range = new Range(cursor, cursor);
        params.context.only = List.of(CodeActionKind.RefactorRewrite);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("remove")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorRewrite));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // one deletion in the declaration (line 3), one in the call (line 8); both empty replacements
        var startLines = new HashSet<Integer>();
        var deletions = 0;
        for (var e : edits) {
            if (e.newText.isEmpty()) {
                deletions++;
                startLines.add(e.range.start.line);
            }
        }
        assertThat("two deletions", deletions, equalTo(2));
        assertThat(startLines, containsInAnyOrder(3, 8));
    }
}
