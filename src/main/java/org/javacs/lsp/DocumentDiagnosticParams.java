package org.javacs.lsp;

/** Params for `textDocument/diagnostic` (document pull). */
public class DocumentDiagnosticParams {
    public TextDocumentIdentifier textDocument;
    public String identifier;
    public String previousResultId;
}
