package app.drydock.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Build-integration guard: the jlink {@code --add-modules} list in
 * {@code RuntimeImageTask} must stay a superset of the JDK modules the packaged
 * app jar actually uses, as reported by {@code jdeps --print-module-deps}.
 *
 * <p>This exists because the list is hand-maintained and silently drifts: a
 * commit added {@code java.util.logging} usage in the {@code mcp} package but
 * never added {@code java.logging} to the list, so the jlink runtime image
 * shipped in the {@code .dmg} lacked the module and the app died at launch with
 * {@code NoClassDefFoundError: java.util.logging.Logger}. Dev runs
 * ({@code ./gradlew run}) and the build itself never caught it -- they use the
 * full JDK, where every module is present; only the stripped runtime image is
 * missing modules. This test runs {@code jdeps} against the same jar + runtime
 * classpath the packaging task consumes and fails the build the moment the
 * declared list stops covering what the code needs.</p>
 *
 * <p>The check is one-directional (jdeps set &sube; declared set): the declared
 * list is deliberately a superset -- it also carries the JavaFX modules (which
 * resolve from the classpath, so {@code jdeps} never reports them) and
 * {@code jdk.unsupported} (added defensively for reflective Unsafe use jdeps
 * cannot see).</p>
 *
 * <p>Inputs are supplied by the {@code test} task in {@code app/build.gradle.kts}
 * (the app jar, its runtime classpath, and the convention-plugin source that
 * declares the list). When they are absent -- e.g. running this class straight
 * from an IDE without the Gradle wiring -- the test skips rather than fails.</p>
 */
class RuntimeImageModuleListTest {

    @Test
    void declaredJlinkModulesCoverWhatTheAppJarActuallyUses() throws IOException, InterruptedException {
        String appJar = System.getProperty("drydock.test.appJar");
        String runtimeClasspath = System.getProperty("drydock.test.runtimeClasspath");
        String moduleListSource = System.getProperty("drydock.test.moduleListSource");
        assumeTrue(
            appJar != null && runtimeClasspath != null && moduleListSource != null,
            "Not wired by the Gradle test task (drydock.test.* system properties absent); skipping."
        );

        Set<String> declared = parseDeclaredModules(Path.of(moduleListSource));
        Set<String> actual = jdepsModuleDeps(appJar, runtimeClasspath);

        Set<String> missing = new TreeSet<>(actual);
        missing.removeAll(declared);
        assertTrue(
            missing.isEmpty(),
            () -> "The jlink --add-modules list in " + moduleListSource + " is missing module(s) the "
                + "app jar uses: " + missing + ".\njdeps --print-module-deps reported: " + new TreeSet<>(actual)
                + "\ndeclared --add-modules: " + new TreeSet<>(declared)
                + "\nAdd the missing module(s) to RuntimeImageTask's --add-modules, or the .dmg will crash "
                + "at launch with NoClassDefFoundError even though the build and `./gradlew run` succeed."
        );
    }

    /**
     * Extracts the module tokens from the {@code --add-modules} argument in the
     * RuntimeImageTask source (the literal is split across concatenated string
     * chunks). Comment text is stripped first so a module name mentioned only in
     * a {@code //} comment cannot mask a genuinely absent entry.
     */
    private static Set<String> parseDeclaredModules(Path source) throws IOException {
        String text = Files.readString(source);
        int start = text.indexOf("\"--add-modules\"");
        int end = text.indexOf("\"--output\"", start);
        if (start < 0 || end < 0) {
            fail("Could not locate the --add-modules ... --output region in " + source
                + "; the test's parser needs updating to match RuntimeImageTask.");
        }
        StringBuilder codeOnly = new StringBuilder();
        for (String line : text.substring(start, end).split("\n")) {
            int comment = line.indexOf("//");
            codeOnly.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        Set<String> modules = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?:java|jdk|javafx)\\.[a-z][a-z.]*").matcher(codeOnly);
        while (m.find()) {
            modules.add(m.group());
        }
        assertTrue(modules.contains("java.base"), "Parser found no plausible module list in " + source);
        return modules;
    }

    /** Runs {@code jdeps --print-module-deps} the same way the packaging derives its list. */
    private static Set<String> jdepsModuleDeps(String appJar, String runtimeClasspath)
            throws IOException, InterruptedException {
        String jdeps = Path.of(System.getProperty("java.home"), "bin", "jdeps").toString();
        int feature = Runtime.version().feature();
        Process p = new ProcessBuilder(
            jdeps,
            "--multi-release", Integer.toString(feature),
            "--ignore-missing-deps",
            "--print-module-deps",
            "--class-path", runtimeClasspath,
            appJar
        ).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();
        assertTrue(exit == 0, () -> "jdeps failed (exit " + exit + "):\n" + output);

        Set<String> modules = new LinkedHashSet<>();
        for (String module : output.split(",")) {
            String trimmed = module.trim();
            if (!trimmed.isEmpty()) {
                modules.add(trimmed);
            }
        }
        assertTrue(modules.contains("java.base"), () -> "Unexpected jdeps output:\n" + output);
        return modules;
    }
}
