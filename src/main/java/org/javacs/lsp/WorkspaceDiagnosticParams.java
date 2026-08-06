package org.javacs.lsp;

/**
 * Params for `workspace/diagnostic`. `previousResultIds` is accepted on the wire but unused: this
 * server always returns full reports rather than tracking `resultId`s.
 */
public class WorkspaceDiagnosticParams {
    public String identifier;
}
