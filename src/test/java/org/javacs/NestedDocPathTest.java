package org.javacs;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonElement;

import org.javacs.embed.ClasspathProvider;
import org.javacs.lsp.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.tools.ToolProvider;

public class NestedDocPathTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void definitionFindsNestedTypeMemberInOuterSourceFile() throws Exception {
        var docs = temporary.newFolder("docs").toPath();
        var source = docs.resolve("demo/R.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "package demo; public class R { public static class string { public static int"
                    + " label = 1; } }\n");
        var classes = temporary.newFolder("classes").toPath();
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), source.toString()));
        var workspace = temporary.newFolder("workspace").toPath();
        var use = workspace.resolve("Use.java");
        var text = "class Use { int n = demo.R.string.label; }";
        Files.writeString(use, text);
        var provider =
                new ClasspathProvider() {
                    @Override
                    public Set<Path> classpath(Path root) {
                        return Set.of(classes);
                    }

                    @Override
                    public Set<Path> docPath(Path root) {
                        return Set.of(docs);
                    }
                };
        var client =
                new LanguageClient() {
                    @Override
                    public void publishDiagnostics(PublishDiagnosticsParams params) {}

                    @Override
                    public void showMessage(ShowMessageParams params) {}

                    @Override
                    public void registerCapability(String method, JsonElement options) {}

                    @Override
                    public void customNotification(String method, JsonElement params) {}
                };
        FileStore.reset();
        var server = new JavaLanguageServer(client, provider, null);
        var init = new InitializeParams();
        init.rootUri = workspace.toUri();
        server.initialize(init);
        var result =
                server.gotoDefinition(
                                new TextDocumentPositionParams(
                                        new TextDocumentIdentifier(use.toUri()),
                                        new Position(0, text.indexOf("label"))))
                        .orElseThrow();
        assertEquals(1, result.size());
        assertEquals(source.toUri(), result.get(0).uri);
    }
}
