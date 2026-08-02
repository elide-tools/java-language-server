package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class ReplaceConstructorWithFactoryMethodTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void addsFactoryAndRedirectsNewCall() {
        var uri = FindResource.uri("/org/javacs/example/ReplaceConstructorExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        // cursor on the constructor name
        var cursor = new Position(5, 11);
        params.range = new Range(cursor, cursor);
        params.context.only = List.of(CodeActionKind.RefactorRewrite);

        CodeAction action = null;
        for (var a : server.codeAction(params)) {
            if (a.title.toLowerCase().contains("factory")) action = a;
        }
        assertThat(action, notNullValue());
        assertThat(action.kind, equalTo(CodeActionKind.RefactorRewrite));
        // listing is lazy: no edit until resolved
        assertThat(action.edit, nullValue());

        var resolved = server.resolveCodeAction(action);
        assertThat(resolved.edit, notNullValue());
        var edits = resolved.edit.changes.values().iterator().next();
        var declaresFactory = false;
        var redirectsCall = false;
        for (var e : edits) {
            if (e.newText.contains("static ReplaceConstructorExample create(int value)")
                    && e.newText.contains("return new ReplaceConstructorExample(value);")) declaresFactory = true;
            if (e.newText.equals("ReplaceConstructorExample.create(42)")) redirectsCall = true;
        }
        assertThat("generates factory method", declaresFactory, equalTo(true));
        assertThat("redirects new call", redirectsCall, equalTo(true));
    }
}
