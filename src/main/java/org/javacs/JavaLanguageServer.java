package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;

import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.SignatureProvider;
import org.javacs.embed.ClasspathProvider;
import org.javacs.embed.JavaFormatter;
import org.javacs.fold.FoldProvider;
import org.javacs.hover.HoverProvider;
import org.javacs.index.SymbolProvider;
import org.javacs.lens.CodeLensProvider;
import org.javacs.lsp.*;
import org.javacs.markup.ColorProvider;
import org.javacs.markup.ErrorProvider;
import org.javacs.markup.SemanticTokensProvider;
import org.javacs.navigation.CallHierarchyProvider;
import org.javacs.navigation.DefinitionProvider;
import org.javacs.navigation.DocumentHighlightProvider;
import org.javacs.navigation.ImplementationProvider;
import org.javacs.navigation.InlayHintProvider;
import org.javacs.navigation.ReferenceProvider;
import org.javacs.navigation.SelectionRangeProvider;
import org.javacs.navigation.TypeDefinitionProvider;
import org.javacs.navigation.TypeHierarchyProvider;
import org.javacs.rewrite.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

import javax.lang.model.element.*;

public class JavaLanguageServer extends LanguageServer {
    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private final LanguageClient client;
    private JavaCompilerService cacheCompiler;
    private JsonObject cacheSettings;
    private JsonObject settings = new JsonObject();
    private boolean modifiedBuild = true;
    private final ClasspathProvider classpathProvider;
    private final JavaFormatter formatter;

    JavaCompilerService compiler() {
        if (needsCompiler()) {
            cacheCompiler = createCompiler();
            cacheSettings = settings;
            modifiedBuild = false;
        }
        return cacheCompiler;
    }

    private boolean needsCompiler() {
        if (modifiedBuild) {
            return true;
        }
        if (!settings.equals(cacheSettings)) {
            LOG.info("Settings\n\t" + settings + "\nis different than\n\t" + cacheSettings);
            return true;
        }
        return false;
    }

    void lint(Collection<Path> files) {
        if (files.isEmpty()) return;
        LOG.info("Lint " + files.size() + " files...");
        var started = Instant.now();
        try (var task = compiler().compile(files.toArray(Path[]::new))) {
            var compiled = Instant.now();
            LOG.info("...compiled in " + Duration.between(started, compiled).toMillis() + " ms");
            for (var errs : new ErrorProvider(task).errors()) {
                client.publishDiagnostics(errs);
            }
            for (var colors : new ColorProvider(task).colors()) {
                client.customNotification("java/colors", GSON.toJsonTree(colors));
            }
            var published = Instant.now();
            LOG.info("...published in " + Duration.between(started, published).toMillis() + " ms");
        }
    }

    @Override
    public DocumentDiagnosticReport documentDiagnostics(DocumentDiagnosticParams params) {
        var report = new DocumentDiagnosticReport();
        if (!FileStore.isJavaFile(params.textDocument.uri)) return report;
        var file = Paths.get(params.textDocument.uri);
        // compile(file) yields exactly this file as the sole root; aggregate its diagnostics.
        try (var task = compiler().compile(file)) {
            for (var errs : new ErrorProvider(task).errors()) {
                report.items.addAll(errs.diagnostics);
            }
        }
        return report;
    }

    @Override
    public WorkspaceDiagnosticReport workspaceDiagnostics(WorkspaceDiagnosticParams params) {
        var report = new WorkspaceDiagnosticReport();
        var files = new ArrayList<>(FileStore.all());
        if (files.isEmpty()) return report;
        LOG.info("Workspace diagnostics over " + files.size() + " files...");
        try (var task = compiler().compile(files.toArray(Path[]::new))) {
            // One full report per compiled document, so clean files clear stale diagnostics too.
            for (var errs : new ErrorProvider(task).errors()) {
                var item = new WorkspaceDocumentDiagnosticReport();
                item.uri = errs.uri;
                item.items = errs.diagnostics;
                report.items.add(item);
            }
        }
        return report;
    }

    private void javaStartProgress(JavaStartProgressParams params) {
        client.customNotification("java/startProgress", GSON.toJsonTree(params));
    }

    private void javaReportProgress(JavaReportProgressParams params) {
        client.customNotification("java/reportProgress", GSON.toJsonTree(params));
    }

    private void javaEndProgress() {
        client.customNotification("java/endProgress", JsonNull.INSTANCE);
    }

