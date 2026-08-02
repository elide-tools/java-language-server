package org.javacs.svm;

import com.oracle.svm.core.jdk.buildtimeinit.FileSystemProviderBuildTimeInitSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Native-image build hooks for java-language-server:
 *
 * <ol>
 *   <li>Registers the jrt + zip NIO FileSystemProviders at build time so the
 *       embedded javac can open {@code jrt:/} against an external JDK's module
 *       image at runtime (mirrors Elide's KotlinCompilerFeature).
 *   <li><b>INFRA-1:</b> registers every {@code org.javacs.lsp.*} DTO for
 *       reflection. gson (de)serializes all LSP wire types reflectively; without
 *       this, any field the tracing agent never observed crashes the server at
 *       runtime (e.g. {@code CodeActionContext.only}). Discovering the package
 *       from the code source keeps this correct as new DTOs are added — no
 *       hand-maintained list.
 * </ol>
 */
public final class JlsNativeImageFeature implements Feature {
    private static final String LSP_PACKAGE = "org.javacs.lsp";
    private static final String LSP_PATH = "org/javacs/lsp/";

    @Override
    public String getDescription() {
        return "java-language-server: jrt/zip file systems + org.javacs.lsp reflection";
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        FileSystemProviderBuildTimeInitSupport.register(new jdk.nio.zipfs.ZipFileSystemProvider());
        FileSystemProviderBuildTimeInitSupport.register(new jdk.internal.jrtfs.JrtFileSystemProvider());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (var className : discoverLspClasses()) {
            var cls = access.findClassByName(className);
            if (cls == null) continue;
            RuntimeReflection.register(cls);
            RuntimeReflection.register(cls.getDeclaredFields());
            RuntimeReflection.register(cls.getDeclaredConstructors());
            RuntimeReflection.register(cls.getDeclaredMethods());
        }
    }

    private List<String> discoverLspClasses() {
        try {
            var source = org.javacs.lsp.LSP.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return List.of();
            var path = Paths.get(source.getLocation().toURI());
            if (Files.isDirectory(path)) {
                return scanDirectory(path);
            }
            return scanJar(path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to enumerate " + LSP_PACKAGE + " for reflection", e);
        }
    }

    private List<String> scanDirectory(Path root) throws Exception {
        var pkgDir = root.resolve(LSP_PATH);
        var names = new ArrayList<String>();
        if (!Files.isDirectory(pkgDir)) return names;
        try (var stream = Files.walk(pkgDir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        var rel = root.relativize(p).toString().replace('\\', '/');
                        names.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                    });
        }
        return names;
    }

    private List<String> scanJar(Path jarPath) throws Exception {
        var names = new ArrayList<String>();
        try (var jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                if (name.startsWith(LSP_PATH) && name.endsWith(".class")) {
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        }
        return names;
    }
}
