package org.javacs.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.source.tree.*;
import com.sun.source.util.*;

import org.javacs.*;
import org.javacs.FindTypeDeclarationAt;
import org.javacs.lsp.*;
import org.javacs.rewrite.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.lang.model.element.*;

public class CodeActionProvider {
    private final CompilerProvider compiler;

    public CodeActionProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public List<CodeAction> codeActionsForCursor(CodeActionParams params) {
        LOG.info(
                String.format(
                        "Find code actions at %s(%d)...",
                        params.textDocument.uri.getPath(), params.range.start.line + 1));
        var started = Instant.now();
        var file = Paths.get(params.textDocument.uri);
        // Cursor actions detect applicability with the one compile below (a cheap AST walk), then
        // list with a resolve descriptor and no edit. The edit is computed on demand in resolve(),
        // so a menu of N actions costs one compile, not one per action.
        var actions = new ArrayList<CodeAction>();
        try (var task = compiler.compile(file)) {
            var elapsed = Duration.between(started, Instant.now()).toMillis();
            LOG.info(String.format("...compiled in %d ms", elapsed));
            var lines = task.root().getLineMap();
            var cursor =
                    lines.getPosition(
                            params.range.start.line + 1, params.range.start.character + 1);
            for (var e : overrideInheritedMethods(task, file, cursor).entrySet()) {
                actions.add(lazyAction(e.getKey(), CodeActionKind.QuickFix, e.getValue(), null));
            }
            if (wants(params.context.only, CodeActionKind.RefactorExtract)) {
                var extract = extractVariable(task, file, params.range);
                if (extract != null) {
                    actions.add(
                            lazyAction(
                                    "Extract variable",
                                    CodeActionKind.RefactorExtract,
                                    extract,
                                    null));
                }
                var constant = extractConstant(task, file, params.range);
                if (constant != null) {
                    actions.add(
                            lazyAction(
                                    "Extract constant",
                                    CodeActionKind.RefactorExtract,
                                    constant,
                                    null));
                }
                var extractedMethod = extractMethod(task, file, params.range);
                if (extractedMethod != null) {
                    actions.add(
                            lazyAction(
                                    "Extract method",
                                    CodeActionKind.RefactorExtract,
                                    extractedMethod,
                                    null));
                }
            }
            if (wants(params.context.only, CodeActionKind.RefactorInline)
                    && InlineVariable.canInline(task, (int) cursor)) {
                var d = new JsonObject();
                d.addProperty("type", "InlineVariable");
                d.addProperty("file", file.toString());
                d.addProperty("position", (int) cursor);
                actions.add(lazyAction("Inline variable", CodeActionKind.RefactorInline, d, null));
            }
            if (wants(params.context.only, CodeActionKind.RefactorInline)
                    && InlineMethod.canInline(task, (int) cursor)) {
                var d = new JsonObject();
                d.addProperty("type", "InlineMethod");
                d.addProperty("file", file.toString());
                d.addProperty("position", (int) cursor);
                actions.add(lazyAction("Inline method", CodeActionKind.RefactorInline, d, null));
            }
            if (wants(params.context.only, CodeActionKind.RefactorInline)
                    && InlineField.canInline(task, (int) cursor)) {
                var d = new JsonObject();
                d.addProperty("type", "InlineField");
                d.addProperty("file", file.toString());
                d.addProperty("position", (int) cursor);
                actions.add(lazyAction("Inline field", CodeActionKind.RefactorInline, d, null));
            }
            if (wants(params.context.only, CodeActionKind.RefactorRewrite)) {
                var current = ChangeMethodAccess.currentAccess(task, (int) cursor);
                if (current != null) {
                    for (var level : ACCESS_LEVELS) {
                        if (level[0].equals(current)) continue;
                        var d = new JsonObject();
                        d.addProperty("type", "ChangeMethodAccess");
                        d.addProperty("file", file.toString());
                        d.addProperty("position", (int) cursor);
                        d.addProperty("access", level[0]);
                        actions.add(
                                lazyAction(
                                        "Make method " + level[1],
                                        CodeActionKind.RefactorRewrite,
                                        d,
                                        null));
                    }
                }
                if (ReplaceConstructorWithFactoryMethod.canReplace(task, (int) cursor)) {
                    var d = new JsonObject();
                    d.addProperty("type", "ReplaceConstructorWithFactoryMethod");
                    d.addProperty("file", file.toString());
                    d.addProperty("position", (int) cursor);
                    actions.add(
                            lazyAction(
                                    "Replace constructor with factory method",
                                    CodeActionKind.RefactorRewrite,
                                    d,
                                    null));
                }
                if (AddParameter.canAdd(task, (int) cursor)) {
                    var d = new JsonObject();
                    d.addProperty("type", "AddParameter");
                    d.addProperty("file", file.toString());
                    d.addProperty("position", (int) cursor);
                    actions.add(
                            lazyAction(
                                    "Add parameter to method",
                                    CodeActionKind.RefactorRewrite,
                                    d,
                                    null));
                }
                if (RemoveParameter.canRemove(task, (int) cursor)) {
                    var d = new JsonObject();
                    d.addProperty("type", "RemoveParameter");
                    d.addProperty("file", file.toString());
                    d.addProperty("position", (int) cursor);
                    actions.add(
                            lazyAction(
                                    "Remove unused parameter",
                                    CodeActionKind.RefactorRewrite,
                                    d,
                                    null));
                }
            }
        }
        var elapsed = Duration.between(started, Instant.now()).toMillis();
        LOG.info(String.format("...created %d actions in %d ms", actions.size(), elapsed));
        return actions;
    }

