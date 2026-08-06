package org.javacs.lsp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** A full workspace document diagnostic report (LSP `WorkspaceFullDocumentDiagnosticReport`). */
public class WorkspaceDocumentDiagnosticReport {
    public String kind = "full";
    public URI uri;
    public Integer version;
    public List<Diagnostic> items = new ArrayList<>();
    public String resultId;
}
