package org.javacs.svm;

import com.oracle.svm.core.jdk.buildtimeinit.FileSystemProviderBuildTimeInitSupport;
import org.graalvm.nativeimage.hosted.Feature;

/**
 * Registers the jrt + zip NIO FileSystemProviders into the native image at build
 * time, so the embedded javac can open {@code jrt:/} against an external JDK's
 * module image at runtime. Mirrors Elide's KotlinCompilerFeature.
 */
public final class JlsNativeImageFeature implements Feature {
    @Override
    public String getDescription() {
        return "java-language-server: register jrt/zip file systems for embedded javac";
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        FileSystemProviderBuildTimeInitSupport.register(new jdk.nio.zipfs.ZipFileSystemProvider());
        FileSystemProviderBuildTimeInitSupport.register(new jdk.internal.jrtfs.JrtFileSystemProvider());
    }
}
