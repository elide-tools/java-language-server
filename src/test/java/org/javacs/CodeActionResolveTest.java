package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import com.google.gson.Gson;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class CodeActionResolveTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();
    private static final Gson gson = new Gson();

    @Test
    public void listsWithoutEditThenResolves() {
        var uri = FindResource.uri("/org/javacs/example/GenerateExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.range = new Range(new Position(2, 0), new Position(2, 0));
        params.context.only = List.of(CodeActionKind.Source);

        CodeAction listed = null;
        for (var a : server.codeAction(params)) {
            if ("Generate constructor".equals(a.title)) listed = a;
        }
        // Phase 1 (list): the action is offered with a resolve descriptor but no edit yet.
        assertThat(listed, notNullValue());
        assertThat(listed.edit, nullValue());
        assertThat(listed.data, notNullValue());

        // The client echoes the action back over the wire before resolving; the opaque `data`
        // must survive a gson round-trip (the native-image reflection contract for resolve).
        var echoed = gson.fromJson(gson.toJson(listed), CodeAction.class);
        assertThat(echoed.edit, nullValue());
        assertThat(echoed.data, notNullValue());

        // Phase 2 (resolve): the edit is computed from the descriptor alone.
        var resolved = server.resolveCodeAction(echoed);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        assertThat(edits.get(0).newText, containsString("GenerateExample("));
    }
}
