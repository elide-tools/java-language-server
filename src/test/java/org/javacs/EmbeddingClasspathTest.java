package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.javacs.embed.ClasspathProvider;
import org.javacs.lsp.*;
import org.junit.Test;

public class EmbeddingClasspathTest {
  private static LanguageClient noopClient() {
    return new LanguageClient() {
      @Override
      public void publishDiagnostics(PublishDiagnosticsParams params) {}

      @Override
      public void showMessage(ShowMessageParams params) {}

      @Override
      public void registerCapability(String method, JsonElement options) {}

      @Override
      public void customNotification(String method, JsonElement params) {}
    };
  }

  @Test
  public void injectedClasspathBypassesInference() throws Exception {
    var gsonJar =
        Paths.get(com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    var provided = Set.of(gsonJar);

    FileStore.reset();
    var server =
        new JavaLanguageServer(
            noopClient(),
            new ClasspathProvider() {
              @Override
              public Set<Path> classpath(Path workspaceRoot) {
                return provided;
              }

              @Override
              public Set<Path> docPath(Path workspaceRoot) {
                return Set.of();
              }
            },
            null);
    var init = new InitializeParams();
    init.rootUri = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.toUri();
    server.initialize(init);

    // The compiler must use exactly the injected classpath; InferConfig is never consulted.
    assertThat(server.compiler().classPath, equalTo(provided));
  }
}