    /** Source actions (member generation) offered independent of diagnostics. */
    public List<CodeAction> sourceActions(CodeActionParams params) {
        var only = params.context.only;
        var actions = new ArrayList<CodeAction>();
        var file = Paths.get(params.textDocument.uri);
        if (wants(only, CodeActionKind.SourceOrganizeImports)) {
            actions.add(
                    lazyAction(
                            "Organize imports",
                            CodeActionKind.SourceOrganizeImports,
                            descFile("AutoFixImports", file),
                            null));
        }
        if (wants(only, CodeActionKind.Source)) {
            String simpleName = null;
            try (var task = compiler.compile(file)) {
                simpleName = findClassForSource(task, params.range);
            }
            if (simpleName != null) {
                actions.add(
                        lazyAction(
                                "Generate constructor",
                                CodeActionKind.Source,
                                descFileName("GenerateConstructor", file, simpleName),
                                null));
                actions.add(
                        lazyAction(
                                "Generate getters and setters",
                                CodeActionKind.Source,
                                descFileName("GenerateGettersAndSetters", file, simpleName),
                                null));
                actions.add(
                        lazyAction(
                                "Add missing @Override annotations",
                                CodeActionKind.Source,
                                descFile("AutoAddOverrides", file),
                                null));
            }
        }
        return actions;
    }

    /**
     * Compute the {@code edit} for a code action listed lazily, reconstructing the rewrite from the
     * opaque {@code data} descriptor the client echoed back. Actions that already carry an {@code
     * edit} (diagnostic quick fixes) have no {@code data} and pass through unchanged.
     */
    public CodeAction resolve(CodeAction action) {
        if (action.data == null || !action.data.isJsonObject()) return action;
        var rewrite = rewriteFromData(action.data.getAsJsonObject());
        var edits = rewrite.rewrite(compiler);
        if (edits == Rewrite.CANCELLED) return action;
        var edit = new WorkspaceEdit();
        for (var f : edits.keySet()) {
            edit.changes.put(f.toUri(), List.of(edits.get(f)));
        }
        action.edit = edit;
        return action;
    }

    /** Whether an action of {@code kind} was requested by the client's {@code only} filter. */
    private boolean wants(List<String> only, String kind) {
        if (only == null || only.isEmpty()) return true;
        for (var k : only) {
            if (kind.equals(k) || kind.startsWith(k + ".")) return true;
        }
        return false;
    }

