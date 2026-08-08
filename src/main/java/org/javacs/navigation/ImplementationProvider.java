package org.javacs.navigation;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.Location;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * `textDocument/implementation`: from an interface/abstract method (or a type), find its concrete
 * implementations/overriders across the workspace. Pure javac element analysis — native-image safe.
 */
public class ImplementationProvider {
    private final CompilerProvider compiler;
    private final Path file;
    private final int line, column;

    public static final List<Location> NOT_SUPPORTED = List.of();

    public ImplementationProvider(CompilerProvider compiler, Path file, int line, int column) {
        this.compiler = compiler;
        this.file = file;
        this.line = line;
        this.column = column;
    }

    public List<Location> find() {
        String typeName;
        String methodName = null;
        int arity = -1;
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (element == null) return NOT_SUPPORTED;
            if (element instanceof ExecutableElement) {
                var method = (ExecutableElement) element;
                var parent = method.getEnclosingElement();
                if (!(parent instanceof TypeElement)) return NOT_SUPPORTED;
                typeName = ((TypeElement) parent).getQualifiedName().toString();
                methodName = method.getSimpleName().toString();
                arity = method.getParameters().size();
            } else if (NavigationHelper.isType(element)) {
                typeName = ((TypeElement) element).getQualifiedName().toString();
            } else {
                return NOT_SUPPORTED;
            }
        }
        if (typeName.isEmpty()) return NOT_SUPPORTED;
        return search(typeName, methodName, arity);
    }

    private List<Location> search(String typeName, String methodName, int arity) {
        var files = compiler.findTypeReferences(typeName);
        if (files.length == 0) return List.of();
        var locations = new ArrayList<Location>();
        try (var task = compiler.compile(files)) {
            var trees = Trees.instance(task.task);
            var elements = task.task.getElements();
            var types = task.task.getTypes();
            var target = elements.getTypeElement(typeName);
            if (target == null) return locations;

            ExecutableElement targetMethod = null;
            if (methodName != null) {
                for (var m : ElementFilter.methodsIn(target.getEnclosedElements())) {
                    if (m.getSimpleName().contentEquals(methodName)
                            && m.getParameters().size() == arity) {
                        targetMethod = m;
                        break;
                    }
                }
            }

            for (var root : task.roots) {
                var classPaths = new ArrayList<TreePath>();
                new FindClasses().scan(root, classPaths);
                for (var path : classPaths) {
                    var el = trees.getElement(path);
                    if (!(el instanceof TypeElement)) continue;
                    var subtype = (TypeElement) el;
                    if (subtype.equals(target)) continue;
                    if (!types.isSubtype(
                            types.erasure(subtype.asType()), types.erasure(target.asType())))
                        continue;
                    if (methodName == null) {
                        locations.add(FindHelper.location(task, path, subtype.getSimpleName()));
                        continue;
                    }
                    if (targetMethod == null) continue;
                    addOverrider(
                            task, trees, elements, subtype, targetMethod, methodName, locations);
                }
            }
        }
        return locations;
    }

    private void addOverrider(
            CompileTask task,
            Trees trees,
            javax.lang.model.util.Elements elements,
            TypeElement subtype,
            ExecutableElement targetMethod,
            String methodName,
            List<Location> locations) {
        for (var m : ElementFilter.methodsIn(elements.getAllMembers(subtype))) {
            if (!m.getSimpleName().contentEquals(methodName)) continue;
            if (!elements.overrides(m, targetMethod, subtype)) continue;
            var p = trees.getPath(m);
            if (p == null) continue; // inherited from a binary or unavailable source
            locations.add(FindHelper.location(task, p, methodName));
        }
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
