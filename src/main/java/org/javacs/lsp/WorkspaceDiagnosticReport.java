package org.javacs.lsp;

import java.util.ArrayList;
import java.util.List;

/** Response for `workspace/diagnostic` (LSP `WorkspaceDiagnosticReport`). */
public class WorkspaceDiagnosticReport {
    public List<WorkspaceDocumentDiagnosticReport> items = new ArrayList<>();
}
