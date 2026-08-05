package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.javacs.lsp.*;
import org.junit.Test;

public class RenameTypeTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    // cursor on `RenameTypeExample` in `public class RenameTypeExample {`
    private static final Position CLASS_NAME = new Position(2, 18);

    @Test
    public void prepareRenameOffersTheType() {
        var uri = FindResource.uri("/org/javacs/example/RenameTypeExample.java");
        var params = new TextDocumentPositionParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.position = CLASS_NAME;

        var prepared = server.prepareRename(params);
        assertThat("type rename is offered", prepared.isPresent(), equalTo(true));
        assertThat(prepared.get().placeholder, equalTo("RenameTypeExample"));
    }

    @Test
    public void renamesDeclarationConstructorAndReferences() {
        var uri = FindResource.uri("/org/javacs/example/RenameTypeExample.java");
        var params = new RenameParams();
        params.textDocument = new TextDocumentIdentifier(uri);
        params.position = CLASS_NAME;
        params.newName = "RenamedType";

        var edit = server.rename(params);

        // Edits land in both the declaration file and the same-package user file.
        assertThat(edit.changes.keySet(), hasSize(2));
        java.util.List<TextEdit> declEdits = null, userEdits = null;
        for (var entry : edit.changes.entrySet()) {
            var path = entry.getKey().getPath();
            if (path.endsWith("RenameTypeExample.java")) declEdits = entry.getValue();
            if (path.endsWith("RenameTypeUser.java")) userEdits = entry.getValue();
        }
        assertThat("declaration file edited", declEdits, notNullValue());
        assertThat("user file edited", userEdits, notNullValue());

        // Declaration file: class decl + constructor decl + return type + `new` = 4 occurrences.
        assertThat(declEdits, hasSize(4));
        // User file: return type + local var type + `new` = 3 occurrences.
        assertThat(userEdits, hasSize(3));

        // Every edit substitutes the new simple name.
        for (var edits : edit.changes.values()) {
            for (var e : edits) {
                assertThat(e.newText, equalTo("RenamedType"));
            }
        }
    }
}
