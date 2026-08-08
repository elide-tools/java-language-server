package org.javacs.embed;

import org.javacs.JavaLanguageServer;
import org.javacs.lsp.LSP;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.LanguageServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;

/**
 * In-process embedding entrypoint for java-language-server.
 *
 * <p>Hosts drive the server without the standalone {@link org.javacs.Main}, which mutates the root
 * JUL logger and calls {@code System.exit}. Inject a {@link ClasspathProvider} and {@link
 * JavaFormatter} to replace the built-in dependency inference and formatter; pass {@code null} for
 * either to keep the default behavior.
 */
public final class Embedding {
    private Embedding() {}

    /**
     * A server factory for {@link LSP#connect}, wiring the given SPIs into each server instance.
     */
    public static Function<LanguageClient, LanguageServer> serverFactory(
            ClasspathProvider classpath, JavaFormatter formatter) {
        return client -> new JavaLanguageServer(client, classpath, formatter);
    }

    /**
     * Runs the LSP dispatch loop over the given streams until the client closes {@code receive} or
     * sends {@code exit}. Blocks the calling thread and never calls {@code System.exit}.
     */
    public static void connect(
            InputStream receive,
            OutputStream send,
            ClasspathProvider classpath,
            JavaFormatter formatter) {
        LSP.connect(serverFactory(classpath, formatter), receive, send);
    }
}
