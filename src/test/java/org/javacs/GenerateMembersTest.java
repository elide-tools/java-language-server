package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class GenerateMembersTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<CodeAction> actions(String file) {
        var uri = FindResource.uri(file);
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.range = new Range(new Position(2, 0), new Position(2, 0));
        params.context.only = List.of(CodeActionKind.Source);
        return server.codeAction(params);
    }

    @Test
    public void generatesConstructorAndAccessors() {
        var actions = actions("/org/javacs/example/GenerateExample.java");
        var titles = new ArrayList<String>();
        CodeAction constructor = null;
        for (var a : actions) {
            titles.add(a.title);
            if (a.title.equals("Generate constructor")) constructor = a;
        }
        assertThat(titles, hasItems("Generate constructor", "Generate getters and setters"));
        // the generated constructor initializes the declared fields
        assertThat(constructor, notNullValue());
        // listing is lazy: the edit is absent until resolved
        assertThat(constructor.edit, nullValue());
        var resolved = server.resolveCodeAction(constructor);
        var edits = resolved.edit.changes.values().iterator().next();
        assertThat(edits.get(0).newText, allOf(containsString("GenerateExample("), containsString("count")));
    }
}
