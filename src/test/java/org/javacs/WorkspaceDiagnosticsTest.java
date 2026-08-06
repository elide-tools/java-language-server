package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.javacs.lsp.*;
import org.junit.Test;

public class WorkspaceDiagnosticsTest {
    private static final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer(LanguageServerFixture.SIMPLE_WORKSPACE_ROOT, d -> {});

    private DocumentDiagnosticParams docParams(String name) {
        var params = new DocumentDiagnosticParams();
        var uri = LanguageServerFixture.SIMPLE_WORKSPACE_ROOT.resolve(name).toUri();
        params.textDocument = new TextDocumentIdentifier(uri);
        return params;
    }

    @Test
    public void documentPullReportsErrorsForABadFile() {
        var report = server.documentDiagnostics(docParams("HelloError.java"));
        assertThat(report.kind, equalTo("full"));
        assertThat("HelloError.java has compile errors", report.items, not(empty()));
    }

    @Test
    public void documentPullIsEmptyForACleanFile() {
        var report = server.documentDiagnostics(docParams("CleanDiag.java"));
        assertThat(report.kind, equalTo("full"));
        assertThat(report.items, empty());
    }

    @Test
    public void workspacePullReportsEveryDocumentAndSurfacesErrors() {
        var report = server.workspaceDiagnostics(new WorkspaceDiagnosticParams());
        assertThat("one report per workspace source file", report.items, not(empty()));

        WorkspaceDocumentDiagnosticReport bad = null;
        for (var item : report.items) {
            assertThat(item.kind, equalTo("full"));
            if (item.uri.getPath().endsWith("HelloError.java")) bad = item;
        }
        assertThat("HelloError.java is reported", bad, notNullValue());
        assertThat("HelloError.java carries diagnostics", bad.items, not(empty()));
    }
}
