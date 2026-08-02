package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class AddOverridesTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void insertsMissingOverrideAnnotation() {
        var uri = FindResource.uri("/org/javacs/example/MissingOverride.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.range = new Range(new Position(6, 0), new Position(6, 0));
        params.context.only = List.of(CodeActionKind.Source);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("override")) action = a;
        }
        assertThat(action, notNullValue());
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // greet() overrides Greeting.greet() without @Override -> exactly one annotation inserted
        var inserted = 0;
        for (var e : edits) {
            if (e.newText.contains("@Override")) inserted++;
        }
        assertThat(inserted, equalTo(1));
    }
}
