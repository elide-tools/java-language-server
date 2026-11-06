package org.javacs;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assume.assumeFalse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Inference must not run a workspace's build tool unless {@code javacs.inferBuildTools=true}. The
 * {@code mvn} lookup uses the process {@code PATH}, so a child JVM runs {@link #main} with a
 * marker-writing shim first on its {@code PATH}.
 */
public class BuildToolInferenceTest {
    @Test
    public void pomWorkspaceDoesNotRunMvnByDefault() throws Exception {
        assertThat(probe(false), is(false));
    }

    @Test
    public void pomWorkspaceRunsMvnWhenOptedIn() throws Exception {
        assertThat(probe(true), is(true));
    }

    /** Returns whether the shim ran. */
    private static boolean probe(boolean optIn) throws Exception {
        assumeFalse(File.separatorChar == '\\');
        var root = Files.createTempDirectory("javacs-no-mvn");
        try {
            return runProbe(root, optIn);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static boolean runProbe(Path root, boolean optIn) throws Exception {
        var workspace = Files.createDirectories(root.resolve("workspace"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n");
        var marker = root.resolve("mvn-ran");
        var bin = Files.createDirectories(root.resolve("bin"));
        var mvn = bin.resolve("mvn");
        Files.writeString(mvn, "#!/bin/sh\necho \"$@\" >> '" + marker + "'\n");
        assertThat(mvn.toFile().setExecutable(true), is(true));

        var command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (optIn) command.add("-D" + InferConfig.INFER_BUILD_TOOLS + "=true");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(BuildToolInferenceTest.class.getName());
        command.add(workspace.toString());
        var builder = new ProcessBuilder(command).inheritIO();
        builder.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
        var process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS), is(true));
            assertThat(process.exitValue(), is(0));
        } finally {
            process.destroyForcibly().waitFor();
        }
        return Files.exists(marker);
    }

    public static void main(String[] args) {
        var infer = new InferConfig(Paths.get(args[0]), Map.of());
        infer.classPath();
        infer.buildDocPath();
    }
}
