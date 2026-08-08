package org.javacs.embed;

import java.nio.file.Path;
import java.util.Set;

/**
 * Supplies the compile classpath (and documentation/source path) for a workspace, letting an
 * embedding host — e.g. Elide — inject a resolved dependency set instead of the built-in {@code
 * InferConfig} inference ({@code ~/.m2}/{@code ~/.gradle} scavenging and {@code mvn
 * dependency:list} subprocesses).
 *
 * <p>When {@link #classpath} returns a non-empty set the server uses it verbatim and never runs
 * inference; an empty set defers to the built-in behavior.
 */
public interface ClasspathProvider {
    /**
     * Resolved compile classpath entries (jars and class output dirs); empty to defer to inference.
     */
    Set<Path> classpath(Path workspaceRoot);

    /** Resolved documentation/source jars for hover and Javadoc; may be empty. */
    Set<Path> docPath(Path workspaceRoot);
}
