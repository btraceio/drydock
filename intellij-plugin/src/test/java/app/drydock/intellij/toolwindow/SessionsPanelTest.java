package app.drydock.intellij.toolwindow;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.intellij.toolwindow.SessionsPanel.Entry;
import app.drydock.intellij.toolwindow.SessionsPanel.Entry.Kind;
import app.drydock.intellij.toolwindow.SessionsPanel.WorktreeRow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T16 combined-view pure seams, pinned without opening the IDE — every
 * fixture is a real temporary git repository following the core {@code
 * WorktreeServiceTest} pattern, and every seam takes plain values (the
 * session rows, the raw {@code git worktree list} listing), so there is
 * nothing IDE-bound to instantiate. What is pinned: the one list groups
 * sessions / worktrees / purgable exactly as the design states — session
 * rows under a "Sessions" header, every live worktree (the main checkout
 * included) under "Worktrees", the stale (git-prunable) entries under
 * "Purgable" — a stale row never renders anywhere else, a detached-HEAD
 * worktree (real data on disk) stays with the worktrees rather than the
 * one-click section, and the main checkout is a worktree row whose Force
 * Remove is excluded while "New Session Here…" still serves it. The
 * absorbed {@code rowsOf}/{@code detailOf} semantics (the escapes "locked:
 * reason" and "stale" read apart) move here from the deleted {@code
 * WorktreeListPanelTest}.
 */
class SessionsPanelTest {

    private static final Instant T0 = Instant.parse("2026-10-05T10:00:00Z");

    // ---- section grouping (T16) ------------------------------------------------

    @Test
    void sessionsWorktreesAndStaleEntriesEachGetTheirOwnSection(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path sidebar = worktreeParent.resolve("sidebar");
        runGit(repo, "worktree", "add", sidebar.toString(), "-b", "feat/sidebar");
        Path stale = worktreeParent.resolve("stale");
        runGit(repo, "worktree", "add", stale.toString(), "-b", "feat/stale");
        deleteRecursively(stale);
        SessionListModel.Row session = sessionRow("claude");

        List<Worktree> listing;
        try (WorktreeService worktrees = new WorktreeService()) {
            listing = worktrees.list(repo).get();
        }

        List<Entry> entries = SessionsPanel.entriesOf(List.of(session), listing, repo.toRealPath());
        assertEquals(List.of(
                Kind.SECTION, Kind.SESSION,
                Kind.SECTION, Kind.WORKTREE, Kind.WORKTREE,
                Kind.SECTION, Kind.STALE), kinds(entries));
        assertEquals("Sessions", entries.get(0).title());
        assertEquals("Worktrees", entries.get(2).title());
        assertEquals("Purgable", entries.get(5).title());
        assertEquals(session, entries.get(1).session());
        // git lists the main checkout first, and it stays a Worktrees row —
        // a session on the main checkout is valid (design T16).
        assertTrue(entries.get(3).worktree().worktree().mainCheckout());
        assertEquals(Optional.of("feat/sidebar"), entries.get(4).worktree().worktree().branch());
        // The stale row — directory gone, git metadata left — is the one
        // Purgable row, and only there.
        assertTrue(entries.get(6).worktree().worktree().prunable());
        assertEquals(Optional.of("feat/stale"), entries.get(6).worktree().worktree().branch());
    }

    @Test
    void emptySectionsOmitTheirHeaders(@TempDir Path repoDir) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        List<Worktree> listing;
        try (WorktreeService worktrees = new WorktreeService()) {
            listing = worktrees.list(repo).get();
        }

