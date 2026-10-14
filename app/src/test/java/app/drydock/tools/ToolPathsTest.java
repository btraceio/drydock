package app.drydock.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPathsTest {

    // ---- Windows ----

    @Test
    void windowsGitCandidatesCoverProgramFilesScoopAndChoco() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.GIT, ToolPaths.Os.WINDOWS, Map.of(
                "ProgramFiles", "C:\\Program Files",
                "ProgramFiles(x86)", "C:\\Program Files (x86)",
                "LOCALAPPDATA", "C:\\Users\\u\\AppData\\Local",
                "USERPROFILE", "C:\\Users\\u",
                "ProgramData", "C:\\ProgramData"));

        assertTrue(candidates.contains(Path.of("C:\\Program Files", "Git\\cmd")));
        assertTrue(candidates.contains(Path.of("C:\\Program Files (x86)", "Git\\cmd")));
        assertTrue(candidates.contains(Path.of("C:\\Users\\u\\AppData\\Local", "Programs", "Git\\cmd")));
        assertTrue(candidates.contains(Path.of("C:\\Users\\u", "scoop\\shims")));
        assertTrue(candidates.contains(Path.of("C:\\ProgramData", "chocolatey\\bin")));
    }

    @Test
    void windowsHarnessCandidatesCoverNpmScoopChocoAndLocalBin() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.CLAUDE, ToolPaths.Os.WINDOWS, Map.of(
                "APPDATA", "C:\\Users\\u\\AppData\\Roaming",
                "USERPROFILE", "C:\\Users\\u",
                "ProgramData", "C:\\ProgramData"));

        assertTrue(candidates.contains(Path.of("C:\\Users\\u\\AppData\\Roaming", "npm")));
        assertTrue(candidates.contains(Path.of("C:\\Users\\u", ".local\\bin")));
        assertTrue(candidates.contains(Path.of("C:\\Users\\u", "scoop\\shims")));
        assertTrue(candidates.contains(Path.of("C:\\ProgramData", "chocolatey\\bin")));
    }

    @Test
    void windowsNodeCandidatesCoverProgramFilesNodejs() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.NODE, ToolPaths.Os.WINDOWS, Map.of(
                "ProgramFiles", "C:\\Program Files"));

        assertTrue(candidates.contains(Path.of("C:\\Program Files", "nodejs")));
    }

    @Test
    void missingWindowsEnvironmentVariablesAreSkipped() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.GIT, ToolPaths.Os.WINDOWS, Map.of());
        assertTrue(candidates.isEmpty(), "no env vars, no invented roots");
    }

    @Test
    void windowsCandidateNamesPreferExeThenCmdShimForHarnesses() {
        assertEquals(List.of("claude.exe", "claude.cmd"),
                ToolPaths.candidateNames(ExternalTool.CLAUDE, ToolPaths.Os.WINDOWS));
    }

    @Test
    void windowsNativeToolsProbeOnlyTheExe() {
        assertEquals(List.of("git.exe"), ToolPaths.candidateNames(ExternalTool.GIT, ToolPaths.Os.WINDOWS));
        assertEquals(List.of("node.exe"), ToolPaths.candidateNames(ExternalTool.NODE, ToolPaths.Os.WINDOWS));
    }

    // ---- macOS / Linux ----

    @Test
    void macosGitCandidatesCoverHomebrewBothArchitectures() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.GIT, ToolPaths.Os.MACOS, Map.of());
        assertTrue(candidates.contains(Path.of("/usr/local/bin/git")));
        assertTrue(candidates.contains(Path.of("/opt/homebrew/bin/git")));
        assertTrue(candidates.contains(Path.of(System.getProperty("user.home"), ".local", "bin", "git")));
    }

    @Test
    void linuxGitCandidatesCoverTheFhsBinDirs() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.GIT, ToolPaths.Os.LINUX, Map.of());
        assertTrue(candidates.contains(Path.of("/usr/bin/git")));
        assertTrue(candidates.contains(Path.of("/usr/local/bin/git")));
    }

    @Test
    void macosNodeCandidatesCoverVolta() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.NODE, ToolPaths.Os.MACOS, Map.of());
        assertTrue(candidates.contains(Path.of(System.getProperty("user.home"), ".volta", "bin", "node")));
    }

    @Test
    void macosDdtoolCandidatesCoverDogbrew() {
        List<Path> candidates = ToolPaths.wellKnownCandidates(ExternalTool.DDTOOL, ToolPaths.Os.MACOS, Map.of());
        assertTrue(candidates.contains(Path.of("/opt/dogbrew/bin/ddtool")));
    }

    @Test
    void unixCandidateNamesAreTheBareName(@TempDir Path tempDir) throws IOException {
        assertEquals(List.of("git"), ToolPaths.candidateNames(ExternalTool.GIT, ToolPaths.Os.MACOS));
        assertEquals(List.of("pi"), ToolPaths.candidateNames(ExternalTool.PI, ToolPaths.Os.LINUX));
    }
}
