package org.javacs.lsp;

public class SelectionRange {
    public Range range;
    public SelectionRange parent;

    public SelectionRange() {}

    public SelectionRange(Range range) {
        this.range = range;
    }
}
