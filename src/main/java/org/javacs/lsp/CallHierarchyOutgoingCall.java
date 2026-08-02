package org.javacs.lsp;

import java.util.ArrayList;
import java.util.List;

public class CallHierarchyOutgoingCall {
    public CallHierarchyItem to;
    public List<Range> fromRanges = new ArrayList<>();

    public CallHierarchyOutgoingCall() {}

    public CallHierarchyOutgoingCall(CallHierarchyItem to) {
        this.to = to;
    }
}
