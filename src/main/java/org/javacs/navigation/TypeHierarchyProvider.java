package org.javacs.navigation;

import com.sun.source.tree.ClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.SymbolKind;
import org.javacs.lsp.TypeHierarchyItem;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;

/**
 * `typeHierarchy/*`: prepare a type item, then its direct supertypes and its subtypes across the
 * workspace. Pure javac element analysis — native-image safe.
 */
public class TypeHierarchyProvider {
    private final CompilerProvider compiler;

    public TypeHierarchyProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<TypeHierarchyItem> prepare(Path file, int line, int column) {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (!NavigationHelper.isType(element)) return List.of();
            var name = ((TypeElement) element).getQualifiedName().toString();
            if (name.isEmpty()) return List.of();
            var item = itemForType(name);
            return item == null ? List.of() : List.of(item);
        }
    }

    public List<TypeHierarchyItem> supertypes(TypeHierarchyItem from) {
        var file = Paths.get(from.uri);
        var line = from.selectionRange.start.line + 1;
        var column = from.selectionRange.start.character + 1;
        var names = new ArrayList<String>();
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (!NavigationHelper.isType(element)) return List.of();
            var types = task.task.getTypes();
            for (var supertype : types.directSupertypes(element.asType())) {
                var superElement = types.asElement(supertype);
                if (superElement instanceof TypeElement) {
                    names.add(((TypeElement) superElement).getQualifiedName().toString());
                }
            }
        }
        var items = new ArrayList<TypeHierarchyItem>();
        for (var name : names) {
            var item = itemForType(name);
            if (item != null) items.add(item);
        }
        return items;
    }

    public List<TypeHierarchyItem> subtypes(TypeHierarchyItem from) {
        var file = Paths.get(from.uri);
        var line = from.selectionRange.start.line + 1;
        var column = from.selectionRange.start.character + 1;
        String className;
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (!NavigationHelper.isType(element)) return List.of();
            className = ((TypeElement) element).getQualifiedName().toString();
        }
        var files = compiler.findTypeReferences(className);
        if (files.length == 0) return List.of();
        var items = new ArrayList<TypeHierarchyItem>();
        var seen = new ArrayList<String>();
        try (var task = compiler.compile(files)) {
            var trees = Trees.instance(task.task);
            var types = task.task.getTypes();
            var target = task.task.getElements().getTypeElement(className);
            if (target == null) return List.of();
            for (var root : task.roots) {
                var classPaths = new ArrayList<TreePath>();
                new FindClasses().scan(root, classPaths);
                for (var path : classPaths) {
                    var el = trees.getElement(path);
                    if (!(el instanceof TypeElement)) continue;
                    var candidate = (TypeElement) el;
                    if (candidate.equals(target)) continue;
                    if (!types.isSubtype(
                            types.erasure(candidate.asType()), types.erasure(target.asType())))
                        continue;
                    var qn = candidate.getQualifiedName().toString();
                    if (seen.contains(qn)) continue;
                    seen.add(qn);
                    items.add(item(task, path, candidate));
                }
            }
        }
        return items;
    }

    private TypeHierarchyItem itemForType(String qualifiedName) {
        var otherFile = compiler.findAnywhere(qualifiedName);
        if (otherFile.isEmpty()) return null;
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
                    return item(task, path, type);
                }
                if (fallback == null && type.getSimpleName().contentEquals(simpleName)) {
                    fallback = path;
                }
            }
            if (fallback != null) {
                return item(task, fallback, (TypeElement) trees.getElement(fallback));
            }
            return null;
        }
    }

    private static TypeHierarchyItem item(CompileTask task, TreePath classPath, TypeElement type) {
        var item = new TypeHierarchyItem();
        item.name = type.getSimpleName().toString();
        item.kind = kind(type.getKind());
        item.uri = classPath.getCompilationUnit().getSourceFile().toUri();
        item.range = FindHelper.location(task, classPath).range;
        item.selectionRange = FindHelper.location(task, classPath, type.getSimpleName()).range;
        var enclosing = type.getEnclosingElement();
        if (enclosing instanceof javax.lang.model.element.PackageElement) {
            item.detail =
                    ((javax.lang.model.element.PackageElement) enclosing)
                            .getQualifiedName()
                            .toString();
        }
        return item;
    }

    private static int kind(ElementKind kind) {
        switch (kind) {
            case INTERFACE:
            case ANNOTATION_TYPE:
                return SymbolKind.Interface;
            case ENUM:
                return SymbolKind.Enum;
            default:
                return SymbolKind.Class;
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
