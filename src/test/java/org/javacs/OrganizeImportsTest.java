package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class OrganizeImportsTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @Test
    public void removesUnusedImport() {
        var uri = FindResource.uri("/org/javacs/example/OrganizeImportsExample.java");
        var params = new CodeActionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.range = new Range(new Position(0, 0), new Position(6, 0));
        params.context.only = List.of(CodeActionKind.SourceOrganizeImports);

        CodeAction organize = null;
        for (var a : server.codeAction(params)) {
            if (CodeActionKind.SourceOrganizeImports.equals(a.kind)) organize = a;
        }
        assertThat(organize, notNullValue());
        assertThat(organize.title.toLowerCase(), containsString("import"));
        // the unused `import java.util.List;` is removed (a deletion edit)
        var edits = organize.edit.changes.values().iterator().next();
        var deletions = 0;
        for (var e : edits) {
            if (e.newText.isEmpty()) deletions++;
        }
        assertThat(deletions, greaterThanOrEqualTo(1));
    }
}