    private String findClassForSource(CompileTask task, Range range) {
        var byCursor = findClassTree(task, range);
        if (byCursor != null) return byCursor.getSimpleName().toString();
        for (var decl : task.root().getTypeDecls()) {
            if (decl instanceof ClassTree) return ((ClassTree) decl).getSimpleName().toString();
        }
        return null;
    }

    private Map<String, JsonObject> overrideInheritedMethods(
            CompileTask task, Path file, long cursor) {
        if (!isBlankLine(task.root(), cursor)) return Map.of();
        if (isInMethod(task, cursor)) return Map.of();
        var methodTree = new FindMethodDeclarationAt(task.task).scan(task.root(), cursor);
        if (methodTree != null) return Map.of();
        var actions = new TreeMap<String, JsonObject>();
        var trees = Trees.instance(task.task);
        var classTree = new FindTypeDeclarationAt(task.task).scan(task.root(), cursor);
        if (classTree == null) return Map.of();
        var classPath = trees.getPath(task.root(), classTree);
        var elements = task.task.getElements();
        var classElement = (TypeElement) trees.getElement(classPath);
        for (var member : elements.getAllMembers(classElement)) {
            if (member.getModifiers().contains(Modifier.FINAL)) continue;
            if (member.getKind() != ElementKind.METHOD) continue;
            var method = (ExecutableElement) member;
            var methodSource = (TypeElement) member.getEnclosingElement();
            if (methodSource.getQualifiedName().contentEquals("java.lang.Object")) continue;
            if (methodSource.equals(classElement)) continue;
            var ptr = new MethodPtr(task.task, method);
            var data = descMethod("OverrideInheritedMethod", ptr);
            data.addProperty("file", file.toString());
            data.addProperty("position", (int) cursor);
            var title = "Override '" + method.getSimpleName() + "' from " + ptr.className;
            actions.put(title, data);
        }
        return actions;
    }

    private boolean isInMethod(CompileTask task, long cursor) {
        var method = new FindMethodDeclarationAt(task.task).scan(task.root(), cursor);
        return method != null;
    }

    private boolean isBlankLine(CompilationUnitTree root, long cursor) {
        var lines = root.getLineMap();
        var line = lines.getLineNumber(cursor);
        var start = lines.getStartPosition(line);
        CharSequence contents;
        try {
            contents = root.getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        for (var i = start; i < cursor; i++) {
            if (!Character.isWhitespace(contents.charAt((int) i))) {
                return false;
            }
        }
        return true;
    }

    public List<CodeAction> codeActionForDiagnostics(CodeActionParams params) {
        LOG.info(
                String.format(
                        "Check %d diagnostics for quick fixes...",
                        params.context.diagnostics.size()));
        var started = Instant.now();
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler.compile(file)) {
            var actions = new ArrayList<CodeAction>();
            for (var d : params.context.diagnostics) {
                var newActions = codeActionForDiagnostic(task, file, d);
                actions.addAll(newActions);
            }
            var elapsed = Duration.between(started, Instant.now()).toMillis();
            LOG.info(String.format("...created %d quick fixes in %d ms", actions.size(), elapsed));
            return actions;
        }
    }

