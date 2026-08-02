package org.javacs.lsp;

import java.util.List;

public class SelectionRangeParams {
    public TextDocumentIdentifier textDocument;
    public List<Position> positions;
}
