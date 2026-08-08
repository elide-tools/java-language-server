package org.javacs.navigation;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Location;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/**
 * `textDocument/typeDefinition`: from a variable/expression, jump to the declaration of its *type*.
 * Pure javac element analysis — native-image safe.
 */
public class TypeDefinitionProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public TypeDefinitionProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        String qualifiedName;
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            var mirror = typeOf(element);
            if (mirror == null) return NOT_SUPPORTED;
            var types = task.task.getTypes();
            var typeElement = types.asElement(mirror);
            if (!(typeElement instanceof TypeElement)) return NOT_SUPPORTED;
            qualifiedName = ((TypeElement) typeElement).getQualifiedName().toString();
        }
        if (qualifiedName.isEmpty()) return NOT_SUPPORTED;
        return findTypeDeclaration(qualifiedName);
    }

    private TypeMirror typeOf(Element element) {
        if (element instanceof ExecutableElement) {
            return ((ExecutableElement) element).getReturnType();
        }
        // types, fields, locals, parameters, enum constants, expressions
        return element.asType();
    }

    /**
     * Resolve the class declaration via {@link CompilerProvider#findAnywhere} (source path, doc
     * path, then the type index — the same lookup goto definition relies on, and which handles the
     * unnamed/default package) then locate the class by walking the compiled trees rather than
     * {@code Elements.getTypeElement}, which returns null for unnamed-package types.
     */
    private List<Location> findTypeDeclaration(String qualifiedName) {
        var otherFile = compiler.findAnywhere(qualifiedName);
        if (otherFile.isEmpty()) return List.of();
        var simpleName = simpleName(qualifiedName);
        try (var task = compiler.compile(List.of(otherFile.get()))) {
            var trees = Trees.instance(task.task);
            var classPaths = new ArrayList<TreePath>();
            for (var root : task.roots) {
                new FindClasses().scan(root, classPaths);
            }
            TreePath fallback = null;
            for (var path : classPaths) {
                var el = trees.getElement(path);
                if (!(el instanceof TypeElement)) continue;
                var type = (TypeElement) el;
                if (type.getQualifiedName().contentEquals(qualifiedName)) {
                    return List.of(FindHelper.location(task, path, simpleName));
                }
                if (fallback == null && type.getSimpleName().contentEquals(simpleName)) {
                    fallback = path;
                }
            }
            if (fallback != null) return List.of(FindHelper.location(task, fallback, simpleName));
            return List.of();
        }
    }

    private static String simpleName(String qualifiedName) {
        var dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    private static class FindClasses extends TreePathScanner<Void, List<TreePath>> {
        @Override
        public Void visitClass(ClassTree t, List<TreePath> acc) {
            acc.add(getCurrentPath());
            return super.visitClass(t, acc);
        }

        @Override
        public Void reduce(Void a, Void b) {
            return null;
        }
    }
}
