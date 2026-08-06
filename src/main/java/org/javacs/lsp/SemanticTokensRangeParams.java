package org.javacs.lsp;

/** Params for `textDocument/semanticTokens/range` (tokens for a sub-range only). */
public class SemanticTokensRangeParams {
    public TextDocumentIdentifier textDocument;
    public Range range;
}
