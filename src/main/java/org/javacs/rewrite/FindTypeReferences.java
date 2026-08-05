package org.javacs.rewrite;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.*;
import java.util.function.Consumer;

/**
 * Visits every tree that can carry a type's simple name in source: the type declaration itself,
 * constructor declarations (whose source name is the class name), plain identifier usages (`Foo`,
 * `new Foo()`, `@Foo`, `Foo::new`), and qualified/select usages (`com.example.Foo`, imports).
 * The caller decides which visited paths actually resolve to the target type.
 */
class FindTypeReferences extends TreePathScanner<Void, Consumer<TreePath>> {
    @Override
    public Void visitClass(ClassTree node, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitClass(node, forEach);
    }

    @Override
    public Void visitMethod(MethodTree node, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMethod(node, forEach);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitIdentifier(node, forEach);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Consumer<TreePath> forEach) {
        forEach.accept(getCurrentPath());
        return super.visitMemberSelect(node, forEach);
    }
}
