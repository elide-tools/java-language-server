package org.javacs;

import static org.junit.Assert.assertEquals;

import org.javacs.lsp.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HostWorkspaceSourcesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path source(Path root, String path) throws Exception {
        var file = root.resolve(path + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file, "class " + file.getFileName().toString().replace(".java", "") + " {}\n");
        return file;
    }

    private JavaLanguageServer server(Path root, Collection<Path> files) {
        FileStore.reset();
        var server =
                new JavaLanguageServer(
                        new LanguageClient() {
                            @Override
                            public void publishDiagnostics(PublishDiagnosticsParams params) {}

                            @Override
                            public void showMessage(ShowMessageParams params) {}

                            @Override
                            public void registerCapability(
                                    String method, com.google.gson.JsonElement options) {}

                            @Override
                            public void customNotification(
                                    String method, com.google.gson.JsonElement params) {}
                        }) {
                    @Override
                    protected Collection<Path> workspaceSourceFiles(Path workspace) {
                        return files;
                    }

                    @Override
                    protected boolean workspaceSourceConfigurationChanged(Path file) {
                        return file.getFileName().toString().equals("project.config");
                    }

                    @Override
                    protected boolean isWorkspaceSource(Path file) {
                        return file.startsWith(root.resolve("owned"));
                    }
                };
        var params = new InitializeParams();
        params.rootUri = root.toUri();
        server.initialize(params);
        return server;
    }

    private Set<String> names(JavaLanguageServer server) {
        var names = new HashSet<String>();
        for (var symbol : server.workspaceSymbols(new WorkspaceSymbolParams("HostFixture")))
            names.add(symbol.name);
        return names;
    }

    @Test
    public void explicitFilesReplaceDiscoveryAndFilterWatcherEvents() throws Exception {
        var root = temporary.newFolder().toPath();
        var main = source(root, "owned/HostFixtureMain");
        source(root, "unrelated/HostFixtureUnrelated");
        var server = server(root, List.of(main));
        assertEquals(Set.of("HostFixtureMain"), names(server));
        var created = source(root, "owned/HostFixtureCreated");
        var ignored = source(root, "unrelated/HostFixtureIgnored");
        var params = new DidChangeWatchedFilesParams();
        params.changes = new java.util.ArrayList<>();
        for (var file : List.of(created, ignored)) {
            var event = new FileEvent();
            event.uri = file.toUri();
            event.type = FileChangeType.Created;
            params.changes.add(event);
        }
        server.didChangeWatchedFiles(params);
        assertEquals(Set.of("HostFixtureMain", "HostFixtureCreated"), names(server));
    }

    @Test
    public void changedProjectSelectionRemovesOldFiles() throws Exception {
        var root = temporary.newFolder().toPath();
        var first = source(root, "owned/HostFixtureFirst");
        var second = source(root, "owned/HostFixtureSecond");
        var selected = new java.util.ArrayList<>(List.of(first));
        var server = server(root, selected);
        assertEquals(Set.of("HostFixtureFirst"), names(server));
        selected.clear();
        selected.add(second);
        var event = new FileEvent();
        event.uri = root.resolve("project.config").toUri();
        event.type = FileChangeType.Changed;
        var params = new DidChangeWatchedFilesParams();
        params.changes = List.of(event);
        server.didChangeWatchedFiles(params);
        assertEquals(Set.of("HostFixtureSecond"), names(server));
    }

    @Test
    public void deletionRemovesPreviouslySelectedFilesOutsideCurrentMembership() throws Exception {
        var root = temporary.newFolder().toPath();
        var previous = source(root, "previous/HostFixtureDeleted");
        var server = server(root, List.of(previous));
        assertEquals(Set.of("HostFixtureDeleted"), names(server));
        Files.delete(previous);
        var event = new FileEvent();
        event.uri = previous.toUri();
        event.type = FileChangeType.Deleted;
        var params = new DidChangeWatchedFilesParams();
        params.changes = List.of(event);
        server.didChangeWatchedFiles(params);
        assertEquals(Set.of(), names(server));
    }

    @Test
    public void emptySelectionDoesNotFallBackToWorkspaceScan() throws Exception {
        var root = temporary.newFolder().toPath();
        source(root, "HostFixtureOutside");
        assertEquals(Set.of(), names(server(root, List.of())));
    }
}
