import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Phase 3 viability probe: jrt access + in-image javac platform resolution. */
public class JrtProbe {
    public static void main(String[] args) throws Exception {
        String javaHome = System.getenv("JAVA_HOME");
        System.out.println("JAVA_HOME=" + javaHome);
        System.out.println("java.home prop=" + System.getProperty("java.home"));

        // 1. default installed jrt provider (expected to fail in a native image)
        try {
            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            System.out.println("[1] default jrt: OK " + fs);
        } catch (Throwable t) {
            System.out.println("[1] default jrt: FAIL " + t);
        }

        // 2. jrt backed by an explicit external java.home
        try {
            FileSystem fs = FileSystems.newFileSystem(
                    URI.create("jrt:/"), Map.of("java.home", javaHome));
            Path list = fs.getPath("/modules/java.base/java/util/List.class");
            System.out.println("[2] newFileSystem jrt: OK, List.class exists=" + Files.exists(list));
            try (var s = Files.walk(fs.getPath("/modules/java.base/java/util"))) {
                long n = s.filter(x -> x.toString().endsWith(".class")).count();
                System.out.println("[2] java.util .class count=" + n);
            }
        } catch (Throwable t) {
            System.out.println("[2] newFileSystem jrt: FAIL " + t);
            t.printStackTrace();
        }

        // 3. in-image javac resolving platform types
        try {
            JavaCompiler c = ToolProvider.getSystemJavaCompiler();
            System.out.println("[3] getSystemJavaCompiler=" + c);
            if (c != null) {
                var diags = new DiagnosticCollector<JavaFileObject>();
                var fm = c.getStandardFileManager(diags, null, null);
                Path tmp = Files.createTempDirectory("jrtprobe");
                Path src = tmp.resolve("T.java");
                Files.writeString(src,
                        "import java.util.*; class T { List<String> f(){ return new ArrayList<>(); } }");
                Path out = tmp.resolve("out");
                Files.createDirectories(out);
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
                var units = fm.getJavaFileObjectsFromFiles(List.of(src.toFile()));
                boolean ok = c.getTask(null, fm, diags, null, null, units).call();
                System.out.println("[3] javac compile ok=" + ok);
                for (var d : diags.getDiagnostics()) {
                    System.out.println("    diag: " + d.getKind() + " " + d.getMessage(null));
                }
            }
        } catch (Throwable t) {
            System.out.println("[3] javac: FAIL " + t);
            t.printStackTrace();
        }
    }
}
