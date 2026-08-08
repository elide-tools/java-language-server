package org.javacs.rewrite;

import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

import java.nio.file.Path;
import java.util.Map;

class RewriteNotSupported implements Rewrite {
    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        return CANCELLED;
    }
}
