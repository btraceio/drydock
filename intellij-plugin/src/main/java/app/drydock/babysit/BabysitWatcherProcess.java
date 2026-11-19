package app.drydock.babysit;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>Sanctioned exception to the child-process rule</b> ("child processes go
 * through {@code ProcessRunner}", AGENTS.md), the second after {@code
 * LspServerProcess}: the one spawn whose child must OUTLIVE the process
 * that starts it. At IDE quit the plugin starts the detached babysitting
 * watcher ({@link BabysitWatcherMain}) so engagements keep going while the
 * IDE is closed. {@code ProcessRunner}'s contract is run-to-completion --
 * {@code waitFor(timeout)}, then {@code destroyForcibly()} -- which would
 * kill the watcher with the IDE by construction.
 *
 * <p>Every other rule still holds: an argument list, never a shell; no
 * pipes at all (stdin from {@code /dev/null}, stdout and stderr appended to
 * {@link #logFile}), so there is nothing for the dying IDE to drain and no
 * pipe whose closing kills the child; {@code /usr/bin/nohup} in front when
 * it exists, so the hangup of a terminal the IDE was started from does not
 * reach it. The lifetime is bounded by the protocol, not by a timeout: the
 * watcher stops itself when the plugin clears its generation in the state
 * file (at the next IDE start) or nothing is left to watch, and every child
 * it runs goes through {@code ProcessRunner} with a timeout. The IDE never
 * waits for, reads from or kills it; nothing here blocks beyond the spawn.</p>
 *
 * <p>The command is {@code [nohup] <java.home>/bin/java -Xmx128m
 * -XX:+UseSerialGC -Djava.awt.headless=true -cp <core jar>
 * app.drydock.babysit.BabysitWatcherMain <baseDir> <generation>}: the JBR the
 * IDE runs on, and the plugin's own {@code drydock-core.jar} (the jar this
 * class was loaded from) as the whole class path -- the watcher needs nothing beyond
 * the headless core and the JDK (the core's only third-party jars, tree-sitter
 * and snakeyaml, serve review and task parsing, which the watcher never
 * loads). The PATH handed to the child is the login shell's, which the
 * plugin learnt from the IDE; no login shell is started.</p>
 */
public final class BabysitWatcherProcess {

    /** The log is rotated once past this size, before a spawn. */
    static final long MAX_LOG_BYTES = 1024 * 1024;

    private static final Path NOHUP = Path.of("/usr/bin/nohup");

    private BabysitWatcherProcess() {
    }

    /** {@code <baseDir>/babysit/watcher.log}: the watcher's stdout and stderr, appended. */
    public static Path logFile(Path baseDir) {
        return BabysitStateFile.defaultFile(baseDir).resolveSibling("watcher.log");
    }

    /** The watcher's command line. Pure. */
    public static List<String> command(Optional<Path> nohup, Path java, Path classPath, Path baseDir,
                                       UUID generation) {
        Objects.requireNonNull(nohup, "nohup");
        Objects.requireNonNull(generation, "generation");
        List<String> command = new ArrayList<>();
        nohup.ifPresent(path -> command.add(path.toString()));
        command.addAll(List.of(java.toString(), "-Xmx128m", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
                "-cp", classPath.toString(), BabysitWatcherMain.class.getName(),
                baseDir.toAbsolutePath().normalize().toString(), generation.toString()));
        return List.copyOf(command);
    }

    /** {@code <java.home>/bin/java}: the runtime this JVM runs on. */
    public static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    /**
     * Where the watcher's classes are: the jar (or class directory) this
     * class was loaded from, found through its own class-file resource --
     * not the protection domain, which the IDE's class loaders (and the
     * platform test loader) leave without a code source.
     */
    public static Path classPath() throws IOException {
        String resource = BabysitWatcherProcess.class.getName().replace('.', '/') + ".class";
        URL url = BabysitWatcherProcess.class.getClassLoader().getResource(resource);
        if (url == null) {
            throw new IOException("The babysitting watcher's class file cannot be found");
        }
        return classPathOf(url, resource);
    }

    /**
     * The class-path entry holding {@code resource}, from its URL: the jar
     * of a {@code jar:file:<jar>!/<resource>} URL, the root directory of a
     * {@code file:<dir>/<resource>} URL. Pure.
     */
    static Path classPathOf(URL url, String resource) throws IOException {
        try {
            String text = url.toString();
            if ("jar".equals(url.getProtocol())) {
                int bang = text.indexOf("!/");
                if (bang < 0) {
                    throw new IOException("Not a jar entry URL: " + url);
                }
                return Path.of(new URI(text.substring("jar:".length(), bang)));
            }
            if ("file".equals(url.getProtocol()) && text.endsWith(resource)) {
                return Path.of(new URI(text.substring(0, text.length() - resource.length())));
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("The babysitting watcher's classes are not in a file: " + url, e);
        }
        throw new IOException("The babysitting watcher's classes are not in a jar or a directory: " + url);
    }

    /**
     * Starts the watcher for {@code generation}, detached, with {@code path}
     * as its PATH (null: the inherited one), and returns its pid. Rotates
     * the log first when it is over {@link #MAX_LOG_BYTES}. Blocking only
     * for the spawn.
     */
    public static long spawn(Path baseDir, UUID generation, String path) throws IOException {
        Path log = logFile(baseDir);
        Files.createDirectories(log.getParent());
        if (Files.exists(log) && Files.size(log) > MAX_LOG_BYTES) {
            Files.move(log, log.resolveSibling(log.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
        }
        Optional<Path> nohup = Files.isExecutable(NOHUP) ? Optional.of(NOHUP) : Optional.empty();
        ProcessBuilder builder = new ProcessBuilder(command(nohup, javaExecutable(), classPath(), baseDir, generation))
                .directory(log.getParent().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectErrorStream(true);
        if (path != null && !path.isBlank()) {
            builder.environment().put("PATH", path);
        }
        return builder.start().pid();
    }
}
