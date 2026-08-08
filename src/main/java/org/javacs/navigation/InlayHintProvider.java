package org.javacs.navigation;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

import org.javacs.CompilerProvider;
import org.javacs.lsp.InlayHint;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.ExecutableElement;

/**
 * `textDocument/inlayHint`: parameter-name hints at call sites and inferred-type hints for {@code
 * var} locals. Pure AST — native-image safe.
 */
public class InlayHintProvider {
    private final CompilerProvider compiler;
    private final Path file;

    public InlayHintProvider(CompilerProvider compiler, Path file) {
        this.compiler = compiler;
        this.file = file;
    }

    public List<InlayHint> inlayHints(Range range) {
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            var hints = new ArrayList<InlayHint>();
            new Hinter(task.task, range).scan(root, hints);
            return hints;
        }
    }

    private static class Hinter extends TreePathScanner<Void, List<InlayHint>> {
        private final Trees trees;
        private final SourcePositions pos;
        private final Range range;

        Hinter(JavacTask task, Range range) {
            this.trees = Trees.instance(task);
            this.pos = trees.getSourcePositions();
            this.range = range;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree t, List<InlayHint> acc) {
            var element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement) {
                parameterHints((ExecutableElement) element, t.getArguments(), acc);
            }
            return super.visitMethodInvocation(t, acc);
        }

        @Override
        public Void visitNewClass(NewClassTree t, List<InlayHint> acc) {
            var element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement) {
                parameterHints((ExecutableElement) element, t.getArguments(), acc);
            }
            return super.visitNewClass(t, acc);
        }

        private void parameterHints(
                ExecutableElement method,
                List<? extends ExpressionTree> args,
                List<InlayHint> acc) {
            var params = method.getParameters();
            // Skip trailing varargs: their names add noise.
            var limit = method.isVarArgs() ? params.size() - 1 : params.size();
            for (var i = 0; i < args.size() && i < limit; i++) {
                var arg = args.get(i);
                var name = params.get(i).getSimpleName().toString();
                // Skip when the argument already reads as the parameter name.
                if (arg instanceof IdentifierTree
                        && ((IdentifierTree) arg).getName().contentEquals(name)) continue;
                var position = startPosition(arg);
                if (position == null) continue;
                var hint = new InlayHint(position, name + ":", InlayHint.PARAMETER);
                hint.paddingRight = true;
                acc.add(hint);
            }
        }

        @Override
        public Void visitVariable(VariableTree t, List<InlayHint> acc) {
            if (t instanceof JCTree.JCVariableDecl
                    && ((JCTree.JCVariableDecl) t).declaredUsingVar()) {
                var element = trees.getElement(getCurrentPath());
                if (element != null) {
                    var root = getCurrentPath().getCompilationUnit();
                    var from = (int) pos.getStartPosition(root, t);
                    var position = namePosition(root, from, t.getName().toString());
                    if (position != null) {
                        var hint =
                                new InlayHint(
                                        position,
                                        ": " + simpleType(element.asType().toString()),
                                        InlayHint.TYPE);
                        acc.add(hint);
                    }
                }
            }
            return super.visitVariable(t, acc);
        }

        private static String simpleType(String type) {
            var generic = type.indexOf('<');
            var head = generic < 0 ? type : type.substring(0, generic);
            var tail = generic < 0 ? "" : type.substring(generic);
            var dot = head.lastIndexOf('.');
            return (dot < 0 ? head : head.substring(dot + 1)) + tail;
        }

        private Position startPosition(Tree tree) {
            var root = getCurrentPath().getCompilationUnit();
            return toPosition(root, (int) pos.getStartPosition(root, tree));
        }

        private Position namePosition(CompilationUnitTree root, int from, String name) {
            if (from < 0) return null;
            try {
                var contents = root.getSourceFile().getCharContent(true).toString();
                var idx = contents.indexOf(name, from);
                if (idx < 0) return null;
                return toPosition(root, idx + name.length());
            } catch (Exception e) {
                return null;
            }
        }

        private Position toPosition(CompilationUnitTree root, int offset) {
            if (offset < 0) return null;
            var lines = root.getLineMap();
            var line = (int) lines.getLineNumber(offset) - 1;
            var character = (int) lines.getColumnNumber(offset) - 1;
            if (line < range.start.line || line > range.end.line) return null;
            return new Position(line, character);
        }
    }
}
