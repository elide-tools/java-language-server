package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

import com.google.gson.JsonElement;
import java.nio.file.Files;
import java.util.List;
import org.javacs.embed.JavaFormatter;
import org.javacs.lsp.*;
import org.junit.Test;

public class EmbeddingFormatterTest {
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
  public void injectedFormatterReplacesDefaultEdits() throws Exception {
    var sentinel = new TextEdit();
    sentinel.newText = "// formatted by embedding host\n";
    JavaFormatter formatter = (file, text, range) -> List.of(sentinel);

    var server = new JavaLanguageServer(noopClient(), null, formatter);

    var tmp = Files.createTempFile("Embedding", ".java");
    Files.writeString(tmp, "class A{}\n");
    try {
      var params = new DocumentFormattingParams();
      params.textDocument = new TextDocumentIdentifier();
      params.textDocument.uri = tmp.toUri();

      var edits = server.formatting(params);

      // Delegation: the host formatter's edits are returned verbatim, not JLS's import/override edits.
      assertThat(edits, contains(sentinel));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
