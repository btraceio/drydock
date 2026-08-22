package app.drydock.review;

import org.treesitter.TSLanguage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extension to tree-sitter grammar (spec §10.2).
 *
 * <p><strong>A grammar that is absent is the lexical path, not an error.</strong>
 * That rule is what keeps the shipped language set a packaging decision
 * rather than an architectural one: the {@code .app} and the jbang jar may
 * ship different sets, and a language nobody packaged produces a coarser
 * change graph rather than a broken surface.</p>
 *
 * <p>Grammars are resolved reflectively and cached. Loading pulls a native
 * library out of the jar and {@code System.load}s it, so the first call for
 * a language is disk I/O -- never make it on the FX thread.</p>
 */
public final class GrammarRegistry {

    private static final Logger LOG = Logger.getLogger(GrammarRegistry.class.getName());

    /** Extension to the grammar class the artifact publishes, insertion-ordered for determinism. */
    private static final Map<String, String> GRAMMARS = new LinkedHashMap<>();

    static {
        GRAMMARS.put("java", "org.treesitter.TreeSitterJava");
        GRAMMARS.put("kt", "org.treesitter.TreeSitterKotlin");
        GRAMMARS.put("kts", "org.treesitter.TreeSitterKotlin");
        GRAMMARS.put("py", "org.treesitter.TreeSitterPython");
        GRAMMARS.put("js", "org.treesitter.TreeSitterJavascript");
        GRAMMARS.put("mjs", "org.treesitter.TreeSitterJavascript");
        GRAMMARS.put("ts", "org.treesitter.TreeSitterTypescript");
        GRAMMARS.put("tsx", "org.treesitter.TreeSitterTypescript");
        GRAMMARS.put("go", "org.treesitter.TreeSitterGo");
        GRAMMARS.put("rs", "org.treesitter.TreeSitterRust");
        GRAMMARS.put("c", "org.treesitter.TreeSitterC");
        GRAMMARS.put("h", "org.treesitter.TreeSitterCpp");
        GRAMMARS.put("cc", "org.treesitter.TreeSitterCpp");
        GRAMMARS.put("cpp", "org.treesitter.TreeSitterCpp");
        GRAMMARS.put("hpp", "org.treesitter.TreeSitterCpp");
    }

    private static final Map<String, Optional<TSLanguage>> CACHE = new LinkedHashMap<>();
    private static volatile boolean nativeFailed;

    private GrammarRegistry() {
    }

    /** Whether the native library loaded. False means every file takes the lexical path. */
    public static boolean nativeAvailable() {
        return !nativeFailed;
    }

    /** The grammar for {@code path}'s language, or empty when there is none. */
    public static synchronized Optional<TSLanguage> forPath(String path) {
        if (path == null || path.endsWith("/")) {
            return Optional.empty();
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) {
            return Optional.empty();
        }
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        String className = GRAMMARS.get(extension);
        if (className == null) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(extension, key -> load(className));
    }

    private static Optional<TSLanguage> load(String className) {
        if (nativeFailed) {
            return Optional.empty();
        }
        try {
            Class<?> type = Class.forName(className);
            return Optional.of((TSLanguage) type.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException e) {
            // The grammar was not packaged for this artifact. Normal, and the
            // lexical path handles it -- logging it per file would be noise.
            return Optional.empty();
        } catch (ReflectiveOperationException | UnsatisfiedLinkError | RuntimeException e) {
            // The native library could not load: unsupported arch, a failed
            // extraction, a CRC mismatch. Say it ONCE and fall back for
            // everything; per-file logging would bury it.
            if (!nativeFailed) {
                nativeFailed = true;
                LOG.log(Level.WARNING, "tree-sitter unavailable; the change graph "
                        + "falls back to lexical scanning for every file", e);
            }
            return Optional.empty();
        }
    }
}
