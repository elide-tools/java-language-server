package org.javacs.svm;

import com.oracle.svm.core.annotate.KeepOriginal;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * The jrt filesystem locates the module image via {@code SystemImage.findHome()},
 * which in a stock JDK returns a build-time path. In a native image that path is
 * meaningless, so resolve it at runtime from {@code java.home} (or JAVA_HOME).
 * Mirrors Elide's RuntimeSystemImageSubstitution.
 */
@SuppressWarnings("unused")
@TargetClass(className = "jdk.internal.jrtfs.SystemImage")
@KeepOriginal
public final class SystemImageSubstitution {
    @Substitute
    private static String findHome() {
        String assigned = System.getProperty("java.home");
        if (assigned != null) {
            return assigned;
        }
        String env = System.getenv("JAVA_HOME");
        if (env != null) {
            return env;
        }
        throw new IllegalStateException("java.home / JAVA_HOME not set; cannot locate JDK module image");
    }
}
