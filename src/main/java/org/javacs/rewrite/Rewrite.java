package org.javacs.rewrite;

import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

import java.nio.file.Path;
import java.util.Map;

public interface Rewrite {
    /** Perform a rewrite across the entire codebase. */
    Map<Path, TextEdit[]> rewrite(CompilerProvider compiler);

    /** CANCELLED signals that the rewrite couldn't be completed. */
    Map<Path, TextEdit[]> CANCELLED = Map.of();

    Rewrite NOT_SUPPORTED = new RewriteNotSupported();
}
