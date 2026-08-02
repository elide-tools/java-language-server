package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import org.javacs.lsp.*;
import org.junit.Test;

public class TypeHierarchyTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private TypeHierarchyItem prepare(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(row - 1, column - 1));
        var items = server.prepareTypeHierarchy(params);
        assertThat(items, not(empty()));
        return items.get(0);
    }

    @Test
    public void prepareResolvesType() {
        var item = prepare("/org/javacs/example/TypeHierarchyExample.java", 6, 7);
        assertThat(item.name, equalTo("Dog"));
        assertThat(item.kind, equalTo(SymbolKind.Class));
    }

    @Test
    public void supertypesIncludeInterface() {
        var item = prepare("/org/javacs/example/TypeHierarchyExample.java", 6, 7);
        var params = new TypeHierarchySupertypesParams();
        params.item = item;
        var names = new ArrayList<String>();
        for (var s : server.typeHierarchySupertypes(params)) {
            names.add(s.name);
        }
        assertThat(names, hasItem("Animal"));
    }

    @Test
    public void subtypesIncludeSubclass() {
        var item = prepare("/org/javacs/example/TypeHierarchyExample.java", 6, 7);
        var params = new TypeHierarchySubtypesParams();
        params.item = item;
        var names = new ArrayList<String>();
        for (var s : server.typeHierarchySubtypes(params)) {
            names.add(s.name);
        }
        assertThat(names, hasItem("Puppy"));
    }
}