        // No sessions, no stale entries: exactly one header (Worktrees) over
        // the main checkout — the list never carries empty headers.
        List<Entry> entries = SessionsPanel.entriesOf(List.of(), listing, repo.toRealPath());
        assertEquals(List.of(Kind.SECTION, Kind.WORKTREE), kinds(entries));
        assertEquals("Worktrees", entries.get(0).title());
        assertTrue(entries.get(1).worktree().worktree().mainCheckout());
    }

    @Test
    void aDetachedWorktreeStaysWithTheWorktreesNotThePurgableSection(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        String head = runGitCapture(repo, "rev-parse", "HEAD").strip();
        Path detached = worktreeParent.resolve("detached");
        runGit(repo, "worktree", "add", "--detach", detached.toString(), head);
        List<Worktree> listing;
        try (WorktreeService worktrees = new WorktreeService()) {
            listing = worktrees.list(repo).get();
        }

        // A detached-HEAD worktree's directory exists and holds real data —
        // it must not be one-click purgable; its escape stays the confirmed
        // Force Remove (design T16).
        List<Entry> entries = SessionsPanel.entriesOf(List.of(), listing, repo.toRealPath());
        WorktreeRow detachedRow = entries.stream()
                .filter(entry -> entry.kind() == Kind.WORKTREE)
                .map(Entry::worktree)
                .filter(row -> row.worktree().detached())
                .findFirst().orElseThrow();
        assertTrue(detachedRow.detail().startsWith("detached"), detachedRow.detail());
        assertTrue(detachedRow.removable(), "a non-main worktree is force-removable, never purged blind");
    }

    // ---- the Purgable section's membership rule ---------------------------------

    @Test
    void aLockedWorktreeWhoseDirectoryIsGoneIsNotMarkedPurgableByGit(
            @TempDir Path repoDir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path locked = worktreeParent.resolve("locked");
        runGit(repo, "worktree", "add", locked.toString(), "-b", "feat/locked");
        runGit(repo, "worktree", "lock", "--reason", "initializing", locked.toString());
        deleteRecursively(locked);
        List<Worktree> listing;
        try (WorktreeService worktrees = new WorktreeService()) {
            listing = worktrees.list(repo).get();
        }

        // git itself does not annotate a locked-gone worktree prunable (a
        // lock is a deliberate "do not touch", and `git worktree prune`
        // skips it), so the panel cannot offer it for one-click purge — it
        // renders as a live row whose detail says why it is held.
        WorktreeRow lockedRow = SessionsPanel.rowsOf(listing, repo.toRealPath()).stream()
                .filter(row -> row.worktree().locked())
                .findFirst().orElseThrow();
        assertFalse(lockedRow.worktree().prunable());
        assertTrue(lockedRow.detail().contains("locked: initializing"), lockedRow.detail());
        assertFalse(lockedRow.detail().contains("stale"), "locked must not read as stale: " + lockedRow.detail());
        List<Entry> entries = SessionsPanel.entriesOf(List.of(), listing, repo.toRealPath());
        assertTrue(entries.stream().noneMatch(entry -> entry.kind() == Kind.STALE),
                "a locked-gone worktree is not purgable");
    }

    // ---- Force Remove exclusion + detail lines (absorbed from WorktreeListPanel) -

    @Test
    void theMainCheckoutIsARowButNeverForceRemovable(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path sidebar = worktreeParent.resolve("sidebar");
        runGit(repo, "worktree", "add", sidebar.toString(), "-b", "feat/sidebar");
        List<Worktree> listing;
        try (WorktreeService worktrees = new WorktreeService()) {
            listing = worktrees.list(repo).get();
        }

        List<WorktreeRow> rows = SessionsPanel.rowsOf(listing, repo.toRealPath());
        WorktreeRow main = rows.get(0);
        assertTrue(main.worktree().mainCheckout());
        assertTrue(main.current(), "the project's own checkout is the row Open-as-Project is disabled on");
        assertFalse(main.removable(), "the main checkout is the repository itself — never force-removable");
        assertTrue(main.detail().contains("main checkout"), main.detail());
        WorktreeRow sidebarRow = rows.get(1);
        assertFalse(sidebarRow.current());
        assertTrue(sidebarRow.removable());
        assertTrue(sidebarRow.detail().contains("feat/sidebar"), sidebarRow.detail());
    }

    @Test
    void aStaleRowReadsStaleAndKeepsItsBranchInItsDetail(@TempDir Path repoDir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(repoDir);
        Path stale = worktreeParent.resolve("stale");
        runGit(repo, "worktree", "add", stale.toString(), "-b", "feat/stale");
        deleteRecursively(stale);
        List<Worktree> listing;
        try (WorktreeService worktrees = new WorktreeService()) {
            listing = worktrees.list(repo).get();
        }

        WorktreeRow staleRow = SessionsPanel.rowsOf(listing, repo.toRealPath()).get(1);
        assertTrue(staleRow.worktree().prunable());
        assertTrue(staleRow.detail().contains("feat/stale"), staleRow.detail());
        assertTrue(staleRow.detail().contains("stale"), staleRow.detail());
        assertFalse(staleRow.detail().contains("locked"), "stale must not read as locked: " + staleRow.detail());
    }

    // ---- fixtures (core WorktreeServiceTest pattern) ---------------------------

    private static SessionListModel.Row sessionRow(String name) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), name,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                SessionWorkspace.inRepository(Path.of("/repo/" + name)), T0.minusSeconds(60))
                .status(SessionStatus.RUNNING)
                .lastOpenedAt(T0)
                .build();
        return new SessionListModel.Row(session, Optional.empty());
    }

    private static List<Kind> kinds(List<Entry> entries) {
        return entries.stream().map(Entry::kind).toList();
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Drydock Fixture");
        runGit(repo, "config", "user.email", "fixture@drydock.invalid");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        runGitCapture(repo, args);
    }

    private static String runGitCapture(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("git " + List.of(args) + " failed (" + exit + "): " + output);
        }
        return output;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