    private List<CodeAction> codeActionForDiagnostic(CompileTask task, Path file, Diagnostic d) {
        // Quick fixes stay eager: a rewrite that returns CANCELLED is not applicable, and that can
        // only be known by running it, so we must not offer an unresolvable fix.
        switch (d.code) {
            case "unused_local":
                var toStatement =
                        new ConvertVariableToStatement(file, findPosition(task, d.range.start));
                return createQuickFix("Convert to statement", toStatement);
            case "unused_field":
                var toBlock = new ConvertFieldToBlock(file, findPosition(task, d.range.start));
                return createQuickFix("Convert to block", toBlock);
            case "unused_class":
                var removeClass = new RemoveClass(file, findPosition(task, d.range.start));
                return createQuickFix("Remove class", removeClass);
            case "unused_method":
                var unusedMethod = findMethod(task, d.range);
                var removeMethod =
                        new RemoveMethod(
                                unusedMethod.className,
                                unusedMethod.methodName,
                                unusedMethod.erasedParameterTypes);
                return createQuickFix("Remove method", removeMethod);
            case "unused_throws":
                var shortExceptionName = extractRange(task, d.range);
                var notThrown = extractNotThrownExceptionName(d.message);
                var methodWithExtraThrow = findMethod(task, d.range);
                var removeThrow =
                        new RemoveException(
                                methodWithExtraThrow.className,
                                methodWithExtraThrow.methodName,
                                methodWithExtraThrow.erasedParameterTypes,
                                notThrown);
                return createQuickFix("Remove '" + shortExceptionName + "'", removeThrow);
            case "compiler.warn.unchecked.call.mbr.of.raw.type":
                var warnedMethod = findMethod(task, d.range);
                var suppressWarning =
                        new AddSuppressWarningAnnotation(
                                warnedMethod.className,
                                warnedMethod.methodName,
                                warnedMethod.erasedParameterTypes);
                return createQuickFix("Suppress 'unchecked' warning", suppressWarning);
            case "compiler.err.unreported.exception.need.to.catch.or.throw":
                var needsThrow = findMethod(task, d.range);
                var exceptionName = extractExceptionName(d.message);
                var addThrows =
                        new AddException(
                                needsThrow.className,
                                needsThrow.methodName,
                                needsThrow.erasedParameterTypes,
                                exceptionName);
                var actionsForException =
                        new ArrayList<CodeAction>(createQuickFix("Add 'throws'", addThrows));
                var surroundWithCatch =
                        new CatchException(file, findPosition(task, d.range.start), exceptionName);
                actionsForException.addAll(
                        createQuickFix("Surround with try/catch", surroundWithCatch));
                return actionsForException;
            case "compiler.err.cant.resolve":
            case "compiler.err.cant.resolve.location":
                var simpleName = extractRange(task, d.range);
                var allImports = new ArrayList<CodeAction>();
                for (var qualifiedName : compiler.publicTopLevelTypes()) {
                    if (qualifiedName.endsWith("." + simpleName)) {
                        var title = "Import '" + qualifiedName + "'";
                        var addImport = new AddImport(file, qualifiedName);
                        allImports.addAll(createQuickFix(title, addImport));
                    }
                }
                var fieldName =
                        simpleName.toString().substring(simpleName.toString().lastIndexOf('.') + 1);
                var createField = new CreateMissingField(file, findPosition(task, d.range.start));
                allImports.addAll(createQuickFix("Create field '" + fieldName + "'", createField));
                return allImports;
            case "compiler.err.var.not.initialized.in.default.constructor":
                var needsConstructor = findClassNeedingConstructor(task, d.range);
                if (needsConstructor == null) return List.of();
                var generateConstructor = new GenerateRecordConstructor(needsConstructor);
                return createQuickFix("Generate constructor", generateConstructor);
            case "compiler.err.does.not.override.abstract":
                var missingAbstracts = findClass(task, d.range);
                var implementAbstracts = new ImplementAbstractMethods(missingAbstracts);
                return createQuickFix("Implement abstract methods", implementAbstracts);
            case "compiler.err.cant.resolve.location.args":
                var missingMethod =
                        new CreateMissingMethod(file, findPosition(task, d.range.start));
                return createQuickFix("Create missing method", missingMethod);
            default:
                return List.of();
        }
    }

    private int findPosition(CompileTask task, Position position) {
        var lines = task.root().getLineMap();
        return (int) lines.getPosition(position.line + 1, position.character + 1);
    }

    private String findClassNeedingConstructor(CompileTask task, Range range) {
        var type = findClassTree(task, range);
        if (type == null || hasConstructor(task, type)) return null;
        return qualifiedName(task, type);
    }

