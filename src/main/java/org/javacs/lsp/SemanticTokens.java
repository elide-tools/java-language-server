package org.javacs.lsp;

public class SemanticTokens {
    public String resultId;
    public int[] data;

    public SemanticTokens() {}

    public SemanticTokens(int[] data) {
        this.data = data;
    }
}
