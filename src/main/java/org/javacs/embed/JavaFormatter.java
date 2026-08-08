package org.javacs.embed;

import org.javacs.lsp.Range;
import org.javacs.lsp.TextEdit;

import java.nio.file.Path;
import java.util.List;

/**
 * Formats Java source, letting an embedding host — e.g. Elide — plug in a real formatter
 * (google-java-format) in place of the server's built-in import-fix / add-overrides edits.
 */
public interface JavaFormatter {
    /**
     * @param file the document path (for language/style resolution)
     * @param text the current document text (the in-memory buffer, authoritative over disk)
     * @param range the range to format, or {@code null} for the whole document
     * @return the edits to apply; empty for no change
     */
    List<TextEdit> format(Path file, String text, Range range);
}