    private JavaCompilerService createCompiler() {
        Objects.requireNonNull(
                workspaceRoot,
                "Can't create compiler because workspaceRoot has not been initialized");

        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"));

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var extraArgs = extraCompilerArgs();
        var addExports = addExports();
        // An embedding host (e.g. Elide) supplies the resolved classpath directly; it takes
        // precedence over user settings and built-in inference.
        if (classpathProvider != null) {
            var provided = classpathProvider.classpath(workspaceRoot);
            if (!provided.isEmpty()) {
                javaEndProgress();
                return new JavaCompilerService(
                        provided, classpathProvider.docPath(workspaceRoot), addExports, extraArgs);
            }
        }
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            javaEndProgress();
            return new JavaCompilerService(classPath, docPath(), addExports, extraArgs);
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            javaReportProgress(new JavaReportProgressParams("Inferring class path"));
            classPath = infer.classPath();

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
            var docPath = infer.buildDocPath();

            javaEndProgress();
            return new JavaCompilerService(classPath, docPath, addExports, extraArgs);
        }
    }

    private Set<String> externalDependencies() {
        if (!settings.has("externalDependencies")) return Set.of();
        var array = settings.getAsJsonArray("externalDependencies");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    private Set<Path> classPath() {
        if (!settings.has("classPath")) return Set.of();
        var array = settings.getAsJsonArray("classPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    private List<String> extraCompilerArgs() {
        if (!settings.has("extraCompilerArgs")) return List.of();
        var array = settings.getAsJsonArray("extraCompilerArgs");
        var args = new ArrayList<String>();
        for (var each : array) {
            for (var arg : each.getAsString().split("\\s+")) {
                if (!arg.isEmpty()) {
                    args.add(arg);
                }
            }
        }
        return args;
    }

    private Set<Path> docPath() {
        if (!settings.has("docPath")) return Set.of();
        var array = settings.getAsJsonArray("docPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    private Set<String> addExports() {
        if (!settings.has("addExports")) return Set.of();
        var array = settings.getAsJsonArray("addExports");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        FileStore.setWorkspaceRoots(Set.of(Paths.get(params.rootUri)));

        var c = new JsonObject();
        c.addProperty("textDocumentSync", 2); // Incremental
        c.addProperty("hoverProvider", true);
        var completionOptions = new JsonObject();
        completionOptions.addProperty("resolveProvider", true);
        var triggerCharacters = new JsonArray();
        triggerCharacters.add(".");
        completionOptions.add("triggerCharacters", triggerCharacters);
        c.add("completionProvider", completionOptions);
        var signatureHelpOptions = new JsonObject();
        var signatureTrigger = new JsonArray();
        signatureTrigger.add("(");
        signatureTrigger.add(",");
        signatureHelpOptions.add("triggerCharacters", signatureTrigger);
        c.add("signatureHelpProvider", signatureHelpOptions);
        c.addProperty("referencesProvider", true);
        c.addProperty("definitionProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        codeLensOptions.addProperty("resolveProvider", true);
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);
        var codeActionOptions = new JsonObject();
        codeActionOptions.addProperty("resolveProvider", true);
        c.add("codeActionProvider", codeActionOptions);
        var renameOptions = new JsonObject();
        renameOptions.addProperty("prepareProvider", true);
        c.add("renameProvider", renameOptions);
        c.addProperty("implementationProvider", true);
        c.addProperty("typeDefinitionProvider", true);
        c.addProperty("declarationProvider", true);
        c.addProperty("documentHighlightProvider", true);
        c.addProperty("selectionRangeProvider", true);
        var semanticTokensOptions = new JsonObject();
        var semanticTokensLegend = new JsonObject();
        var tokenTypes = new JsonArray();
        for (var type : SemanticTokensProvider.TOKEN_TYPES) {
            tokenTypes.add(type);
        }
        semanticTokensLegend.add("tokenTypes", tokenTypes);
        semanticTokensLegend.add("tokenModifiers", new JsonArray());
        semanticTokensOptions.add("legend", semanticTokensLegend);
        var semanticTokensFull = new JsonObject();
        semanticTokensFull.addProperty("delta", true);
        semanticTokensOptions.add("full", semanticTokensFull);
        semanticTokensOptions.addProperty("range", true);
        c.add("semanticTokensProvider", semanticTokensOptions);
        c.addProperty("inlayHintProvider", true);
        c.addProperty("callHierarchyProvider", true);
        c.addProperty("typeHierarchyProvider", true);
        var diagnosticOptions = new JsonObject();
        // Java diagnostics for one file depend on other files (types, supertypes, imports).
        diagnosticOptions.addProperty("interFileDependencies", true);
        diagnosticOptions.addProperty("workspaceDiagnostics", true);
        c.add("diagnosticProvider", diagnosticOptions);

        return new InitializeResult(c);
    }

    private static final String[] watchFiles = {
        "**/*.java", "**/pom.xml", "**/BUILD", "**/javaconfig.json", "**/WORKSPACE"
    };

    @Override
    public void initialized() {
        client.registerCapability("workspace/didChangeWatchedFiles", watchFiles(watchFiles));
    }

    private JsonObject watchFiles(String... globPatterns) {
        var options = new JsonObject();
        var watchers = new JsonArray();
        for (var p : globPatterns) {
            var config = new JsonObject();
            config.addProperty("globPattern", p);
            watchers.add(config);
        }
        options.add("watchers", watchers);
        return options;
    }

    @Override
    public void shutdown() {}

    public JavaLanguageServer(LanguageClient client) {
        this(client, null, null);
    }

    public JavaLanguageServer(
            LanguageClient client, ClasspathProvider classpathProvider, JavaFormatter formatter) {
        this.client = client;
        this.classpathProvider = classpathProvider;
        this.formatter = formatter;
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        return new SymbolProvider(compiler()).findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info("Received java settings " + java);
        settings = java.getAsJsonObject();
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        for (var c : params.changes) {
            var file = Paths.get(c.uri);
            if (FileStore.isJavaFile(file)) {
                switch (c.type) {
                    case FileChangeType.Created:
                        FileStore.externalCreate(file);
                        break;
                    case FileChangeType.Changed:
                        FileStore.externalChange(file);
                        break;
                    case FileChangeType.Deleted:
                        removeClass(file);
                        break;
                }
                continue;
            }
            var name = file.getFileName().toString();
            switch (name) {
                case "BUILD":
                case "pom.xml":
                    LOG.info("Compiler needs to be re-created because " + file + " has changed");
                    modifiedBuild = true;
            }
        }
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var provider = new CompletionProvider(compiler());
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        new HoverProvider(compiler()).resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var list = new HoverProvider(compiler()).hover(file, line, column);
        if (list == HoverProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        // TODO add range
        return Optional.of(new Hover(list));
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var line = params.position.line + 1;
        var column = params.position.character + 1;
        var help = new SignatureProvider(compiler()).signatureHelp(file, line, column);
        if (help == SignatureProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(help);
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new DefinitionProvider(compiler(), file, line, column).find();
        if (found == DefinitionProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new ReferenceProvider(compiler(), file, line, column).find();
        if (found == ReferenceProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> findImplementations(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new ImplementationProvider(compiler(), file, line, column).find();
        if (found == ImplementationProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> typeDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new TypeDefinitionProvider(compiler(), file, line, column).find();
        if (found == TypeDefinitionProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> declaration(TextDocumentPositionParams position) {
        return gotoDefinition(position);
    }

    @Override
    public List<DocumentHighlight> documentHighlight(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return List.of();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        return new DocumentHighlightProvider(compiler(), file, line, column).find();
    }

    @Override
    public List<SelectionRange> selectionRange(SelectionRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new SelectionRangeProvider(compiler(), file).find(params.positions);
    }

    @Override
    public Optional<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var data = new SemanticTokensProvider(compiler()).tokens(file);
        var tokens = new SemanticTokens(data);
        tokens.resultId = nextSemanticTokensResultId();
        return Optional.of(tokens);
    }

    @Override
    public Optional<SemanticTokens> semanticTokensRange(SemanticTokensRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var data = new SemanticTokensProvider(compiler()).tokensInRange(file, params.range);
        var tokens = new SemanticTokens(data);
        tokens.resultId = nextSemanticTokensResultId();
        return Optional.of(tokens);
    }

    @Override
    public Optional<SemanticTokens> semanticTokensFullDelta(SemanticTokensDeltaParams params) {
        // We don't compute token diffs; a full token set with a fresh resultId is a spec-permitted
        // response to a delta request (SemanticTokens instead of SemanticTokensDelta).
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var data = new SemanticTokensProvider(compiler()).tokens(file);
        var tokens = new SemanticTokens(data);
        tokens.resultId = nextSemanticTokensResultId();
        return Optional.of(tokens);
    }

    private long semanticTokensResultId = 0;

    private String nextSemanticTokensResultId() {
        return Long.toString(++semanticTokensResultId);
    }

    @Override
    public List<InlayHint> inlayHint(InlayHintParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new InlayHintProvider(compiler(), file).inlayHints(params.range);
    }

    @Override
    public List<CallHierarchyItem> prepareCallHierarchy(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return List.of();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        return new CallHierarchyProvider(compiler()).prepare(file, line, column);
    }

    @Override
    public List<CallHierarchyIncomingCall> callHierarchyIncoming(
            CallHierarchyIncomingCallsParams params) {
        if (params.item == null || !FileStore.isJavaFile(params.item.uri)) return List.of();
        return new CallHierarchyProvider(compiler()).incoming(params.item);
    }

    @Override
    public List<CallHierarchyOutgoingCall> callHierarchyOutgoing(
            CallHierarchyOutgoingCallsParams params) {
        if (params.item == null || !FileStore.isJavaFile(params.item.uri)) return List.of();
        return new CallHierarchyProvider(compiler()).outgoing(params.item);
    }

    @Override
    public List<TypeHierarchyItem> prepareTypeHierarchy(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return List.of();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        return new TypeHierarchyProvider(compiler()).prepare(file, line, column);
    }

    @Override
    public List<TypeHierarchyItem> typeHierarchySupertypes(TypeHierarchySupertypesParams params) {
        if (params.item == null || !FileStore.isJavaFile(params.item.uri)) return List.of();
        return new TypeHierarchyProvider(compiler()).supertypes(params.item);
    }

    @Override
    public List<TypeHierarchyItem> typeHierarchySubtypes(TypeHierarchySubtypesParams params) {
        if (params.item == null || !FileStore.isJavaFile(params.item.uri)) return List.of();
        return new TypeHierarchyProvider(compiler()).subtypes(params.item);
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new SymbolProvider(compiler()).documentSymbols(file);
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        var task = compiler().parse(file);
        return CodeLensProvider.find(task);
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        if (unresolved.data == null || !unresolved.data.isJsonObject()) return unresolved;
        var data = unresolved.data.getAsJsonObject();
        if (!data.has("uri") || !data.has("line") || !data.has("character")) return unresolved;
        var uri = java.net.URI.create(data.get("uri").getAsString());
        if (!FileStore.isJavaFile(uri)) return unresolved;
        var file = Paths.get(uri);
        var line = data.get("line").getAsInt() + 1;
        var column = data.get("character").getAsInt() + 1;
        var references = new ReferenceProvider(compiler(), file, line, column).find();
        var count = references.size();
        var title = count == 1 ? "1 reference" : count + " references";
        unresolved.command = new Command(title, "", new JsonArray());
        return unresolved;
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        var file = Paths.get(params.textDocument.uri);
        if (formatter != null) {
            return formatter.format(file, FileStore.contents(file), null);
        }
        var edits = new ArrayList<TextEdit>();
        var fixImports = new AutoFixImports(file).rewrite(compiler()).get(file);
        Collections.addAll(edits, fixImports);
        var addOverrides = new AutoAddOverrides(file).rewrite(compiler()).get(file);
        Collections.addAll(edits, addOverrides);
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new FoldProvider(compiler()).foldingRanges(file);
    }

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        LOG.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            var lines = task.root().getLineMap();
            var cursor = lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(task.root(), cursor);
            if (path == null) {
                LOG.info("...no element under cursor");
                return Optional.empty();
            }
            var el = Trees.instance(task.task).getElement(path);
            if (el == null) {
                LOG.info("...couldn't resolve element");
                return Optional.empty();
            }
            if (!canRename(el)) {
                LOG.info("...can't rename " + el);
                return Optional.empty();
            }
            if (!canFindSource(el)) {
                LOG.info("...can't find source for " + el);
                return Optional.empty();
            }
            var response = new RenameResponse();
            response.range = FindHelper.location(task, path).range;
            response.placeholder = el.getSimpleName().toString();
            return Optional.of(response);
        }
    }

    private boolean canRename(Element rename) {
        switch (rename.getKind()) {
            case METHOD:
            case FIELD:
            case LOCAL_VARIABLE:
            case PARAMETER:
            case EXCEPTION_PARAMETER:
            case CLASS:
            case INTERFACE:
            case ENUM:
            case RECORD:
            case ANNOTATION_TYPE:
                return true;
            default:
                return false;
        }
    }

    private boolean canFindSource(Element rename) {
        if (rename == null) return false;
        if (rename instanceof TypeElement) {
            var type = (TypeElement) rename;
            var name = type.getQualifiedName().toString();
            // findTypeDeclaration misses default-package types; findTypeReferences (which includes
            // the declaration file for same-package/imported use) is the robust source-reachability
            // probe.
            if (compiler().findTypeDeclaration(name) != CompilerProvider.NOT_FOUND) return true;
            return compiler().findTypeReferences(name).length > 0;
        }
        return canFindSource(rename.getEnclosingElement());
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        var rw = createRewrite(params);
        var response = new WorkspaceEdit();
        var map = rw.rewrite(compiler());
        for (var editedFile : map.keySet()) {
            response.changes.put(editedFile.toUri(), List.of(map.get(editedFile)));
        }
        return response;
    }

    private Rewrite createRewrite(RenameParams params) {
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            var lines = task.root().getLineMap();
            var position =
                    lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(task.root(), position);
            if (path == null) return Rewrite.NOT_SUPPORTED;
            var el = Trees.instance(task.task).getElement(path);
            switch (el.getKind()) {
                case METHOD:
                    return renameMethod(task, (ExecutableElement) el, params.newName);
                case FIELD:
                    return renameField(task, (VariableElement) el, params.newName);
                case LOCAL_VARIABLE:
                case PARAMETER:
                case EXCEPTION_PARAMETER:
                    return renameVariable(task, (VariableElement) el, params.newName);
                case CLASS:
                case INTERFACE:
                case ENUM:
                case RECORD:
                case ANNOTATION_TYPE:
                    return renameType(task, (TypeElement) el, params.newName);
                default:
                    return Rewrite.NOT_SUPPORTED;
            }
        }
    }

    private RenameMethod renameMethod(CompileTask task, ExecutableElement method, String newName) {
        var parent = (TypeElement) method.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var erasedParameterTypes = new String[method.getParameters().size()];
        for (var i = 0; i < erasedParameterTypes.length; i++) {
            var type = method.getParameters().get(i).asType();
            erasedParameterTypes[i] = task.task.getTypes().erasure(type).toString();
        }
        return new RenameMethod(className, methodName, erasedParameterTypes, newName);
    }

    private RenameField renameField(CompileTask task, VariableElement field, String newName) {
        var parent = (TypeElement) field.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var fieldName = field.getSimpleName().toString();
        return new RenameField(className, fieldName, newName);
    }

    private RenameVariable renameVariable(
            CompileTask task, VariableElement variable, String newName) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(variable);
        var file = Paths.get(path.getCompilationUnit().getSourceFile().toUri());
        var position =
                trees.getSourcePositions()
                        .getStartPosition(path.getCompilationUnit(), path.getLeaf());
        return new RenameVariable(file, (int) position, newName);
    }

    private RenameType renameType(CompileTask task, TypeElement type, String newName) {
        var className = type.getQualifiedName().toString();
        return new RenameType(className, newName);
    }

    private void removeClass(Path file) {
        var className = cacheCompiler.fileManager.getClassName(file);
        FileStore.externalDelete(file);
        var compiler = compiler();
        var referencePaths =
                Arrays.stream(compiler.findTypeReferences(className))
                        .filter(ref -> !ref.equals(file))
                        .toList();
        if (referencePaths.isEmpty()) {
            return;
        }
        for (var referencePath : referencePaths) {
            try (var task = compiler.compile(referencePath)) {
                compiler.compiler.removeClass((JCTree.JCCompilationUnit) task.root(), className);
            }
        }
        compiler.clearCachedModified();
        lint(referencePaths);
    }

    private boolean uncheckedChanges = false;
    private Path lastEdited = Paths.get("");

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);

        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // Clear diagnostics
            client.publishDiagnostics(
                    new PublishDiagnosticsParams(params.textDocument.uri, List.of()));
        }
    }

    @Override
    public List<CodeAction> codeAction(CodeActionParams params) {
        var provider = new CodeActionProvider(compiler());
        if (params.context.diagnostics.isEmpty()) {
            var actions = new ArrayList<CodeAction>(provider.codeActionsForCursor(params));
            actions.addAll(provider.sourceActions(params));
            return actions;
        } else {
            return provider.codeActionForDiagnostics(params);
        }
    }

    @Override
    public CodeAction resolveCodeAction(CodeAction unresolved) {
        return new CodeActionProvider(compiler()).resolve(unresolved);
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // Re-lint all active documents
            lint(FileStore.activeDocuments());
        }
    }

    @Override
    public void doAsyncWork() {
        if (uncheckedChanges && FileStore.activeDocuments().contains(lastEdited)) {
            lint(List.of(lastEdited));
            uncheckedChanges = false;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
