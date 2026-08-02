package org.javacs.lsp;

import java.util.ArrayList;
import java.util.List;

public class CallHierarchyIncomingCall {
    public CallHierarchyItem from;
    public List<Range> fromRanges = new ArrayList<>();

    public CallHierarchyIncomingCall() {}

    public CallHierarchyIncomingCall(CallHierarchyItem from) {
        this.from = from;
    }
}
