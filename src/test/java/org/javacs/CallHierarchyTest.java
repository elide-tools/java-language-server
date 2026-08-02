package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.ArrayList;
import org.javacs.lsp.*;
import org.junit.Test;

public class CallHierarchyTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private CallHierarchyItem prepare(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(row - 1, column - 1));
        var items = server.prepareCallHierarchy(params);
        assertThat(items, not(empty()));
        return items.get(0);
    }

    @Test
    public void prepareResolvesMethod() {
        var item = prepare("/org/javacs/example/CallHierarchyExample.java", 4, 10);
        assertThat(item.name, equalTo("target"));
        assertThat(item.kind, equalTo(SymbolKind.Method));
    }

    @Test
    public void incomingCallsFindCallers() {
        var item = prepare("/org/javacs/example/CallHierarchyExample.java", 4, 10);
        var params = new CallHierarchyIncomingCallsParams();
        params.item = item;
        var callers = new ArrayList<String>();
        for (var c : server.callHierarchyIncoming(params)) {
            callers.add(c.from.name);
        }
        assertThat(callers, hasItem("caller"));
    }

    @Test
    public void outgoingCallsFindCallees() {
        var item = prepare("/org/javacs/example/CallHierarchyExample.java", 4, 10);
        var params = new CallHierarchyOutgoingCallsParams();
        params.item = item;
        var callees = new ArrayList<String>();
        for (var c : server.callHierarchyOutgoing(params)) {
            callees.add(c.to.name);
        }
        assertThat(callees, hasItem("helper"));
    }
}
