package org.javacs.rewrite;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.logging.Logger;
import org.javacs.CompilerProvider;
import org.javacs.lsp.TextEdit;

/**
 * Rename a class/interface/enum/record/annotation type and every reference to it across the
 * workspace: the declaration name, constructor declarations, `new` expressions, type usages, and
 * import statements. Cross-file references are located the same way as "Find References" (same
 * package or an explicit/wildcard import), so this inherits that resolution's reach.
 */
public class RenameType implements Rewrite {
    final String className, newName;

    public RenameType(String className, String newName) {
        this.className = className;
        this.newName = newName;
    }

    @Override
    public Map<Path, TextEdit[]> rewrite(CompilerProvider compiler) {
        LOG.info("Rewrite type " + className + " to " + newName + "...");
        // findTypeReferences already includes the declaration file (same package) plus every
        // importing/same-package user. Union in findTypeDeclaration too, since it is the only path
        // that resolves a public type by source path when it has no in-workspace references.
        var files = new LinkedHashSet<Path>();
        var declaration = compiler.findTypeDeclaration(className);
        if (declaration != CompilerProvider.NOT_FOUND) {
            files.add(declaration);
        }
        for (var ref : compiler.findTypeReferences(className)) {
            files.add(ref);
        }
        if (files.isEmpty()) {
            LOG.warning("...no source found for " + className);
            return CANCELLED;
        }
        LOG.info("...check " + files.size() + " files for references");
        try (var compile = compiler.compile(files.toArray(Path[]::new))) {
            var helper = new RenameHelper(compile);
            return helper.renameType(compile.roots, className, newName);
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
