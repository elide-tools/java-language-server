package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class ExtractConstantTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void extractsSelectedExpressionToConstant() {
        var uri = FindResource.uri("/org/javacs/example/ExtractConstantExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // select `3 * 4` in `return 2 + 3 * 4;`
        params.range = new Range(new Position(4, 19), new Position(4, 24));
        params.context.only = List.of(CodeActionKind.RefactorExtract);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("constant")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorExtract));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // one edit introduces the constant field, the other replaces the selection with its name
        var declares = false;
        var replaces = false;
        for (var e : edits) {
            if (e.newText.contains("private static final int EXTRACTED_CONSTANT = 3 * 4")) declares = true;
            if (e.newText.equals("EXTRACTED_CONSTANT")) replaces = true;
        }
        assertThat("introduces constant", declares, equalTo(true));
        assertThat("replaces selection", replaces, equalTo(true));
    }
}
