package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class ExtractMethodTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void extractsSelectedStatements() {
        var uri = FindResource.uri("/org/javacs/example/ExtractMethodExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // select the two `int ...;` statements (params a, b are read; scaled is returned)
        params.range = new Range(new Position(4, 8), new Position(5, 29));
        params.context.only = List.of(CodeActionKind.RefactorExtract);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("method")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorExtract));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        // one edit declares the method with the two free vars as params returning the out var,
        // the other replaces the statements with a call binding the returned value
        var declares = false;
        var replaces = false;
        for (var e : edits) {
            if (e.newText.contains("private int extracted(int a, int b)") && e.newText.contains("return scaled;"))
                declares = true;
            if (e.newText.contains("int scaled = extracted(a, b);")) replaces = true;
        }
        assertThat("declares method", declares, equalTo(true));
        assertThat("replaces with call", replaces, equalTo(true));
    }
}
