package org.javacs.lsp;

/** Params for `textDocument/semanticTokens/full/delta`. */
public class SemanticTokensDeltaParams {
    public TextDocumentIdentifier textDocument;
    public String previousResultId;
}
