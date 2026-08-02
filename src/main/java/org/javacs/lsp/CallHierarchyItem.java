package org.javacs.lsp;

import java.net.URI;

public class CallHierarchyItem {
    public String name;
    public int kind;
    public String detail;
    public URI uri;
    public Range range;
    public Range selectionRange;
}
