package org.javacs.lsp;

public class InlayHint {
    /** InlayHintKind: 1 = Type, 2 = Parameter. */
    public static final int TYPE = 1, PARAMETER = 2;

    public Position position;
    public String label;
    public int kind;
    public Boolean paddingLeft;
    public Boolean paddingRight;

    public InlayHint() {}

    public InlayHint(Position position, String label, int kind) {
        this.position = position;
        this.label = label;
        this.kind = kind;
    }
}
