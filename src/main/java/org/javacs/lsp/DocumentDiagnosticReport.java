package org.javacs.lsp;

import java.util.ArrayList;
import java.util.List;

/**
 * A full document diagnostic report (LSP `RelatedFullDocumentDiagnosticReport`, without the
 * optional `relatedDocuments` map).
 */
public class DocumentDiagnosticReport {
    public String kind = "full";
    public List<Diagnostic> items = new ArrayList<>();
    public String resultId;
}
