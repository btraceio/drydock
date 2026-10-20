package app.drydock.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The well-known install locations each {@link ExternalTool} is probed at
 * when it is on neither the configured override nor the {@code PATH} -- a
 * convenience fallback, never a substitute for PATH-based discovery.
 *
 * <p>Organized per OS because install schemes differ sharply: Windows tools
 * live under {@code Program Files} (native installers), {@code %APPDATA%\npm}
 * (npm global shims), scoop's shim dir, or Chocolatey's bin; macOS under
 * homebrew or {@code ~/.local/bin}; Linux under the FHS bin dirs. Every list
 * here is ordered most-canonical first; {@link ToolLocator} probes them in
 * order and reports the whole searched set in its error descriptions.</p>
 *
 * <p>Environment-dependent roots ({@code %ProgramFiles%} and friends) are
 * read from a map rather than {@link System#getenv} directly so tests can
 * inject values; the production entry point passes {@code System.getenv()}.</p>
 */
public final class ToolPaths {

    /** The operating systems the fallback lists distinguish. */
    public enum Os {
        MACOS, LINUX, WINDOWS;

        /** The running JVM's OS. */
        public static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase();
            if (name.contains("win")) {
                return WINDOWS;
            }
            if (name.contains("mac") || name.contains("darwin")) {
                return MACOS;
            }
            return LINUX;
        }
    }

    private static final String HOME = System.getProperty("user.home", "");

    private ToolPaths() {
    }

    /** The well-known candidate executable paths for {@code tool} on the running OS. */
    public static List<Path> wellKnownCandidates(ExternalTool tool) {
        return wellKnownCandidates(tool, Os.current(), System.getenv());
    }

    /**
     * As {@link #wellKnownCandidates(ExternalTool)}, against an explicit OS
     * and environment -- the test seam, and how {@link ToolLocator} would
     * probe a foreign layout without touching the real machine.
     */
    public static List<Path> wellKnownCandidates(ExternalTool tool, Os os, Map<String, String> env) {
        return switch (os) {
            case WINDOWS -> windowsCandidates(tool, env);
            case MACOS -> unixCandidates(tool,
                    "/usr/local/bin", "/opt/homebrew/bin");
            case LINUX -> unixCandidates(tool,
                    "/usr/bin", "/usr/local/bin");
        };
    }

    /**
     * The file names probed for {@code tool} inside a directory, per OS.
     * On Windows a directory can hold several executable spellings: the
     * native installer's {@code .exe} and npm's {@code .cmd} shim. The bare
     * extensionless file npm also writes is a POSIX shell script -- never
     * executable on Windows, so it is not probed. Elsewhere there is only
     * the bare name.
     */
    public static List<String> candidateNames(ExternalTool tool, Os os) {
        if (os != Os.WINDOWS) {
            return List.of(tool.configKey);
        }
        // git and node are native programs: only the .exe exists.
        if (tool == ExternalTool.GIT || tool == ExternalTool.NODE) {
            return List.of(tool.configKey + ".exe");
        }
        return List.of(tool.configKey + ".exe", tool.configKey + ".cmd");
    }

    /**
     * macOS and Linux roots. Homebrew on Apple Silicon owns
     * {@code /opt/homebrew/bin}; Intel homebrew and the native installers
     * use {@code /usr/local/bin} and {@code ~/.local/bin}. {@code ~/.volta/bin}
     * covers the Volta node shim. {@code /opt/dogbrew/bin} is the internal
     * package manager's path (ddtool is a homebrew/dogbrew cask on this
     * class of machine).
     */
    private static List<Path> unixCandidates(ExternalTool tool, String... roots) {
        List<Path> candidates = new ArrayList<>();
        for (String root : roots) {
            candidates.add(Path.of(root, tool.configKey));
        }
        candidates.add(Path.of(HOME, ".local", "bin", tool.configKey));
        if (tool == ExternalTool.NODE) {
            candidates.add(Path.of(HOME, ".volta", "bin", "node"));
        }
        if (tool == ExternalTool.DDTOOL) {
            candidates.add(Path.of("/opt/dogbrew/bin/ddtool"));
        }
        return List.copyOf(candidates);
    }

    /**
     * Windows roots. {@code Program Files\Git\cmd} is Git for Windows'
     * canonical install dir (git.exe lives in {@code cmd}, not {@code bin} --
     * the bin copy needs its POSIX layer on PATH). npm global shims land in
     * {@code %APPDATA%\npm}; scoop exposes everything through
     * {@code %USERPROFILE%\scoop\shims}; Chocolatey through
     * {@code %ProgramData%\chocolatey\bin}. Claude Code's native installer
     * and pi's install script use {@code %USERPROFILE%\.local\bin}.
     */
    private static List<Path> windowsCandidates(ExternalTool tool, Map<String, String> env) {
        List<Path> candidates = new ArrayList<>();
        switch (tool) {
            case GIT -> {
                addProgramFiles(env, candidates, "Git\\cmd");
                addLocalAppDataPrograms(env, candidates, "Git\\cmd");
                addUserProfile(env, candidates, "scoop\\shims");
                addProgramData(env, candidates, "chocolatey\\bin");
            }
            case NODE -> {
                addProgramFiles(env, candidates, "nodejs");
                addUserProfile(env, candidates, "scoop\\shims");
                addProgramData(env, candidates, "chocolatey\\bin");
            }
            default -> {
                addAppData(env, candidates, "npm");
                addUserProfile(env, candidates, ".local\\bin");
                addUserProfile(env, candidates, "scoop\\shims");
                addProgramData(env, candidates, "chocolatey\\bin");
            }
        }
        return List.copyOf(candidates);
    }

    /** Appends {@code tool} under {@code %ProgramFiles%\<sub>} and, when present, {@code %ProgramFiles(x86)%\<sub>}. */
    private static void addProgramFiles(Map<String, String> env, List<Path> candidates, String sub) {
        String programFiles = env.get("ProgramFiles");
        if (notBlank(programFiles)) {
            candidates.add(Path.of(programFiles, sub));
        }
        String programFilesX86 = env.get("ProgramFiles(x86)");
        if (notBlank(programFilesX86)) {
            candidates.add(Path.of(programFilesX86, sub));
        }
    }

    /** Appends {@code %LOCALAPPDATA%\Programs\<sub>} -- the per-user install root (no admin rights needed). */
    private static void addLocalAppDataPrograms(Map<String, String> env, List<Path> candidates, String sub) {
        String localAppData = env.get("LOCALAPPDATA");
        if (notBlank(localAppData)) {
            candidates.add(Path.of(localAppData, "Programs", sub));
        }
    }

    private static void addAppData(Map<String, String> env, List<Path> candidates, String sub) {
        String appData = env.get("APPDATA");
        if (notBlank(appData)) {
            candidates.add(Path.of(appData, sub));
        }
    }

    private static void addUserProfile(Map<String, String> env, List<Path> candidates, String sub) {
        String userProfile = env.get("USERPROFILE");
        if (notBlank(userProfile)) {
            candidates.add(Path.of(userProfile, sub));
        }
    }

    private static void addProgramData(Map<String, String> env, List<Path> candidates, String sub) {
        String programData = env.get("ProgramData");
        if (notBlank(programData)) {
            candidates.add(Path.of(programData, sub));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
