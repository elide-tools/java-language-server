package org.javacs.navigation;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import org.javacs.CompileTask;
import org.javacs.CompilerProvider;
import org.javacs.FindHelper;
import org.javacs.lsp.CallHierarchyIncomingCall;
import org.javacs.lsp.CallHierarchyItem;
import org.javacs.lsp.CallHierarchyOutgoingCall;
import org.javacs.lsp.Range;
import org.javacs.lsp.SymbolKind;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * `callHierarchy/*`: prepare a method item, then find its callers (incoming) and callees
 * (outgoing). Reuses {@link FindReferences} for callers and walks the method body for callees. Pure
 * javac element analysis — native-image safe.
 */
public class CallHierarchyProvider {
    private final CompilerProvider compiler;

    public CallHierarchyProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<CallHierarchyItem> prepare(Path file, int line, int column) {
        try (var task = compiler.compile(file)) {
            var element = NavigationHelper.findElement(task, file, line, column);
            if (!(element instanceof ExecutableElement)) return List.of();
            var path = Trees.instance(task.task).getPath(element);
            if (path == null) return List.of();
            var item = item(task, path);
            return item == null ? List.of() : List.of(item);
        }
    }

    public List<CallHierarchyIncomingCall> incoming(CallHierarchyItem from) {
        var file = Paths.get(from.uri);
        var line = from.selectionRange.start.line + 1;
        var column = from.selectionRange.start.character + 1;
        String className;
        String methodName;
        try (var task = compiler.compile(file)) {
            var el = NavigationHelper.findElement(task, file, line, column);
            if (!(el instanceof ExecutableElement)) return List.of();
            var parent = el.getEnclosingElement();
            if (!(parent instanceof TypeElement)) return List.of();
            className = ((TypeElement) parent).getQualifiedName().toString();
            methodName = el.getSimpleName().toString();
        }
        var files = compiler.findMemberReferences(className, methodName);
        if (files.length == 0) return List.of();
        var result = new LinkedHashMap<String, CallHierarchyIncomingCall>();
        try (var task = compiler.compile(files)) {
            var target = NavigationHelper.findElement(task, file, line, column);
            if (target == null) return List.of();
            for (var root : task.roots) {
                var refs = new ArrayList<TreePath>();
                new FindReferences(task.task, target).scan(root, refs);
                for (var ref : refs) {
                    var callerPath = enclosingMethod(ref);
                    if (callerPath == null) continue;
                    var callerItem = item(task, callerPath);
                    if (callerItem == null) continue;
                    var key = key(callerItem);
                    var call =
                            result.computeIfAbsent(
                                    key, k -> new CallHierarchyIncomingCall(callerItem));
                    call.fromRanges.add(FindHelper.location(task, ref).range);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    public List<CallHierarchyOutgoingCall> outgoing(CallHierarchyItem from) {
        var file = Paths.get(from.uri);
        var line = from.selectionRange.start.line + 1;
        var column = from.selectionRange.start.character + 1;
        var result = new LinkedHashMap<String, CallHierarchyOutgoingCall>();
        var crossFile = new ArrayList<CalleeRef>();
        try (var task = compiler.compile(file)) {
            var trees = Trees.instance(task.task);
            var el = NavigationHelper.findElement(task, file, line, column);
            if (!(el instanceof ExecutableElement)) return List.of();
            var methodPath = trees.getPath(el);
            if (methodPath == null || !(methodPath.getLeaf() instanceof MethodTree))
                return List.of();
            new CalleeScanner(task, result, crossFile).scan(methodPath, null);
        }
        for (var ref : crossFile) {
            var declFile = compiler.findAnywhere(ref.className);
            if (declFile.isEmpty()) continue;
            try (var task = compiler.compile(List.of(declFile.get()))) {
                var method =
                        FindHelper.findMethod(
                                task, ref.className, ref.methodName, ref.erasedParameterTypes);
                if (method == null) continue;
                var path = Trees.instance(task.task).getPath(method);
                if (path == null) continue;
                var item = item(task, path);
                if (item == null) continue;
                result.computeIfAbsent(key(item), k -> new CallHierarchyOutgoingCall(item))
                        .fromRanges
                        .add(ref.range);
            } catch (RuntimeException ignored) {
                // unresolved cross-file callee (e.g. default-package getTypeElement) — skip
            }
        }
        return new ArrayList<>(result.values());
    }

    private class CalleeScanner extends TreePathScanner<Void, Void> {
        private final CompileTask task;
        private final Trees trees;
        private final LinkedHashMap<String, CallHierarchyOutgoingCall> sameFile;
        private final List<CalleeRef> crossFile;

        CalleeScanner(
                CompileTask task,
                LinkedHashMap<String, CallHierarchyOutgoingCall> sameFile,
                List<CalleeRef> crossFile) {
            this.task = task;
            this.trees = Trees.instance(task.task);
            this.sameFile = sameFile;
            this.crossFile = crossFile;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree t, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitMethodInvocation(t, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree t, Void unused) {
            record(trees.getElement(getCurrentPath()));
            return super.visitNewClass(t, unused);
        }

        private void record(Element callee) {
            if (!(callee instanceof ExecutableElement)) return;
            var range = FindHelper.location(task, getCurrentPath()).range;
            var declPath = trees.getPath(callee);
            if (declPath != null) {
                var item = item(task, declPath);
                if (item == null) return;
                sameFile.computeIfAbsent(key(item), k -> new CallHierarchyOutgoingCall(item))
                        .fromRanges
                        .add(range);
                return;
            }
            var parent = callee.getEnclosingElement();
            if (!(parent instanceof TypeElement)) return;
            crossFile.add(
                    new CalleeRef(
                            ((TypeElement) parent).getQualifiedName().toString(),
                            callee.getSimpleName().toString(),
                            FindHelper.erasedParameterTypes(task, (ExecutableElement) callee),
                            range));
        }
    }

    private static class CalleeRef {
        final String className;
        final String methodName;
        final String[] erasedParameterTypes;
        final Range range;

        CalleeRef(String className, String methodName, String[] erasedParameterTypes, Range range) {
            this.className = className;
            this.methodName = methodName;
            this.erasedParameterTypes = erasedParameterTypes;
            this.range = range;
        }
    }

    private static TreePath enclosingMethod(TreePath path) {
        for (var p = path.getParentPath(); p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof MethodTree) return p;
        }
        return null;
    }

    private static String key(CallHierarchyItem item) {
        return item.uri
                + "#"
                + item.selectionRange.start.line
                + ":"
                + item.selectionRange.start.character;
    }

    private static CallHierarchyItem item(CompileTask task, TreePath methodPath) {
        var element = Trees.instance(task.task).getElement(methodPath);
        if (!(element instanceof ExecutableElement)) return null;
        var method = (ExecutableElement) element;
        var displayName = method.getSimpleName().toString();
        if (displayName.equals("<init>")) {
            displayName = method.getEnclosingElement().getSimpleName().toString();
        }
        var item = new CallHierarchyItem();
        item.name = displayName;
        item.kind =
                method.getKind() == ElementKind.CONSTRUCTOR
                        ? SymbolKind.Constructor
                        : SymbolKind.Method;
        item.uri = methodPath.getCompilationUnit().getSourceFile().toUri();
        item.range = FindHelper.location(task, methodPath).range;
        item.selectionRange = FindHelper.location(task, methodPath, displayName).range;
        var enclosing = method.getEnclosingElement();
        if (enclosing instanceof TypeElement) {
            item.detail = ((TypeElement) enclosing).getQualifiedName().toString();
        }
        return item;
    }
}
