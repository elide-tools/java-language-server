package org.javacs.markup;

import org.javacs.lsp.Range;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class SemanticColors {
    public URI uri;
    public List<Range> statics = new ArrayList<>(), fields = new ArrayList<>();
}