    private String findClass(CompileTask task, Range range) {
        var type = findClassTree(task, range);
        if (type == null) return null;
        return qualifiedName(task, type);
    }

    private ClassTree findClassTree(CompileTask task, Range range) {
        var position =
                task.root()
                        .getLineMap()
                        .getPosition(range.start.line + 1, range.start.character + 1);
        return new FindTypeDeclarationAt(task.task).scan(task.root(), position);
    }

    private String qualifiedName(CompileTask task, ClassTree tree) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(task.root(), tree);
        var type = (TypeElement) trees.getElement(path);
        return type.getQualifiedName().toString();
    }

    private boolean hasConstructor(CompileTask task, ClassTree type) {
        for (var member : type.getMembers()) {
            if (member instanceof MethodTree) {
                var method = (MethodTree) member;
                if (isConstructor(task, method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isConstructor(CompileTask task, MethodTree method) {
        return method.getName().contentEquals("<init>") && !synthentic(task, method);
    }

    private boolean synthentic(CompileTask task, MethodTree method) {
        return Trees.instance(task.task).getSourcePositions().getStartPosition(task.root(), method)
                != -1;
    }

    private MethodPtr findMethod(CompileTask task, Range range) {
        var trees = Trees.instance(task.task);
        var position =
                task.root()
                        .getLineMap()
                        .getPosition(range.start.line + 1, range.start.character + 1);
        var tree = new FindMethodDeclarationAt(task.task).scan(task.root(), position);
        var path = trees.getPath(task.root(), tree);
        var method = (ExecutableElement) trees.getElement(path);
        return new MethodPtr(task.task, method);
    }

    class MethodPtr {
        String className, methodName;
        String[] erasedParameterTypes;

        MethodPtr(JavacTask task, ExecutableElement method) {
            var types = task.getTypes();
            var parent = (TypeElement) method.getEnclosingElement();
            className = parent.getQualifiedName().toString();
            methodName = method.getSimpleName().toString();
            erasedParameterTypes = new String[method.getParameters().size()];
            for (var i = 0; i < erasedParameterTypes.length; i++) {
                var param = method.getParameters().get(i);
                var type = param.asType();
                var erased = types.erasure(type);
                erasedParameterTypes[i] = erased.toString();
            }
        }
    }

    private static final Pattern NOT_THROWN_EXCEPTION =
            Pattern.compile("^'((\\w+\\.)*\\w+)' is not thrown");

    private String extractNotThrownExceptionName(String message) {
        var matcher = NOT_THROWN_EXCEPTION.matcher(message);
        if (!matcher.find()) {
            LOG.warning(String.format("`%s` doesn't match `%s`", message, NOT_THROWN_EXCEPTION));
            return "";
        }
        return matcher.group(1);
    }

    private static final Pattern UNREPORTED_EXCEPTION =
            Pattern.compile("unreported exception ((\\w+\\.)*\\w+)");

    private String extractExceptionName(String message) {
        var matcher = UNREPORTED_EXCEPTION.matcher(message);
        if (!matcher.find()) {
            LOG.warning(String.format("`%s` doesn't match `%s`", message, UNREPORTED_EXCEPTION));
            return "";
        }
        return matcher.group(1);
    }

    private CharSequence extractRange(CompileTask task, Range range) {
        CharSequence contents;
        try {
            contents = task.root().getSourceFile().getCharContent(true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var start =
                (int)
                        task.root()
                                .getLineMap()
                                .getPosition(range.start.line + 1, range.start.character + 1);
        var end =
                (int)
                        task.root()
                                .getLineMap()
                                .getPosition(range.end.line + 1, range.end.character + 1);
        return contents.subSequence(start, end);
    }

    private List<CodeAction> createQuickFix(String title, Rewrite rewrite) {
        var edits = rewrite.rewrite(compiler);
        if (edits == Rewrite.CANCELLED) {
            return List.of();
        }
        var a = new CodeAction();
        a.kind = CodeActionKind.QuickFix;
        a.title = title;
        a.edit = new WorkspaceEdit();
        for (var file : edits.keySet()) {
            a.edit.changes.put(file.toUri(), List.of(edits.get(file)));
        }
        return List.of(a);
    }

    // ---- lazy action + resolve descriptor plumbing (cursor + source actions) ----

    private CodeAction lazyAction(
            String title, String kind, JsonObject data, List<Diagnostic> diagnostics) {
        var a = new CodeAction();
        a.title = title;
        a.kind = kind;
        a.data = data;
        if (diagnostics != null) a.diagnostics = diagnostics;
        return a;
    }

    private static JsonObject descFile(String type, Path file) {
        var d = new JsonObject();
        d.addProperty("type", type);
        d.addProperty("file", file.toString());
        return d;
    }

    private static JsonObject descFileName(String type, Path file, String name) {
        var d = descFile(type, file);
        d.addProperty("name", name);
        return d;
    }

    private static JsonObject descMethod(String type, MethodPtr method) {
        var d = new JsonObject();
        d.addProperty("type", type);
        d.addProperty("className", method.className);
        d.addProperty("methodName", method.methodName);
        var erased = new JsonArray();
        for (var p : method.erasedParameterTypes) erased.add(p);
        d.add("erasedParameterTypes", erased);
        return d;
    }

    /**
     * Descriptor for extracting the expression exactly spanning the selection into a local
     * variable, or null when the selection is not a value-producing expression inside a block.
     */
    private JsonObject extractVariable(CompileTask task, Path file, Range range) {
        if (range.start.line == range.end.line && range.start.character == range.end.character) {
            return null;
        }
        var trees = Trees.instance(task.task);
        var pos = trees.getSourcePositions();
        var root = task.root();
        var lines = root.getLineMap();
        var start = (int) lines.getPosition(range.start.line + 1, range.start.character + 1);
        var end = (int) lines.getPosition(range.end.line + 1, range.end.character + 1);
        if (end <= start) return null;
        var expr = findExpression(root, pos, start, end);
        if (expr == null) return null;
        var path = trees.getPath(root, expr);
        if (!inBlock(path)) return null;
        var type = trees.getTypeMirror(path);
        if (type == null) return null;
        switch (type.getKind()) {
            case VOID:
            case NONE:
            case ERROR:
            case PACKAGE:
            case EXECUTABLE:
                return null;
            default:
                break;
        }
        var d = new JsonObject();
        d.addProperty("type", "ExtractVariable");
        d.addProperty("file", file.toString());
        d.addProperty("start", start);
        d.addProperty("end", end);
        return d;
    }

    /**
     * Descriptor for extracting the expression exactly spanning the selection into a {@code private
     * static final} field, or null when the selection is not a static-initializer-legal expression.
     */
    private JsonObject extractConstant(CompileTask task, Path file, Range range) {
        if (range.start.line == range.end.line && range.start.character == range.end.character) {
            return null;
        }
        var lines = task.root().getLineMap();
        var start = (int) lines.getPosition(range.start.line + 1, range.start.character + 1);
        var end = (int) lines.getPosition(range.end.line + 1, range.end.character + 1);
        if (end <= start) return null;
        if (!ExtractConstant.canExtract(task, start, end)) return null;
        var d = new JsonObject();
        d.addProperty("type", "ExtractConstant");
        d.addProperty("file", file.toString());
        d.addProperty("start", start);
        d.addProperty("end", end);
        return d;
    }

    /**
     * Descriptor for extracting the statements covered by the selection into a new method, or null
     * when the selection is not a safely-extractable run of whole statements.
     */
    private JsonObject extractMethod(CompileTask task, Path file, Range range) {
        if (range.start.line == range.end.line && range.start.character == range.end.character) {
            return null;
        }
        var lines = task.root().getLineMap();
        var start = (int) lines.getPosition(range.start.line + 1, range.start.character + 1);
        var end = (int) lines.getPosition(range.end.line + 1, range.end.character + 1);
        if (end <= start) return null;
        if (!ExtractMethod.canExtract(task, start, end)) return null;
        var d = new JsonObject();
        d.addProperty("type", "ExtractMethod");
        d.addProperty("file", file.toString());
        d.addProperty("start", start);
        d.addProperty("end", end);
        return d;
    }

    private static ExpressionTree findExpression(
            CompilationUnitTree root, SourcePositions pos, int start, int end) {
        var result = new ExpressionTree[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (result[0] == null && tree instanceof ExpressionTree) {
                    var s = pos.getStartPosition(root, tree);
                    var e = pos.getEndPosition(root, tree);
                    if (s == start && e == end) {
                        result[0] = (ExpressionTree) tree;
                    }
                }
                return super.scan(tree, p);
            }
        }.scan(root, null);
        return result[0];
    }

    private static boolean inBlock(TreePath path) {
        for (var p = path; p != null && p.getParentPath() != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof StatementTree
                    && p.getParentPath().getLeaf() instanceof BlockTree) {
                return true;
            }
        }
        return false;
    }

    private Rewrite rewriteFromData(JsonObject d) {
        var type = d.get("type").getAsString();
        switch (type) {
            case "AutoFixImports":
                return new AutoFixImports(dataPath(d));
            case "AutoAddOverrides":
                return new AutoAddOverrides(dataPath(d));
            case "GenerateConstructor":
                return new GenerateConstructor(dataPath(d), d.get("name").getAsString());
            case "GenerateGettersAndSetters":
                return new GenerateGettersAndSetters(dataPath(d), d.get("name").getAsString());
            case "ExtractVariable":
                return new ExtractVariable(
                        dataPath(d), d.get("start").getAsInt(), d.get("end").getAsInt());
            case "ExtractConstant":
                return new ExtractConstant(
                        dataPath(d), d.get("start").getAsInt(), d.get("end").getAsInt());
            case "ExtractMethod":
                return new ExtractMethod(
                        dataPath(d), d.get("start").getAsInt(), d.get("end").getAsInt());
            case "InlineVariable":
                return new InlineVariable(dataPath(d), d.get("position").getAsInt());
            case "InlineMethod":
                return new InlineMethod(dataPath(d), d.get("position").getAsInt());
            case "InlineField":
                return new InlineField(dataPath(d), d.get("position").getAsInt());
            case "ChangeMethodAccess":
                return new ChangeMethodAccess(
                        dataPath(d), d.get("position").getAsInt(), d.get("access").getAsString());
            case "ReplaceConstructorWithFactoryMethod":
                return new ReplaceConstructorWithFactoryMethod(
                        dataPath(d), d.get("position").getAsInt());
            case "AddParameter":
                return new AddParameter(dataPath(d), d.get("position").getAsInt());
            case "RemoveParameter":
                return new RemoveParameter(dataPath(d), d.get("position").getAsInt());
            case "OverrideInheritedMethod":
                return new OverrideInheritedMethod(
                        d.get("className").getAsString(),
                        d.get("methodName").getAsString(),
                        dataErased(d),
                        dataPath(d),
                        d.get("position").getAsInt());
            default:
                LOG.warning("Unknown code action data type: " + type);
                return Rewrite.NOT_SUPPORTED;
        }
    }

    /** Access levels in menu order: {modifier keyword, display label}. */
    private static final String[][] ACCESS_LEVELS = {
        {"public", "public"},
        {"protected", "protected"},
        {"", "package-private"},
        {"private", "private"},
    };

    private static Path dataPath(JsonObject d) {
        return Paths.get(d.get("file").getAsString());
    }

    private static String[] dataErased(JsonObject d) {
        var arr = d.getAsJsonArray("erasedParameterTypes");
        var xs = new String[arr.size()];
        for (var i = 0; i < xs.length; i++) xs[i] = arr.get(i).getAsString();
        return xs;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
