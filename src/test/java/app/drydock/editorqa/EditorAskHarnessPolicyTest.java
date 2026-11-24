package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorAskHarnessPolicyTest {

    private static Optional<String> noModels(AgentKind kind) {
        return Optional.empty();
    }

    private static EditorAskHarnessPolicy.FreshResolution resolve(EditorAskHarnessPolicy.Choice choice,
                                                                  Predicate<AgentKind> available,
                                                                  Function<AgentKind, Optional<String>> models) {
        return EditorAskHarnessPolicy.resolveFresh(choice, available, models);
    }

    // --- Auto's Q&A preference order: Pi, Claude, Codex (never Terminal) ---

    @Test
    void allThreeAvailableAutoResolvesPi() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> true,
                EditorAskHarnessPolicyTest::noModels);
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.PI, Optional.empty()), resolution);
    }

    @Test
    void withoutPiAutoResolvesClaude() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> kind != AgentKind.PI,
                EditorAskHarnessPolicyTest::noModels);
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.CLAUDE, Optional.empty()), resolution);
    }

    @Test
    void onlyCodexAvailableAutoResolvesCodex() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> kind == AgentKind.CODEX,
                EditorAskHarnessPolicyTest::noModels);
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.CODEX, Optional.empty()), resolution);
    }

    @Test
    void noneAvailableDisablesWithAReason() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> false, EditorAskHarnessPolicyTest::noModels);
        assertTrue(resolution instanceof EditorAskHarnessPolicy.Disabled, "expected Disabled, got " + resolution);
        String reason = ((EditorAskHarnessPolicy.Disabled) resolution).reason();
        assertTrue(!reason.isBlank());
        assertTrue(reason.contains("Settings"), "the reason must point at Settings: " + reason);
    }

    @Test
    void terminalIsNeverAQaHarness() {
        // A shell-only machine must disable Q&A, not fall back to Terminal:
        // a bare shell has no answer tool to call.
        assertTrue(!EditorAskHarnessPolicy.QA_PREFERENCE.contains(AgentKind.TERMINAL));
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> kind == AgentKind.TERMINAL,
                EditorAskHarnessPolicyTest::noModels);
        assertTrue(resolution instanceof EditorAskHarnessPolicy.Disabled, "expected Disabled, got " + resolution);
    }

    // --- pinned harnesses: available or a visible refusal, no fallback ---

    @Test
    void pinnedAvailableResolvesWithItsOwnModel() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.PI, kind -> true,
                kind -> kind == AgentKind.PI ? Optional.of("pi-large") : Optional.empty());
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.PI, Optional.of("pi-large")), resolution);
    }

    @Test
    void pinnedUnavailableFailsVisiblyEvenWhenOthersAreAvailable() {
        for (EditorAskHarnessPolicy.Choice pinned : new EditorAskHarnessPolicy.Choice[] {
                EditorAskHarnessPolicy.Choice.PI, EditorAskHarnessPolicy.Choice.CLAUDE,
                EditorAskHarnessPolicy.Choice.CODEX }) {
            AgentKind wanted = switch (pinned) {
                case PI -> AgentKind.PI;
                case CLAUDE -> AgentKind.CLAUDE;
                case CODEX -> AgentKind.CODEX;
                case AUTO -> throw new AssertionError();
            };
            // Every OTHER harness is available: the pinned one still must
            // not be substituted.
            var resolution = resolve(pinned, kind -> kind != wanted, EditorAskHarnessPolicyTest::noModels);
            assertTrue(resolution instanceof EditorAskHarnessPolicy.Disabled,
                    "pinned " + wanted + " must fail visibly, got " + resolution);
            String reason = ((EditorAskHarnessPolicy.Disabled) resolution).reason();
            assertTrue(reason.contains(wanted.persistedName()),
                    "the reason must name the pinned harness: " + reason);
            assertTrue(reason.contains("Settings"), "the reason must point at Settings: " + reason);
        }
    }

    // --- the model stays with its harness ---

    @Test
    void piModelIsNotLeakedToClaudeWhenAutoMovesDown() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> kind != AgentKind.PI,
                kind -> switch (kind) {
                    case PI -> Optional.of("pi-override");
                    case CLAUDE -> Optional.of("claude-override");
                    default -> Optional.empty();
                });
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.CLAUDE, Optional.of("claude-override")), resolution);
    }

    @Test
    void resolvedHarnessWithNoOwnModelOmitsTheFlag() {
        // Pi's override exists but Auto resolved Codex: no model at all,
        // never another harness's model.
        var resolution = resolve(EditorAskHarnessPolicy.Choice.AUTO, kind -> kind == AgentKind.CODEX,
                kind -> kind == AgentKind.PI ? Optional.of("pi-override") : Optional.empty());
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.CODEX, Optional.empty()), resolution);
    }

    @Test
    void aBlankModelIsNormalizedToNoFlag() {
        var resolution = resolve(EditorAskHarnessPolicy.Choice.PI, kind -> true,
                kind -> Optional.of("   "));
        assertEquals(new EditorAskHarnessPolicy.Fresh(AgentKind.PI, Optional.empty()), resolution);
    }

    // --- keep-original-harness resume: no model flag, ever ---

    @Test
    void keepOriginalHarnessResumesWithoutAModel() {
        EditorAskHarnessPolicy.Resume resume = EditorAskHarnessPolicy.keepOriginal(AgentKind.CLAUDE);
        assertEquals(AgentKind.CLAUDE, resume.harness());
        // The record carries no model component: the type is the guarantee.
        assertEquals(1, resume.getClass().getRecordComponents().length);
    }

    // --- choice persistence: stable names, lenient decode ---

    @Test
    void choiceRoundTripsItsPersistedName() {
        for (EditorAskHarnessPolicy.Choice choice : EditorAskHarnessPolicy.Choice.values()) {
            assertEquals(choice, EditorAskHarnessPolicy.Choice.fromPersisted(choice.persistedName()));
        }
    }

    @Test
    void unknownOrLegacyStoredNamesDecodeToAuto() {
        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskHarnessPolicy.Choice.fromPersisted(null));
        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskHarnessPolicy.Choice.fromPersisted(""));
        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskHarnessPolicy.Choice.fromPersisted("nonsense"));
        // terminal is an ordinary kind, never a Q&A choice: stored legacy
        // values naming it fall back to Auto rather than pinning a shell.
        assertEquals(EditorAskHarnessPolicy.Choice.AUTO, EditorAskHarnessPolicy.Choice.fromPersisted("terminal"));
        assertEquals(EditorAskHarnessPolicy.Choice.CLAUDE, EditorAskHarnessPolicy.Choice.fromPersisted(" claude "));
    }

    // --- the settings page's model guard shares EditorAskLimits' bound ---

    @Test
    void modelRefusalBoundariesComeFromEditorAskLimits() {
        assertNull(EditorAskLimits.checkModel(null));
        assertEquals("  ", EditorAskLimits.checkModel("  "));
        // checkModel validates and returns its input unchanged; stripping is
        // the settings reader's job (EditorAskSettings.model strips).
        assertEquals(" sonnet-4 ", EditorAskLimits.checkModel(" sonnet-4 "));
        assertEquals(256, "m".repeat(256).getBytes(StandardCharsets.UTF_8).length);
        assertEquals("m".repeat(256), EditorAskLimits.checkModel("m".repeat(256)));
        assertThrows(EditorAskLimits.RejectedInputException.class,
                () -> EditorAskLimits.checkModel("m".repeat(257)));
        assertThrows(EditorAskLimits.RejectedInputException.class, () -> EditorAskLimits.checkModel("a\nb"));
        assertThrows(EditorAskLimits.RejectedInputException.class, () -> EditorAskLimits.checkModel("a\tb"));
        assertThrows(EditorAskLimits.RejectedInputException.class, () -> EditorAskLimits.checkModel("a\u0000b"));
        assertThrows(EditorAskLimits.RejectedInputException.class, () -> EditorAskLimits.checkModel("a\u001Bb"));
    }
}
