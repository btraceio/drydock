package app.drydock.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selector {@code gh pr view} is handed for a checkout's branch: the
 * {@code pr-<n>} alias resolves to the PR number it encodes -- gh matches a
 * branch by the PR's own head name, which a drydock checkout never is -- and
 * an ordinary branch is passed through verbatim. The argv gh actually
 * receives is pinned too, with a scripted gh, not just the pure selector: a
 * call site that went back to passing the branch would fail here.
 */
class GhCliViewPrSelectorTest {

    @Test
    void aPrCheckoutBranchResolvesToItsPrNumber() {
        assertEquals("664", GhCliService.prSelector("pr-664"));
        assertEquals("7", GhCliService.prSelector("pr-7"));
    }

    @Test
    void anOrdinaryBranchIsPassedThrough() {
        assertEquals("main", GhCliService.prSelector("main"));
        assertEquals("fix/tabs", GhCliService.prSelector("fix/tabs"));
    }

    @Test
    void aLookalikeBranchNameIsNotAnAlias() {
        // Not digits, not ours: gh gets the branch, not a bogus number.
        assertEquals("pr-fix-login", GhCliService.prSelector("pr-fix-login"));
        assertEquals("pr-664x", GhCliService.prSelector("pr-664x"));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void viewPrHandsGhThePrNumberForAPrCheckoutBranch(@TempDir Path dir) throws Exception {
        Path receipt = scriptedGh(dir);

        Optional<GhCliService.PrInfo> info = GhCliService.viewPrWith(dir.resolve("bin/gh"), dir, "pr-858");

        String logged = Files.readString(receipt);
        assertTrue(logged.contains("args=pr view 858 --json number,state,url"), logged);
        assertFalse(logged.contains("pr view pr-858"), logged);
        assertTrue(info.isPresent(), "the scripted gh answered, so the lookup is not empty");
        assertEquals(858, info.get().number());
        assertEquals(GhCliService.PrInfo.PrLifecycle.OPEN, info.get().state());
        assertEquals(Optional.of("https://example.test/pull/858"), info.get().url());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void viewPrHandsGhAnOrdinaryBranchVerbatim(@TempDir Path dir) throws Exception {
        Path receipt = scriptedGh(dir);

        Optional<GhCliService.PrInfo> info = GhCliService.viewPrWith(dir.resolve("bin/gh"), dir, "fix/tabs");

        String logged = Files.readString(receipt);
        assertTrue(logged.contains("args=pr view fix/tabs --json number,state,url"), logged);
        assertEquals(Optional.of(858), info.map(GhCliService.PrInfo::number));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anOptionLikeOrBlankBranchNeverReachesGh(@TempDir Path dir) throws Exception {
        Path receipt = scriptedGh(dir);
        Path gh = dir.resolve("bin/gh");

        assertEquals(Optional.empty(), GhCliService.viewPrWith(gh, dir, "-x"));
        assertEquals(Optional.empty(), GhCliService.viewPrWith(gh, dir, "  "));

        assertFalse(Files.exists(receipt), "a refused branch must never reach gh's argv");
    }

    /**
     * A fake gh in {@code dir/bin} that records its argv to
     * {@code dir/receipt.txt} and answers like
     * {@code gh pr view --json number,state,url}.
     */
    private static Path scriptedGh(Path dir) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path receipt = dir.resolve("receipt.txt");
        Files.writeString(bin.resolve("gh"),
                "#!/bin/sh\nprintf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                        + "printf '%s\\n' '{\"number\":858,\"state\":\"OPEN\",\"url\":\"https://example.test/pull/858\"}'\n"
                        + "exit 0\n");
        bin.resolve("gh").toFile().setExecutable(true);
        return receipt;
    }
}
