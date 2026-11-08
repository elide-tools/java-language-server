package org.javacs;

import static org.junit.Assert.assertEquals;

import org.javacs.lsp.WorkspaceSymbolParams;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class WorkspaceIndexTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private void source(Path root, String directory, String name) throws Exception {
        var file = root.resolve(directory).resolve(name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "public class " + name + " {}\n");
    }

    private Set<String> symbols(Path root) {
        var server = LanguageServerFixture.getJavaLanguageServer(root, diagnostic -> {});
        var names = new HashSet<String>();
        for (var symbol : server.workspaceSymbols(new WorkspaceSymbolParams("IndexFixture"))) {
            names.add(symbol.name);
        }
        return names;
    }

    @Test
    public void skipsCachesButPreservesAuthoredAndGeneratedSources() throws Exception {
        var root = temporary.newFolder("workspace").toPath();
        var included =
                new String[] {
                    "src/main/java",
                    "build/generated",
                    "target/generated-sources",
                    ".dev/codegen",
                    "src/build"
                };
        var expected = new HashSet<String>();
        for (int i = 0; i < included.length; i++) {
            var name = "IndexFixtureSource" + i;
            source(root, included[i], name);
            expected.add(name);
        }
        var excluded =
                new String[] {
                    ".git",
                    ".hg",
                    ".svn",
                    ".cache",
                    ".gradle",
                    ".m2",
                    "node_modules",
                    "nested/node_modules",
                    ".dev/artifacts",
                    ".dev/dependencies",
                    ".dev/scratch",
                    ".dev/toolchains",
                    ".dev/reports",
                    ".dev/worktree-archive"
                };
        for (int i = 0; i < excluded.length; i++) {
            source(root, excluded[i] + "/nested", "IndexFixtureCached" + i);
        }
        assertEquals(expected, symbols(root));
    }

    @Test
    public void indexesAnExplicitWorkspaceInsideACache() throws Exception {
        var root = temporary.newFolder(".cache").toPath();
        source(root, "src", "IndexFixtureExplicit");
        assertEquals(Set.of("IndexFixtureExplicit"), symbols(root));
    }
}
